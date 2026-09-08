package com.jxc.wefolio.service.teamportfolio;

import com.alibaba.fastjson2.JSONObject;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.jxc.wefolio.constant.TeamPortfolioConstants;
import com.jxc.wefolio.dict.PortfolioConfigScopeDict;
import com.jxc.wefolio.dict.TeamPortfolioComponentTypeDict;
import com.jxc.wefolio.dto.teamportfolio.TeamPortfolioConfigDto;
import com.jxc.wefolio.entity.PortfolioReferenceEntity;
import com.jxc.wefolio.exception.BusinessException;
import com.jxc.wefolio.mapper.PortfolioReferenceEntityMapper;
import com.jxc.wefolio.service.teamportfolio.component.carousel.TeamCarouselComponentReferenceExtractor;
import com.jxc.wefolio.service.teamportfolio.component.contactform.TeamContactFormComponentReferenceExtractor;
import com.jxc.wefolio.service.teamportfolio.component.divider.TeamDividerComponentReferenceExtractor;
import com.jxc.wefolio.service.teamportfolio.component.memberportfoliogrid.TeamMemberPortfolioGridComponentReferenceExtractor;
import com.jxc.wefolio.service.teamportfolio.component.memberportfoliolist.TeamMemberPortfolioListComponentReferenceExtractor;
import com.jxc.wefolio.service.teamportfolio.component.qrcontact.TeamQrContactComponentReferenceExtractor;
import com.jxc.wefolio.service.teamportfolio.component.schedulequery.TeamScheduleQueryComponentReferenceExtractor;
import com.jxc.wefolio.service.teamportfolio.component.singlework.TeamSingleWorkComponentReferenceExtractor;
import com.jxc.wefolio.service.teamportfolio.component.teamprofile.TeamProfileComponentReferenceExtractor;
import com.jxc.wefolio.service.teamportfolio.component.textsection.TeamTextSectionComponentReferenceExtractor;
import com.jxc.wefolio.service.teamportfolio.component.structuredtextsection.TeamStructuredTextSectionComponentReferenceExtractor;
import com.jxc.wefolio.service.teamportfolio.component.videocarousel.TeamVideoCarouselComponentReferenceExtractor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * 团队作品集引用重建与组件提取分发服务。
 */
@Service
public class TeamPortfolioReferenceService {

    /** 配置作用域不支持提示。 */
    private static final String CONFIG_SCOPE_UNSUPPORTED_MESSAGE = "团队作品集引用作用域不支持";

    /** 组件类型不支持提示。 */
    private static final String COMPONENT_TYPE_UNSUPPORTED_MESSAGE = "团队作品集组件类型不支持";

    /** 作品集上下文不一致提示。 */
    private static final String CONTEXT_MISMATCH_MESSAGE = "团队作品集引用上下文不一致";

    /** 配置不能为空提示。 */
    private static final String CONFIG_REQUIRED_MESSAGE = "团队作品集配置不能为空";

    /** Schema 不支持提示。 */
    private static final String SCHEMA_UNSUPPORTED_MESSAGE = "团队作品集配置版本不支持";

    private final PortfolioReferenceEntityMapper referenceMapper;
    private final TeamProfileComponentReferenceExtractor teamProfileExtractor;
    private final TeamCarouselComponentReferenceExtractor carouselExtractor;
    private final TeamSingleWorkComponentReferenceExtractor singleWorkExtractor;
    private final TeamDividerComponentReferenceExtractor dividerExtractor;
    private final TeamMemberPortfolioGridComponentReferenceExtractor gridExtractor;
    private final TeamMemberPortfolioListComponentReferenceExtractor listExtractor;
    private final TeamTextSectionComponentReferenceExtractor textExtractor;
    /** 结构化文字组件策略。 */
    private final TeamStructuredTextSectionComponentReferenceExtractor structuredTextExtractor;
    private final TeamScheduleQueryComponentReferenceExtractor scheduleExtractor;
    private final TeamContactFormComponentReferenceExtractor contactExtractor;
    private final TeamQrContactComponentReferenceExtractor qrExtractor;
    private final TeamVideoCarouselComponentReferenceExtractor videoCarouselExtractor;

    /**
     * 创建团队作品集引用重建服务。
     */
    public TeamPortfolioReferenceService(
            PortfolioReferenceEntityMapper referenceMapper,
            TeamProfileComponentReferenceExtractor teamProfileExtractor,
            TeamCarouselComponentReferenceExtractor carouselExtractor,
            TeamSingleWorkComponentReferenceExtractor singleWorkExtractor,
            TeamDividerComponentReferenceExtractor dividerExtractor,
            TeamMemberPortfolioGridComponentReferenceExtractor gridExtractor,
            TeamMemberPortfolioListComponentReferenceExtractor listExtractor,
            TeamTextSectionComponentReferenceExtractor textExtractor,
            TeamScheduleQueryComponentReferenceExtractor scheduleExtractor,
            TeamContactFormComponentReferenceExtractor contactExtractor,
            TeamQrContactComponentReferenceExtractor qrExtractor,
            TeamVideoCarouselComponentReferenceExtractor videoCarouselExtractor,
            TeamStructuredTextSectionComponentReferenceExtractor structuredTextExtractor
    ) {
        this.referenceMapper = referenceMapper;
        this.teamProfileExtractor = teamProfileExtractor;
        this.carouselExtractor = carouselExtractor;
        this.singleWorkExtractor = singleWorkExtractor;
        this.dividerExtractor = dividerExtractor;
        this.gridExtractor = gridExtractor;
        this.listExtractor = listExtractor;
        this.textExtractor = textExtractor;
        this.scheduleExtractor = scheduleExtractor;
        this.contactExtractor = contactExtractor;
        this.qrExtractor = qrExtractor;
        this.videoCarouselExtractor = videoCarouselExtractor;
        this.structuredTextExtractor = structuredTextExtractor;
    }

    /**
     * 按指定配置作用域逻辑删除旧引用，并写入当前组件引用。
     *
     * @param portfolioId 作品集 ID
     * @param configScope 配置作用域
     * @param config 规范化配置
     * @param context 组件上下文
     */
    @Transactional(rollbackFor = Exception.class)
    public void rebuild(
            long portfolioId,
            String configScope,
            TeamPortfolioConfigDto config,
            TeamPortfolioComponentContext context
    ) {
        validateScope(configScope);
        validateContext(portfolioId, context);
        validateConfig(config);
        List<PortfolioReferenceEntity> references = extractReferences(config, context);
        referenceMapper.delete(Wrappers.lambdaQuery(PortfolioReferenceEntity.class)
                .eq(PortfolioReferenceEntity::getPortfolioId, portfolioId)
                .eq(PortfolioReferenceEntity::getConfigScope, configScope));

        for (PortfolioReferenceEntity reference : references) {
            reference.setPortfolioId(portfolioId);
            reference.setConfigScope(configScope);
            referenceMapper.insert(reference);
        }
    }

    /**
     * 在调用提取器或 Mapper 前校验顶层配置。
     */
    private void validateConfig(TeamPortfolioConfigDto config) {
        if (config == null) {
            throw new BusinessException(CONFIG_REQUIRED_MESSAGE);
        }
        if (!TeamPortfolioConstants.SCHEMA_VERSION_STANDARD_TEAM_V1.equals(config.getSchemaVersion())) {
            throw new BusinessException(SCHEMA_UNSUPPORTED_MESSAGE);
        }
        TeamPortfolioComponentTraversal.listComponentLocations(config).stream()
                .map(TeamPortfolioComponentTraversal.ComponentLocation::component)
                .filter(component -> component != null)
                .forEach(component -> componentType(component.getComponentType()));
    }

    /**
     * 提取所有已启用组件引用。
     */
    private List<PortfolioReferenceEntity> extractReferences(
            TeamPortfolioConfigDto config,
            TeamPortfolioComponentContext context
    ) {
        List<TeamPortfolioComponentTraversal.ComponentLocation> locations =
                sortedEnabledComponentLocations(config);
        List<PortfolioReferenceEntity> references = new ArrayList<>();
        for (TeamPortfolioComponentTraversal.ComponentLocation location : locations) {
            TeamPortfolioConfigDto.ComponentEnvelope component = location.component();
            TeamPortfolioComponentTypeDict componentType = componentType(component.getComponentType());
            references.addAll(extractComponent(componentType, component.getComponentKey(), location.componentPath(),
                    component.getConfig() == null ? new JSONObject() : component.getConfig(), context));
        }
        return references;
    }

    /**
     * 分发至对应组件引用提取器。
     */
    private List<PortfolioReferenceEntity> extractComponent(
            TeamPortfolioComponentTypeDict componentType,
            String componentKey,
            String componentPath,
            JSONObject normalizedConfig,
            TeamPortfolioComponentContext context
    ) {
        return switch (componentType) {
            case TEAM_PROFILE -> teamProfileExtractor.extract(componentKey, componentPath, normalizedConfig, context);
            case CAROUSEL -> carouselExtractor.extract(componentKey, componentPath, normalizedConfig, context);
            case SINGLE_WORK -> singleWorkExtractor.extract(componentKey, componentPath, normalizedConfig, context);
            case DIVIDER -> dividerExtractor.extract(componentKey, componentPath, normalizedConfig, context);
            case MEMBER_PORTFOLIO_GRID -> gridExtractor.extract(componentKey, componentPath, normalizedConfig, context);
            case MEMBER_PORTFOLIO_LIST -> listExtractor.extract(componentKey, componentPath, normalizedConfig, context);
            case TEXT_SECTION -> textExtractor.extract(componentKey, componentPath, normalizedConfig, context);
            case STRUCTURED_TEXT_SECTION -> structuredTextExtractor.extract(componentKey, componentPath, normalizedConfig, context);
            case SCHEDULE_QUERY -> scheduleExtractor.extract(componentKey, componentPath, normalizedConfig, context);
            case CONTACT_FORM -> contactExtractor.extract(componentKey, componentPath, normalizedConfig, context);
            case QR_CONTACT -> qrExtractor.extract(componentKey, componentPath, normalizedConfig, context);
            case VIDEO_CAROUSEL -> videoCarouselExtractor.extract(
                    componentKey, componentPath, normalizedConfig, context);
        };
    }

    /**
     * 校验配置作用域。
     */
    private void validateScope(String configScope) {
        if (!PortfolioConfigScopeDict.DRAFT.getCode().equals(configScope)
                && !PortfolioConfigScopeDict.PUBLISHED.getCode().equals(configScope)) {
            throw new BusinessException(CONFIG_SCOPE_UNSUPPORTED_MESSAGE);
        }
    }

    /**
     * 校验调用上下文与目标作品集一致。
     */
    private void validateContext(long portfolioId, TeamPortfolioComponentContext context) {
        if (portfolioId <= 0 || context == null || context.teamId() <= 0
                || context.portfolioId() != portfolioId || context.revision() < 0) {
            throw new BusinessException(CONTEXT_MISMATCH_MESSAGE);
        }
    }

    /**
     * 获取受支持组件类型。
     */
    private TeamPortfolioComponentTypeDict componentType(String typeCode) {
        TeamPortfolioComponentTypeDict componentType = TeamPortfolioComponentTypeDict.fromCode(typeCode);
        if (componentType == null) {
            throw new BusinessException(COMPONENT_TYPE_UNSUPPORTED_MESSAGE);
        }
        return componentType;
    }

    /**
     * 对启用组件进行稳定排序。
     */
    private List<TeamPortfolioComponentTraversal.ComponentLocation> sortedEnabledComponentLocations(
            TeamPortfolioConfigDto config
    ) {
        return TeamPortfolioComponentTraversal.listComponentLocations(config).stream()
                .filter(location -> location.component() != null
                        && !Boolean.FALSE.equals(location.component().getEnabled()))
                .sorted(Comparator
                        .comparingInt(TeamPortfolioComponentTraversal.ComponentLocation::menuIndex)
                        .thenComparing(location -> sortOrder(location.component())))
                .toList();
    }

    /**
     * 获取安全排序值。
     */
    private int sortOrder(TeamPortfolioConfigDto.ComponentEnvelope component) {
        return component.getSortOrder() == null ? Integer.MAX_VALUE : component.getSortOrder();
    }

}
