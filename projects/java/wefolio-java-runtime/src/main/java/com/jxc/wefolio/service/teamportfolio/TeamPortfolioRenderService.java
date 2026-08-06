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
import com.jxc.wefolio.service.teamportfolio.component.videocarousel.TeamVideoCarouselComponentRenderer;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * 团队作品集顶层渲染与组件分发服务。
 */
@Slf4j
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

    /** 底部导航最少菜单数。 */
    private static final int BOTTOM_NAV_MIN_ITEM_COUNT = 2;

    /** 底部导航最多菜单数。 */
    private static final int BOTTOM_NAV_MAX_ITEM_COUNT = 4;

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
    private final TeamVideoCarouselComponentRenderer videoCarouselRenderer;

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
            TeamQrContactComponentRenderer qrRenderer,
            TeamVideoCarouselComponentRenderer videoCarouselRenderer
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
        this.videoCarouselRenderer = videoCarouselRenderer;
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
        validateNormalizedStructure(config, context);
        validateComponentTypes(TeamPortfolioComponentTraversal.listComponentLocations(config).stream()
                .map(TeamPortfolioComponentTraversal.ComponentLocation::component)
                .filter(component -> !Boolean.FALSE.equals(component.getEnabled()))
                .toList());
        TeamPortfolioRenderDto render = new TeamPortfolioRenderDto();
        render.setPortfolioId(context.portfolioId());
        render.setTeamId(context.teamId());
        render.setShare(copyShare(config.getShare()));
        render.setTitle(resolveTitle(config.getShare()));
        render.setStyle(buildStyle(config.getStyle()));
        render.setComponents(buildComponents(config.getComponents(), context));
        render.setBottomNav(buildBottomNav(config.getBottomNav(), context));
        return render;
    }

    /**
     * 构建页面样式。
     */
    private TeamPortfolioRenderDto.Style buildStyle(TeamPortfolioConfigDto.Style configuredStyle) {
        String backgroundColor = normalizeBackgroundColor(
                configuredStyle == null ? null : configuredStyle.getBackgroundColor());
        TeamPortfolioRenderDto.Style style = new TeamPortfolioRenderDto.Style();
        style.setBackgroundColor(backgroundColor);
        style.setThemeMode(resolveThemeMode(backgroundColor));
        return style;
    }

    /**
     * 构建底部导航渲染数据。
     */
    private TeamPortfolioRenderDto.BottomNav buildBottomNav(
            TeamPortfolioConfigDto.BottomNav configuredBottomNav,
            TeamPortfolioComponentContext context
    ) {
        TeamPortfolioRenderDto.BottomNav bottomNav = new TeamPortfolioRenderDto.BottomNav();
        if (configuredBottomNav == null || !Boolean.TRUE.equals(configuredBottomNav.getEnabled())) {
            bottomNav.setEnabled(false);
            bottomNav.setItems(List.of());
            return bottomNav;
        }
        bottomNav.setEnabled(true);
        List<TeamPortfolioRenderDto.BottomNavItem> items = new ArrayList<>();
        List<TeamPortfolioConfigDto.BottomNavItem> configuredItems =
                configuredBottomNav.getItems() == null ? List.of() : configuredBottomNav.getItems();
        for (int index = 0; index < configuredItems.size(); index++) {
            TeamPortfolioConfigDto.BottomNavItem configuredItem = configuredItems.get(index);
            TeamPortfolioRenderDto.BottomNavItem item = new TeamPortfolioRenderDto.BottomNavItem();
            item.setKey(defaultString(configuredItem.getKey()));
            item.setTitle(defaultString(configuredItem.getTitle()));
            if (index > 0) {
                item.setComponents(buildComponents(configuredItem.getComponents(), context));
            }
            items.add(item);
        }
        bottomNav.setItems(items);
        return bottomNav;
    }

    /**
     * 使用统一入口渲染一个菜单的全部启用组件。
     */
    private List<TeamPortfolioRenderDto.Component> buildComponents(
            List<TeamPortfolioConfigDto.ComponentEnvelope> components,
            TeamPortfolioComponentContext context
    ) {
        return sortedEnabledComponents(components).stream()
                .map(component -> renderComponent(component, context))
                .filter(Objects::nonNull)
                .toList();
    }

    /**
     * 规范化背景色，委托给共享的样式规范化工具。
     *
     * @see TeamPortfolioStyleNormalizer
     */
    private String normalizeBackgroundColor(String backgroundColor) {
        return TeamPortfolioStyleNormalizer.normalizeBackgroundColor(backgroundColor);
    }

    /**
     * 根据 YIQ 亮度推导页面主题，委托给共享的样式规范化工具。
     *
     * @see TeamPortfolioStyleNormalizer
     */
    private String resolveThemeMode(String backgroundColor) {
        return TeamPortfolioStyleNormalizer.resolveThemeMode(backgroundColor);
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
     * 校验持久化配置的菜单容器和组件信封完整性。
     */
    private void validateNormalizedStructure(
            TeamPortfolioConfigDto config,
            TeamPortfolioComponentContext context
    ) {
        if (config.getComponents() == null) {
            throw new BusinessException(CONFIG_INVALID_MESSAGE);
        }
        TeamPortfolioConfigDto.BottomNav bottomNav = config.getBottomNav();
        if (bottomNav != null && Boolean.TRUE.equals(bottomNav.getEnabled())) {
            List<TeamPortfolioConfigDto.BottomNavItem> items = bottomNav.getItems();
            if (items == null || items.size() < BOTTOM_NAV_MIN_ITEM_COUNT
                    || items.size() > BOTTOM_NAV_MAX_ITEM_COUNT) {
                throw new BusinessException(CONFIG_INVALID_MESSAGE);
            }
            Set<String> menuKeys = new LinkedHashSet<>();
            Set<String> menuTitles = new LinkedHashSet<>();
            for (int index = 0; index < items.size(); index++) {
                TeamPortfolioConfigDto.BottomNavItem item = items.get(index);
                if (item == null) {
                    log.warn(
                            "团队作品集规范化配置中的底部导航菜单项无效，teamId={}，portfolioId={}，menuIndex={}",
                            context.teamId(),
                            context.portfolioId(),
                            index
                    );
                    throw new BusinessException(CONFIG_INVALID_MESSAGE);
                }
                if (item.getKey() == null || item.getKey().isBlank()
                        || item.getTitle() == null || item.getTitle().isBlank()
                        || !menuKeys.add(item.getKey()) || !menuTitles.add(item.getTitle())
                        || (index == 0 && item.getComponents() != null)
                        || (index > 0 && item.getComponents() == null)) {
                    throw new BusinessException(CONFIG_INVALID_MESSAGE);
                }
            }
        }
        Set<String> componentKeys = new LinkedHashSet<>();
        for (TeamPortfolioComponentTraversal.ComponentLocation location
                : TeamPortfolioComponentTraversal.listComponentLocations(config)) {
            TeamPortfolioConfigDto.ComponentEnvelope component = location.component();
            if (component == null || component.getComponentKey() == null
                    || component.getComponentKey().isBlank()
                    || !componentKeys.add(component.getComponentKey())
                    || component.getComponentType() == null
                    || component.getComponentType().isBlank()
                    || component.getConfig() == null) {
                throw new BusinessException(CONFIG_INVALID_MESSAGE);
            }
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
        JSONObject componentData = renderComponentData(componentType, component.getConfig(), context);
        if (componentData == null) {
            return null;
        }
        TeamPortfolioRenderDto.Component rendered = new TeamPortfolioRenderDto.Component();
        rendered.setComponentKey(component.getComponentKey());
        rendered.setComponentType(componentType.getCode());
        rendered.setName(componentType.getDisplayName());
        rendered.setSortOrder(component.getSortOrder());
        rendered.setData(componentData);
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
            case VIDEO_CAROUSEL -> videoCarouselRenderer.render(config, context);
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
     * 空字符串兜底。
     */
    private String defaultString(String value) {
        return value == null ? "" : value;
    }

    /**
     * 获取安全排序值。
     */
    private int sortOrder(TeamPortfolioConfigDto.ComponentEnvelope component) {
        return component.getSortOrder() == null ? Integer.MAX_VALUE : component.getSortOrder();
    }

}
