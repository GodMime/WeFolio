package com.jxc.wefolio.service.teamportfolio;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;
import com.jxc.wefolio.constant.TeamPortfolioConstants;
import com.jxc.wefolio.dict.TeamPortfolioComponentTypeDict;
import com.jxc.wefolio.dto.teamportfolio.TeamPortfolioConfigDto;
import com.jxc.wefolio.exception.BusinessException;
import com.jxc.wefolio.service.teamportfolio.component.carousel.TeamCarouselComponentValidator;
import com.jxc.wefolio.service.teamportfolio.component.contactform.TeamContactFormComponentValidator;
import com.jxc.wefolio.service.teamportfolio.component.divider.TeamDividerComponentValidator;
import com.jxc.wefolio.service.teamportfolio.component.memberportfoliogrid.TeamMemberPortfolioGridComponentValidator;
import com.jxc.wefolio.service.teamportfolio.component.memberportfoliolist.TeamMemberPortfolioListComponentValidator;
import com.jxc.wefolio.service.teamportfolio.component.qrcontact.TeamQrContactComponentValidator;
import com.jxc.wefolio.service.teamportfolio.component.schedulequery.TeamScheduleQueryComponentValidator;
import com.jxc.wefolio.service.teamportfolio.component.teamprofile.TeamProfileComponentValidator;
import com.jxc.wefolio.service.teamportfolio.component.textsection.TeamTextSectionComponentValidator;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 团队作品集配置顶层校验与组件分发服务。
 */
@Service
public class TeamPortfolioConfigValidator {

    /** 默认组件排序间隔。 */
    private static final int DEFAULT_SORT_ORDER_STEP = 1000;

    /** 配置不能为空提示。 */
    private static final String CONFIG_REQUIRED_MESSAGE = "团队作品集配置不能为空";

    /** 配置格式错误提示。 */
    private static final String CONFIG_INVALID_MESSAGE = "团队作品集配置格式不正确";

    /** Schema 不支持提示。 */
    private static final String SCHEMA_UNSUPPORTED_MESSAGE = "团队作品集配置版本不支持";

    /** 组件不能为空提示。 */
    private static final String COMPONENT_REQUIRED_MESSAGE = "团队作品集至少需要一个启用组件";

    /** 组件键重复提示。 */
    private static final String COMPONENT_KEY_DUPLICATE_MESSAGE = "团队作品集组件标识不能重复";

    /** 组件类型不支持提示。 */
    private static final String COMPONENT_TYPE_UNSUPPORTED_MESSAGE = "团队作品集组件类型不支持";

    /** 团队资料数量超限提示。 */
    private static final String TEAM_PROFILE_LIMIT_MESSAGE = "团队作品集最多只能包含一个团队资料组件";

    /** 组件上下文非法提示。 */
    private static final String CONTEXT_INVALID_MESSAGE = "团队作品集组件上下文不正确";

    private final TeamProfileComponentValidator teamProfileValidator;
    private final TeamCarouselComponentValidator carouselValidator;
    private final TeamDividerComponentValidator dividerValidator;
    private final TeamMemberPortfolioGridComponentValidator gridValidator;
    private final TeamMemberPortfolioListComponentValidator listValidator;
    private final TeamTextSectionComponentValidator textValidator;
    private final TeamScheduleQueryComponentValidator scheduleValidator;
    private final TeamContactFormComponentValidator contactValidator;
    private final TeamQrContactComponentValidator qrValidator;

    /**
     * 创建团队作品集配置校验器。
     */
    public TeamPortfolioConfigValidator(
            TeamProfileComponentValidator teamProfileValidator,
            TeamCarouselComponentValidator carouselValidator,
            TeamDividerComponentValidator dividerValidator,
            TeamMemberPortfolioGridComponentValidator gridValidator,
            TeamMemberPortfolioListComponentValidator listValidator,
            TeamTextSectionComponentValidator textValidator,
            TeamScheduleQueryComponentValidator scheduleValidator,
            TeamContactFormComponentValidator contactValidator,
            TeamQrContactComponentValidator qrValidator
    ) {
        this.teamProfileValidator = teamProfileValidator;
        this.carouselValidator = carouselValidator;
        this.dividerValidator = dividerValidator;
        this.gridValidator = gridValidator;
        this.listValidator = listValidator;
        this.textValidator = textValidator;
        this.scheduleValidator = scheduleValidator;
        this.contactValidator = contactValidator;
        this.qrValidator = qrValidator;
    }

    /**
     * 解析、校验并规范化团队作品集顶层配置。
     *
     * @param json 原始配置 JSON
     * @param teamId 团队 ID
     * @param portfolioId 作品集 ID
     * @param revision 作品集修订号
     * @return 规范化配置
     */
    public TeamPortfolioConfigDto normalizeAndValidate(String json, long teamId, long portfolioId, int revision) {
        TeamPortfolioComponentContext context = new TeamPortfolioComponentContext(teamId, portfolioId, revision);
        validateContext(context);
        TeamPortfolioConfigDto config = parseConfig(json);
        validateSchema(config);

        List<TeamPortfolioConfigDto.ComponentEnvelope> allComponents = nonNullComponents(config.getComponents());
        validateComponentTypes(allComponents);
        validateComponentKeys(allComponents);
        validateTeamProfileCount(allComponents);
        List<TeamPortfolioConfigDto.ComponentEnvelope> components = sortedEnabledComponents(allComponents);
        if (components.isEmpty()) {
            throw new BusinessException(COMPONENT_REQUIRED_MESSAGE);
        }

        List<TeamPortfolioConfigDto.ComponentEnvelope> normalizedComponents = new ArrayList<>();
        for (int index = 0; index < components.size(); index++) {
            TeamPortfolioConfigDto.ComponentEnvelope component = components.get(index);
            TeamPortfolioComponentTypeDict componentType = componentType(component.getComponentType());
            TeamPortfolioConfigDto.ComponentEnvelope normalized = new TeamPortfolioConfigDto.ComponentEnvelope();
            normalized.setComponentKey(component.getComponentKey());
            normalized.setComponentType(componentType.getCode());
            normalized.setEnabled(true);
            normalized.setSortOrder((index + 1) * DEFAULT_SORT_ORDER_STEP);
            normalized.setConfig(normalizeComponent(componentType, component.getConfig(), context));
            normalizedComponents.add(normalized);
        }

        TeamPortfolioConfigDto normalized = new TeamPortfolioConfigDto();
        normalized.setSchemaVersion(TeamPortfolioConstants.SCHEMA_VERSION_STANDARD_TEAM_V1);
        normalized.setShare(copyShare(config.getShare()));
        normalized.setComponents(normalizedComponents);
        return normalized;
    }

    /**
     * 解析顶层配置 JSON。
     */
    private TeamPortfolioConfigDto parseConfig(String json) {
        if (json == null || json.isBlank()) {
            throw new BusinessException(CONFIG_REQUIRED_MESSAGE);
        }
        try {
            TeamPortfolioConfigDto config = JSON.parseObject(json, TeamPortfolioConfigDto.class);
            if (config == null) {
                throw new BusinessException(CONFIG_REQUIRED_MESSAGE);
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
     * 过滤空组件信封并保留输入顺序。
     */
    private List<TeamPortfolioConfigDto.ComponentEnvelope> nonNullComponents(
            List<TeamPortfolioConfigDto.ComponentEnvelope> components
    ) {
        if (components == null) {
            return List.of();
        }
        return components.stream().filter(component -> component != null).toList();
    }

    /**
     * 对启用组件仅按排序值进行稳定排序。
     */
    private List<TeamPortfolioConfigDto.ComponentEnvelope> sortedEnabledComponents(
            List<TeamPortfolioConfigDto.ComponentEnvelope> components
    ) {
        return components.stream()
                .filter(component -> !Boolean.FALSE.equals(component.getEnabled()))
                .sorted(Comparator.comparing(this::sortOrder))
                .toList();
    }

    /**
     * 校验所有组件信封均使用受支持类型。
     */
    private void validateComponentTypes(List<TeamPortfolioConfigDto.ComponentEnvelope> components) {
        components.forEach(component -> componentType(component.getComponentType()));
    }

    /**
     * 校验组件实例键唯一。
     */
    private void validateComponentKeys(List<TeamPortfolioConfigDto.ComponentEnvelope> components) {
        Set<String> componentKeys = new LinkedHashSet<>();
        for (TeamPortfolioConfigDto.ComponentEnvelope component : components) {
            String componentKey = safeString(component.getComponentKey());
            if (componentKey.isBlank() || !componentKeys.add(componentKey)) {
                throw new BusinessException(COMPONENT_KEY_DUPLICATE_MESSAGE);
            }
        }
    }

    /**
     * 校验团队资料组件数量。
     */
    private void validateTeamProfileCount(List<TeamPortfolioConfigDto.ComponentEnvelope> components) {
        long count = components.stream()
                .filter(component -> TeamPortfolioComponentTypeDict.TEAM_PROFILE.getCode().equals(component.getComponentType()))
                .count();
        if (count > 1) {
            throw new BusinessException(TEAM_PROFILE_LIMIT_MESSAGE);
        }
    }

    /**
     * 分发至对应组件校验器。
     */
    private JSONObject normalizeComponent(
            TeamPortfolioComponentTypeDict componentType,
            JSONObject componentConfig,
            TeamPortfolioComponentContext context
    ) {
        JSONObject config = componentConfig == null ? new JSONObject() : componentConfig;
        return switch (componentType) {
            case TEAM_PROFILE -> teamProfileValidator.normalizeAndValidate(config, context);
            case CAROUSEL -> carouselValidator.normalizeAndValidate(config, context);
            case DIVIDER -> dividerValidator.normalizeAndValidate(config, context);
            case MEMBER_PORTFOLIO_GRID -> gridValidator.normalizeAndValidate(config, context);
            case MEMBER_PORTFOLIO_LIST -> listValidator.normalizeAndValidate(config, context);
            case TEXT_SECTION -> textValidator.normalizeAndValidate(config, context);
            case SCHEDULE_QUERY -> scheduleValidator.normalizeAndValidate(config, context);
            case CONTACT_FORM -> contactValidator.normalizeAndValidate(config, context);
            case QR_CONTACT -> qrValidator.normalizeAndValidate(config, context);
        };
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
     * 复制分享信息，避免复用输入对象。
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
     * 获取安全排序值。
     */
    private int sortOrder(TeamPortfolioConfigDto.ComponentEnvelope component) {
        return component.getSortOrder() == null ? Integer.MAX_VALUE : component.getSortOrder();
    }

    /**
     * 获取安全字符串。
     */
    private String safeString(String value) {
        return value == null ? "" : value;
    }

    /**
     * 校验组件上下文。
     */
    private void validateContext(TeamPortfolioComponentContext context) {
        if (context.teamId() <= 0 || context.portfolioId() <= 0 || context.revision() < 0) {
            throw new BusinessException(CONTEXT_INVALID_MESSAGE);
        }
    }
}
