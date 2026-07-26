package com.jxc.wefolio.service.teamportfolio;

import com.alibaba.fastjson2.JSON;
import com.jxc.wefolio.constant.TeamPortfolioConstants;
import com.alibaba.fastjson2.JSONObject;
import com.jxc.wefolio.dict.TeamPortfolioComponentTypeDict;
import com.jxc.wefolio.dto.teamportfolio.TeamPortfolioConfigDto;
import com.jxc.wefolio.dto.teamportfolio.TeamPortfolioRenderDto;
import com.jxc.wefolio.exception.BusinessException;
import com.jxc.wefolio.service.teamportfolio.component.carousel.TeamCarouselComponentRenderer;
import com.jxc.wefolio.service.teamportfolio.component.contactform.TeamContactFormComponentRenderer;
import com.jxc.wefolio.service.teamportfolio.component.divider.TeamDividerComponentRenderer;
import com.jxc.wefolio.service.teamportfolio.component.memberportfoliogrid.TeamMemberPortfolioGridComponentRenderer;
import com.jxc.wefolio.service.teamportfolio.component.memberportfoliolist.TeamMemberPortfolioListComponentRenderer;
import com.jxc.wefolio.service.teamportfolio.component.qrcontact.TeamQrContactComponentRenderer;
import com.jxc.wefolio.service.teamportfolio.component.schedulequery.TeamScheduleQueryComponentRenderer;
import com.jxc.wefolio.service.teamportfolio.component.singlework.TeamSingleWorkComponentRenderer;
import com.jxc.wefolio.service.teamportfolio.component.teamprofile.TeamProfileComponentRenderer;
import com.jxc.wefolio.service.teamportfolio.component.textsection.TeamTextSectionComponentRenderer;
import org.springframework.stereotype.Service;

import java.util.Comparator;
import java.util.List;

/**
 * 团队作品集顶层渲染与组件分发服务。
 */
@Service
public class TeamPortfolioRenderService {

    /** 默认页面标题。 */
    private static final String DEFAULT_TITLE = "团队作品集";

    /** 配置格式错误提示。 */
    private static final String CONFIG_INVALID_MESSAGE = "团队作品集配置格式不正确";

    /** Schema 不支持提示。 */
    private static final String SCHEMA_UNSUPPORTED_MESSAGE = "团队作品集配置版本不支持";

    /** 组件类型不支持提示。 */
    private static final String COMPONENT_TYPE_UNSUPPORTED_MESSAGE = "团队作品集组件类型不支持";

    /** 组件上下文非法提示。 */
    private static final String CONTEXT_INVALID_MESSAGE = "团队作品集组件上下文不正确";

    private final TeamProfileComponentRenderer teamProfileRenderer;
    private final TeamCarouselComponentRenderer carouselRenderer;
    private final TeamSingleWorkComponentRenderer singleWorkRenderer;
    private final TeamDividerComponentRenderer dividerRenderer;
    private final TeamMemberPortfolioGridComponentRenderer gridRenderer;
    private final TeamMemberPortfolioListComponentRenderer listRenderer;
    private final TeamTextSectionComponentRenderer textRenderer;
    private final TeamScheduleQueryComponentRenderer scheduleRenderer;
    private final TeamContactFormComponentRenderer contactRenderer;
    private final TeamQrContactComponentRenderer qrRenderer;

    /**
     * 创建团队作品集渲染服务。
     */
    public TeamPortfolioRenderService(
            TeamProfileComponentRenderer teamProfileRenderer,
            TeamCarouselComponentRenderer carouselRenderer,
            TeamSingleWorkComponentRenderer singleWorkRenderer,
            TeamDividerComponentRenderer dividerRenderer,
            TeamMemberPortfolioGridComponentRenderer gridRenderer,
            TeamMemberPortfolioListComponentRenderer listRenderer,
            TeamTextSectionComponentRenderer textRenderer,
            TeamScheduleQueryComponentRenderer scheduleRenderer,
            TeamContactFormComponentRenderer contactRenderer,
            TeamQrContactComponentRenderer qrRenderer
    ) {
        this.teamProfileRenderer = teamProfileRenderer;
        this.carouselRenderer = carouselRenderer;
        this.singleWorkRenderer = singleWorkRenderer;
        this.dividerRenderer = dividerRenderer;
        this.gridRenderer = gridRenderer;
        this.listRenderer = listRenderer;
        this.textRenderer = textRenderer;
        this.scheduleRenderer = scheduleRenderer;
        this.contactRenderer = contactRenderer;
        this.qrRenderer = qrRenderer;
    }

    /**
     * 渲染规范化团队作品集配置。
     *
     * @param normalizedJson 已规范化配置 JSON
     * @param context 组件上下文
     * @return 页面渲染数据
     */
    public TeamPortfolioRenderDto render(String normalizedJson, TeamPortfolioComponentContext context) {
        validateContext(context);
        TeamPortfolioConfigDto config = parseConfig(normalizedJson);
        validateSchema(config);
        List<TeamPortfolioConfigDto.ComponentEnvelope> components = sortedEnabledComponents(config.getComponents());
        validateComponentTypes(components);
        TeamPortfolioRenderDto render = new TeamPortfolioRenderDto();
        render.setPortfolioId(context.portfolioId());
        render.setTeamId(context.teamId());
        render.setShare(copyShare(config.getShare()));
        render.setTitle(resolveTitle(config.getShare()));
        render.setComponents(components.stream()
                .map(component -> renderComponent(component, context))
                .toList());
        return render;
    }

    /**
     * 校验组件上下文。
     */
    private void validateContext(TeamPortfolioComponentContext context) {
        if (context == null || context.teamId() <= 0 || context.portfolioId() <= 0 || context.revision() < 0) {
            throw new BusinessException(CONTEXT_INVALID_MESSAGE);
        }
    }

    /**
     * 解析配置 JSON。
     */
    private TeamPortfolioConfigDto parseConfig(String normalizedJson) {
        if (normalizedJson == null || normalizedJson.isBlank()) {
            throw new BusinessException(CONFIG_INVALID_MESSAGE);
        }
        try {
            TeamPortfolioConfigDto config = JSON.parseObject(normalizedJson, TeamPortfolioConfigDto.class);
            if (config == null) {
                throw new BusinessException(CONFIG_INVALID_MESSAGE);
            }
            return config;
        } catch (BusinessException exception) {
            throw exception;
        } catch (RuntimeException exception) {
            throw new BusinessException(CONFIG_INVALID_MESSAGE);
        }
    }

    /**
     * 校验团队 Schema。
     */
    private void validateSchema(TeamPortfolioConfigDto config) {
        if (!TeamPortfolioConstants.SCHEMA_VERSION_STANDARD_TEAM_V1.equals(config.getSchemaVersion())) {
            throw new BusinessException(SCHEMA_UNSUPPORTED_MESSAGE);
        }
    }

    /**
     * 生成单个组件渲染数据。
     */
    private TeamPortfolioRenderDto.Component renderComponent(
            TeamPortfolioConfigDto.ComponentEnvelope component,
            TeamPortfolioComponentContext context
    ) {
        TeamPortfolioComponentTypeDict componentType = componentType(component.getComponentType());
        TeamPortfolioRenderDto.Component rendered = new TeamPortfolioRenderDto.Component();
        rendered.setComponentKey(component.getComponentKey());
        rendered.setComponentType(componentType.getCode());
        rendered.setName(componentType.getDisplayName());
        rendered.setSortOrder(component.getSortOrder());
        rendered.setData(renderComponentData(componentType, component.getConfig(), context));
        return rendered;
    }

    /**
     * 分发至对应组件渲染器。
     */
    private JSONObject renderComponentData(
            TeamPortfolioComponentTypeDict componentType,
            JSONObject componentConfig,
            TeamPortfolioComponentContext context
    ) {
        JSONObject config = componentConfig == null ? new JSONObject() : componentConfig;
        return switch (componentType) {
            case TEAM_PROFILE -> teamProfileRenderer.render(config, context);
            case CAROUSEL -> carouselRenderer.render(config, context);
            case SINGLE_WORK -> singleWorkRenderer.render(config, context);
            case DIVIDER -> dividerRenderer.render(config, context);
            case MEMBER_PORTFOLIO_GRID -> gridRenderer.render(config, context);
            case MEMBER_PORTFOLIO_LIST -> listRenderer.render(config, context);
            case TEXT_SECTION -> textRenderer.render(config, context);
            case SCHEDULE_QUERY -> scheduleRenderer.render(config, context);
            case CONTACT_FORM -> contactRenderer.render(config, context);
            case QR_CONTACT -> qrRenderer.render(config, context);
        };
    }

    /**
     * 获取组件类型。
     */
    private TeamPortfolioComponentTypeDict componentType(String typeCode) {
        TeamPortfolioComponentTypeDict componentType = TeamPortfolioComponentTypeDict.fromCode(typeCode);
        if (componentType == null) {
            throw new BusinessException(COMPONENT_TYPE_UNSUPPORTED_MESSAGE);
        }
        return componentType;
    }

    /**
     * 在渲染任何组件前校验全部顶层组件类型。
     */
    private void validateComponentTypes(List<TeamPortfolioConfigDto.ComponentEnvelope> components) {
        components.forEach(component -> componentType(component.getComponentType()));
    }

    /**
     * 对已启用组件进行稳定排序。
     */
    private List<TeamPortfolioConfigDto.ComponentEnvelope> sortedEnabledComponents(
            List<TeamPortfolioConfigDto.ComponentEnvelope> components
    ) {
        if (components == null) {
            return List.of();
        }
        return components.stream()
                .filter(component -> component != null && !Boolean.FALSE.equals(component.getEnabled()))
                .sorted(Comparator.comparing(this::sortOrder))
                .toList();
    }

    /**
     * 复制分享信息。
     */
    private TeamPortfolioConfigDto.Share copyShare(TeamPortfolioConfigDto.Share source) {
        if (source == null) {
            return null;
        }
        TeamPortfolioConfigDto.Share copy = new TeamPortfolioConfigDto.Share();
        copy.setTitle(source.getTitle());
        copy.setDescription(source.getDescription());
        copy.setCoverUrl(source.getCoverUrl());
        return copy;
    }

    /**
     * 解析页面标题。
     */
    private String resolveTitle(TeamPortfolioConfigDto.Share share) {
        if (share == null || share.getTitle() == null || share.getTitle().isBlank()) {
            return DEFAULT_TITLE;
        }
        return share.getTitle();
    }

    /**
     * 获取安全排序值。
     */
    private int sortOrder(TeamPortfolioConfigDto.ComponentEnvelope component) {
        return component.getSortOrder() == null ? Integer.MAX_VALUE : component.getSortOrder();
    }

}
