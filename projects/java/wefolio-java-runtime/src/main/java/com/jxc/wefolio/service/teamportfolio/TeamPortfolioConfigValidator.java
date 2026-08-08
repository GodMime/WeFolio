package com.jxc.wefolio.service.teamportfolio;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;
import com.jxc.wefolio.constant.TeamPortfolioConstants;
import com.jxc.wefolio.dict.TeamPortfolioComponentTypeDict;
import com.jxc.wefolio.dto.teamportfolio.TeamPortfolioConfigDto;
import com.jxc.wefolio.exception.BusinessException;
import com.jxc.wefolio.message.TeamPortfolioMessage;
import com.jxc.wefolio.service.teamportfolio.component.carousel.TeamCarouselComponentValidator;
import com.jxc.wefolio.service.teamportfolio.component.contactform.TeamContactFormComponentValidator;
import com.jxc.wefolio.service.teamportfolio.component.divider.TeamDividerComponentValidator;
import com.jxc.wefolio.service.teamportfolio.component.memberportfoliogrid.TeamMemberPortfolioGridComponentValidator;
import com.jxc.wefolio.service.teamportfolio.component.memberportfoliolist.TeamMemberPortfolioListComponentValidator;
import com.jxc.wefolio.service.teamportfolio.component.qrcontact.TeamQrContactComponentValidator;
import com.jxc.wefolio.service.teamportfolio.component.schedulequery.TeamScheduleQueryComponentValidator;
import com.jxc.wefolio.service.teamportfolio.component.singlework.TeamSingleWorkComponentValidator;
import com.jxc.wefolio.service.teamportfolio.component.teamprofile.TeamProfileComponentValidator;
import com.jxc.wefolio.service.teamportfolio.component.textsection.TeamTextSectionComponentValidator;
import com.jxc.wefolio.service.teamportfolio.component.videocarousel.TeamVideoCarouselComponentValidator;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * 团队作品集配置顶层校验与组件分发服务。
 */
@Service
public class TeamPortfolioConfigValidator {

    /** 默认组件排序间隔。 */
    private static final int DEFAULT_SORT_ORDER_STEP = 1000;

    /** 底部导航最少菜单数。 */
    private static final int BOTTOM_NAV_MIN_ITEM_COUNT = 2;

    /** 底部导航最多菜单数。 */
    private static final int BOTTOM_NAV_MAX_ITEM_COUNT = 4;

    /** 底部导航菜单名称最大 Unicode 字符数。 */
    private static final int BOTTOM_NAV_TITLE_MAX_CODE_POINTS = 5;

    /** 底部导航菜单标识格式。 */
    private static final Pattern BOTTOM_NAV_KEY_PATTERN = Pattern.compile("^nav_[A-Za-z0-9_-]{1,64}$");

    /** 配置不能为空提示。 */
    private static final String CONFIG_REQUIRED_MESSAGE = "团队作品集配置不能为空";

    /** 配置格式错误提示。 */
    private static final String CONFIG_INVALID_MESSAGE = "团队作品集配置格式不正确";

    /** Schema 不支持提示。 */
    private static final String SCHEMA_UNSUPPORTED_MESSAGE = "团队作品集配置版本不支持";

    /** 组件不能为空提示。 */
    private static final String COMPONENT_REQUIRED_MESSAGE = "团队作品集至少需要一个启用组件";

    /** 组件类型不支持提示。 */
    private static final String COMPONENT_TYPE_UNSUPPORTED_MESSAGE = "团队作品集组件类型不支持";

    /** 组件上下文非法提示。 */
    private static final String CONTEXT_INVALID_MESSAGE = "团队作品集组件上下文不正确";

    private final TeamProfileComponentValidator teamProfileValidator;
    private final TeamCarouselComponentValidator carouselValidator;
    private final TeamSingleWorkComponentValidator singleWorkValidator;
    private final TeamDividerComponentValidator dividerValidator;
    private final TeamMemberPortfolioGridComponentValidator gridValidator;
    private final TeamMemberPortfolioListComponentValidator listValidator;
    private final TeamTextSectionComponentValidator textValidator;
    private final TeamScheduleQueryComponentValidator scheduleValidator;
    private final TeamContactFormComponentValidator contactValidator;
    private final TeamQrContactComponentValidator qrValidator;
    private final TeamVideoCarouselComponentValidator videoCarouselValidator;

    /**
     * 创建团队作品集配置校验器。
     */
    public TeamPortfolioConfigValidator(
            TeamProfileComponentValidator teamProfileValidator,
            TeamCarouselComponentValidator carouselValidator,
            TeamSingleWorkComponentValidator singleWorkValidator,
            TeamDividerComponentValidator dividerValidator,
            TeamMemberPortfolioGridComponentValidator gridValidator,
            TeamMemberPortfolioListComponentValidator listValidator,
            TeamTextSectionComponentValidator textValidator,
            TeamScheduleQueryComponentValidator scheduleValidator,
            TeamContactFormComponentValidator contactValidator,
            TeamQrContactComponentValidator qrValidator,
            TeamVideoCarouselComponentValidator videoCarouselValidator
    ) {
        this.teamProfileValidator = teamProfileValidator;
        this.carouselValidator = carouselValidator;
        this.singleWorkValidator = singleWorkValidator;
        this.dividerValidator = dividerValidator;
        this.gridValidator = gridValidator;
        this.listValidator = listValidator;
        this.textValidator = textValidator;
        this.scheduleValidator = scheduleValidator;
        this.contactValidator = contactValidator;
        this.qrValidator = qrValidator;
        this.videoCarouselValidator = videoCarouselValidator;
    }

    /**
     * 解析、校验并规范化旧版团队作品集顶层配置。
     *
     * @param json 原始配置 JSON
     * @param teamId 团队 ID
     * @param portfolioId 作品集 ID
     * @param revision 作品集修订号
     * @return 规范化配置
     * @deprecated 仅为兼容已有调用保留；新代码应按保存或发布场景调用
     * {@link #normalizeForDraft(TeamPortfolioConfigDto, TeamPortfolioConfigDto, TeamPortfolioComponentContext)}
     * 或 {@link #validateForPublish(TeamPortfolioConfigDto, TeamPortfolioComponentContext)}
     */
    @Deprecated
    public TeamPortfolioConfigDto normalizeAndValidate(
            String json,
            long teamId,
            long portfolioId,
            int revision
    ) {
        TeamPortfolioComponentContext context = new TeamPortfolioComponentContext(teamId, portfolioId, revision);
        return normalizeForDraft(parseConfig(json), null, context);
    }

    /**
     * 按草稿规则兼容合并、校验并规范化配置。
     *
     * @param incomingConfig 本次请求配置
     * @param existingDraftConfig 服务端当前草稿配置
     * @param context 团队组件上下文
     * @return 完整规范化配置
     */
    public TeamPortfolioConfigDto normalizeForDraft(
            TeamPortfolioConfigDto incomingConfig,
            TeamPortfolioConfigDto existingDraftConfig,
            TeamPortfolioComponentContext context
    ) {
        validateContext(context);
        if (incomingConfig == null) {
            throw new BusinessException(CONFIG_REQUIRED_MESSAGE);
        }
        validateEditorSchemaRevision(incomingConfig.getEditorSchemaRevision());
        if (existingDraftConfig != null) {
            validateEditorSchemaRevision(existingDraftConfig.getEditorSchemaRevision());
        }

        TeamPortfolioConfigDto config = mergeCompatibleConfig(incomingConfig, existingDraftConfig);
        validateEditorSchemaRevision(config.getEditorSchemaRevision());
        validateSchema(config);
        TeamPortfolioConfigDto normalized = new TeamPortfolioConfigDto();
        normalized.setSchemaVersion(TeamPortfolioConstants.SCHEMA_VERSION_STANDARD_TEAM_V1);
        normalized.setEditorSchemaRevision(config.getEditorSchemaRevision());
        normalized.setShare(normalizeShare(config.getShare()));
        normalized.setStyle(normalizeStyle(config.getStyle()));
        normalized.setBottomNav(normalizeBottomNavMetadata(config.getBottomNav()));

        List<TeamPortfolioConfigDto.ComponentEnvelope> allComponents = allSourceComponents(config);
        validateComponentTypes(allComponents);
        validateComponentKeys(allComponents);
        validateTeamProfileCount(allComponents);

        boolean navigationEnabled = Boolean.TRUE.equals(normalized.getBottomNav().getEnabled());
        String firstMenuTitle = navigationEnabled
                ? normalized.getBottomNav().getItems().getFirst().getTitle()
                : "";
        List<TeamPortfolioConfigDto.ComponentEnvelope> topLevel = normalizeComponentList(
                config.getComponents(), context, firstMenuTitle, navigationEnabled);
        if (topLevel.isEmpty()) {
            throw new BusinessException(navigationEnabled
                    ? TeamPortfolioMessage.MENU_COMPONENT_REQUIRED_TEMPLATE.formatted(firstMenuTitle)
                    : COMPONENT_REQUIRED_MESSAGE);
        }
        normalized.setComponents(topLevel);

        if (navigationEnabled) {
            List<TeamPortfolioConfigDto.BottomNavItem> sourceItems = config.getBottomNav().getItems();
            List<TeamPortfolioConfigDto.BottomNavItem> normalizedItems = normalized.getBottomNav().getItems();
            for (int menuIndex = 1; menuIndex < normalizedItems.size(); menuIndex++) {
                TeamPortfolioConfigDto.BottomNavItem sourceItem = sourceItems.get(menuIndex);
                TeamPortfolioConfigDto.BottomNavItem normalizedItem = normalizedItems.get(menuIndex);
                normalizedItem.setComponents(normalizeComponentList(
                        sourceItem == null ? null : sourceItem.getComponents(),
                        context,
                        normalizedItem.getTitle(),
                        true
                ));
            }
        }
        return normalized;
    }

    /**
     * 按发布规则重新校验完整草稿。
     * 即使输入已经过保存阶段规范化，发布前仍执行完整规范化与组件校验，
     * 用于阻止持久化配置损坏或保存后资源状态变化产生的无效正式配置。
     *
     * @param normalizedDraftConfig 已规范化草稿配置
     * @param context 团队组件上下文
     * @return 重新校验后的独立正式配置
     */
    public TeamPortfolioConfigDto validateForPublish(
            TeamPortfolioConfigDto normalizedDraftConfig,
            TeamPortfolioComponentContext context
    ) {
        TeamPortfolioConfigDto validated = normalizeForDraft(normalizedDraftConfig, null, context);
        if (!Boolean.TRUE.equals(validated.getBottomNav().getEnabled())) {
            return validated;
        }
        List<TeamPortfolioConfigDto.BottomNavItem> items = validated.getBottomNav().getItems();
        for (int menuIndex = 1; menuIndex < items.size(); menuIndex++) {
            TeamPortfolioConfigDto.BottomNavItem item = items.get(menuIndex);
            if (item.getComponents() == null || item.getComponents().isEmpty()) {
                throw new BusinessException(
                        TeamPortfolioMessage.MENU_COMPONENT_REQUIRED_TEMPLATE.formatted(item.getTitle()));
            }
        }
        return validated;
    }

    /**
     * 兼容合并新旧编辑器请求，委托给独立合并器。
     *
     * @see TeamPortfolioConfigMerger
     */
    private TeamPortfolioConfigDto mergeCompatibleConfig(
            TeamPortfolioConfigDto incomingConfig,
            TeamPortfolioConfigDto existingDraftConfig
    ) {
        return TeamPortfolioConfigMerger.merge(incomingConfig, existingDraftConfig);
    }

    /**
     * 规范化页面样式，委托给共享的样式规范化工具。
     *
     * @see TeamPortfolioStyleNormalizer
     */
    private TeamPortfolioConfigDto.Style normalizeStyle(TeamPortfolioConfigDto.Style style) {
        TeamPortfolioConfigDto.Style normalized = new TeamPortfolioConfigDto.Style();
        normalized.setBackgroundColor(TeamPortfolioStyleNormalizer.normalizeBackgroundColor(
                style == null ? null : style.getBackgroundColor()));
        return normalized;
    }

    /**
     * 校验并复制底部导航元数据。
     */
    private TeamPortfolioConfigDto.BottomNav normalizeBottomNavMetadata(
            TeamPortfolioConfigDto.BottomNav bottomNav
    ) {
        TeamPortfolioConfigDto.BottomNav normalized = new TeamPortfolioConfigDto.BottomNav();
        boolean enabled = bottomNav != null && Boolean.TRUE.equals(bottomNav.getEnabled());
        normalized.setEnabled(enabled);
        if (!enabled) {
            normalized.setItems(null);
            return normalized;
        }
        List<TeamPortfolioConfigDto.BottomNavItem> items = bottomNav.getItems() == null
                ? List.of()
                : bottomNav.getItems();
        if (items.size() < BOTTOM_NAV_MIN_ITEM_COUNT || items.size() > BOTTOM_NAV_MAX_ITEM_COUNT) {
            throw new BusinessException(TeamPortfolioMessage.BOTTOM_NAV_ITEM_COUNT_INVALID_MESSAGE);
        }
        TeamPortfolioConfigDto.BottomNavItem firstItem = items.getFirst();
        if (firstItem != null && firstItem.getComponents() != null) {
            throw new BusinessException(TeamPortfolioMessage.FIRST_BOTTOM_NAV_COMPONENTS_DUPLICATE_MESSAGE);
        }

        Set<String> keys = new LinkedHashSet<>();
        Set<String> titles = new LinkedHashSet<>();
        List<TeamPortfolioConfigDto.BottomNavItem> normalizedItems = new ArrayList<>();
        for (int menuIndex = 0; menuIndex < items.size(); menuIndex++) {
            TeamPortfolioConfigDto.BottomNavItem item = items.get(menuIndex);
            String key = item == null ? "" : safeString(item.getKey());
            if (!BOTTOM_NAV_KEY_PATTERN.matcher(key).matches()) {
                throw new BusinessException(TeamPortfolioMessage.BOTTOM_NAV_KEY_INVALID_MESSAGE);
            }
            if (!keys.add(key)) {
                throw new BusinessException(TeamPortfolioMessage.BOTTOM_NAV_KEY_DUPLICATE_MESSAGE);
            }
            String title = item == null ? "" : safeString(item.getTitle()).trim();
            int titleLength = title.codePointCount(0, title.length());
            if (titleLength < 1) {
                throw new BusinessException(TeamPortfolioMessage.BOTTOM_NAV_TITLE_REQUIRED_MESSAGE);
            }
            if (titleLength > BOTTOM_NAV_TITLE_MAX_CODE_POINTS) {
                throw new BusinessException(TeamPortfolioMessage.BOTTOM_NAV_TITLE_TOO_LONG_MESSAGE);
            }
            if (!titles.add(title)) {
                throw new BusinessException(TeamPortfolioMessage.BOTTOM_NAV_TITLE_DUPLICATE_MESSAGE);
            }
            TeamPortfolioConfigDto.BottomNavItem copy = new TeamPortfolioConfigDto.BottomNavItem();
            copy.setKey(key);
            copy.setTitle(title);
            copy.setIconUrl(item == null ? null : item.getIconUrl());
            copy.setComponents(menuIndex == 0 ? null : new ArrayList<>());
            normalizedItems.add(copy);
        }
        normalized.setItems(normalizedItems);
        return normalized;
    }

    /**
     * 返回完整配置中的全部原始组件。
     */
    private List<TeamPortfolioConfigDto.ComponentEnvelope> allSourceComponents(
            TeamPortfolioConfigDto config
    ) {
        List<TeamPortfolioConfigDto.ComponentEnvelope> components = new ArrayList<>();
        for (TeamPortfolioComponentTraversal.ComponentList componentList
                : TeamPortfolioComponentTraversal.listComponentLists(config)) {
            components.addAll(nonNullComponents(componentList.components()));
        }
        return components;
    }

    /**
     * 规范化单个菜单中的启用组件。
     */
    private List<TeamPortfolioConfigDto.ComponentEnvelope> normalizeComponentList(
            List<TeamPortfolioConfigDto.ComponentEnvelope> source,
            TeamPortfolioComponentContext context,
            String menuTitle,
            boolean menuAware
    ) {
        List<TeamPortfolioConfigDto.ComponentEnvelope> components =
                sortedEnabledComponents(nonNullComponents(source));
        List<TeamPortfolioConfigDto.ComponentEnvelope> normalizedComponents = new ArrayList<>();
        for (int index = 0; index < components.size(); index++) {
            TeamPortfolioConfigDto.ComponentEnvelope component = components.get(index);
            TeamPortfolioComponentTypeDict componentType = componentType(component.getComponentType());
            TeamPortfolioConfigDto.ComponentEnvelope normalized = new TeamPortfolioConfigDto.ComponentEnvelope();
            normalized.setComponentKey(component.getComponentKey());
            normalized.setComponentType(componentType.getCode());
            normalized.setEnabled(true);
            normalized.setSortOrder((index + 1) * DEFAULT_SORT_ORDER_STEP);
            try {
                normalized.setConfig(normalizeComponent(componentType, component.getConfig(), context));
            } catch (BusinessException exception) {
                if (!menuAware) {
                    throw exception;
                }
                throw new BusinessException("【" + menuTitle + "】" + exception.getMessage());
            }
            normalizedComponents.add(normalized);
        }
        return normalizedComponents;
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
                throw new BusinessException(TeamPortfolioMessage.COMPONENT_KEY_CROSS_MENU_DUPLICATE_MESSAGE);
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
            throw new BusinessException(TeamPortfolioMessage.TEAM_PROFILE_COMPONENT_LIMIT_MESSAGE);
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
            case SINGLE_WORK -> singleWorkValidator.normalizeAndValidate(config, context);
            case DIVIDER -> dividerValidator.normalizeAndValidate(config, context);
            case MEMBER_PORTFOLIO_GRID -> gridValidator.normalizeAndValidate(config, context);
            case MEMBER_PORTFOLIO_LIST -> listValidator.normalizeAndValidate(config, context);
            case TEXT_SECTION -> textValidator.normalizeAndValidate(config, context);
            case SCHEDULE_QUERY -> scheduleValidator.normalizeAndValidate(config, context);
            case CONTACT_FORM -> contactValidator.normalizeAndValidate(config, context);
            case QR_CONTACT -> qrValidator.normalizeAndValidate(config, context);
            case VIDEO_CAROUSEL -> videoCarouselValidator.normalizeAndValidate(config, context);
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
     * 校验并复制分享信息，避免复用输入对象。
     */
    private TeamPortfolioConfigDto.Share normalizeShare(TeamPortfolioConfigDto.Share source) {
        if (source == null || source.getTitle() == null || source.getTitle().isBlank()) {
            throw new BusinessException(TeamPortfolioMessage.TITLE_REQUIRED);
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
     * 拒绝当前服务无法完整理解的未来编辑器配置，避免降级保存时丢失字段。
     */
    private void validateEditorSchemaRevision(Integer editorSchemaRevision) {
        if (editorSchemaRevision != null
                && editorSchemaRevision > TeamPortfolioConfigDto.EDITOR_SCHEMA_REVISION_CURRENT) {
            throw new BusinessException(TeamPortfolioMessage.EDITOR_SCHEMA_REVISION_UNSUPPORTED_MESSAGE);
        }
    }

    /**
     * 校验组件上下文。
     */
    private void validateContext(TeamPortfolioComponentContext context) {
        if (context == null || context.teamId() <= 0 || context.portfolioId() <= 0 || context.revision() < 0) {
            throw new BusinessException(CONTEXT_INVALID_MESSAGE);
        }
    }
}
