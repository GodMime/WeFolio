package com.jxc.wefolio.service;

import com.jxc.wefolio.common.PortfolioTextTypographySupport;
import com.jxc.wefolio.constant.PortfolioTextTypographyConstants;
import com.jxc.wefolio.dict.MediaTypeDict;
import com.jxc.wefolio.dict.PortfolioComponentTypeDict;
import com.jxc.wefolio.dict.PortfolioTextFontFamilyDict;
import com.jxc.wefolio.dict.ReferenceTypeDict;
import com.jxc.wefolio.dict.WorkAuditStatusDict;
import com.jxc.wefolio.dict.WorkStatusDict;
import com.jxc.wefolio.dto.PortfolioConfigDto;
import com.jxc.wefolio.entity.PortfolioReferenceEntity;
import com.jxc.wefolio.entity.WorkEntity;
import com.jxc.wefolio.exception.BusinessException;
import com.jxc.wefolio.mapper.UserEntityMapper;
import com.jxc.wefolio.mapper.WorkEntityMapper;
import com.jxc.wefolio.message.PortfolioMessage;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.BiConsumer;
import java.util.regex.Pattern;

/**
 * 作品集配置校验器 — 负责标准个人作品集组件规则和引用构建。
 */
@Service
@RequiredArgsConstructor
public class PortfolioConfigValidator {

    /** 单作品组件允许的媒体类型 */
    private static final Set<String> SINGLE_WORK_MEDIA_TYPES = Set.of(
            MediaTypeDict.IMAGE.getCode(),
            MediaTypeDict.VIDEO.getCode(),
            MediaTypeDict.ANIMATION.getCode());

    /** 批量作品组件允许的媒体类型 */
    private static final Set<String> BULK_WORK_MEDIA_TYPES = Set.of(
            MediaTypeDict.IMAGE.getCode(),
            MediaTypeDict.VIDEO.getCode());

    /** 轮播图最大作品数量 */
    private static final int CAROUSEL_WORK_MAX_COUNT = 9;

    /** 双列作品列表最大作品数量 */
    private static final int WORK_GRID_MAX_COUNT = 50;

    /** 单列作品列表最大作品数量 */
    private static final int WORK_LIST_MAX_COUNT = 50;

    /** 默认排序间隔 */
    private static final int DEFAULT_SORT_ORDER_STEP = 1000;

    /** 底部导航最少菜单数 */
    private static final int BOTTOM_NAV_MIN_ITEM_COUNT = 2;

    /** 底部导航最多菜单数 */
    private static final int BOTTOM_NAV_MAX_ITEM_COUNT = 4;

    /** 底部导航菜单名称最大 Unicode 字符数 */
    private static final int BOTTOM_NAV_TITLE_MAX_CODE_POINTS = 5;

    /** 页面背景色格式 */
    private static final Pattern BACKGROUND_COLOR_PATTERN = Pattern.compile("^#[0-9A-Fa-f]{6}$");

    /** 底部导航菜单标识格式 */
    private static final Pattern BOTTOM_NAV_KEY_PATTERN = Pattern.compile("^nav_[A-Za-z0-9_-]{1,64}$");

    /** 联系人字段 */
    private static final String CONTACT_FIELD_NAME = "contactName";

    /** 手机号字段 */
    private static final String CONTACT_FIELD_PHONE = "phone";

    /** 微信号字段 */
    private static final String CONTACT_FIELD_WECHAT = "wechat";

    /** 需求描述字段 */
    private static final String CONTACT_FIELD_NEEDS = "needs";

    /** 默认访客联系表单字段 */
    private static final List<String> DEFAULT_CONTACT_FORM_FIELDS = List.of(
            CONTACT_FIELD_NAME,
            CONTACT_FIELD_PHONE,
            CONTACT_FIELD_WECHAT,
            CONTACT_FIELD_NEEDS
    );

    /** 作品 ID 配置键 */
    private static final String CONFIG_KEY_WORK_IDS = "workIds";

    /** 单个作品 ID 配置键 */
    private static final String CONFIG_KEY_WORK_ID = "workId";

    /** 是否展示作品名配置键 */
    private static final String CONFIG_KEY_SHOW_TITLE = "showTitle";

    /** 是否展示作品说明配置键 */
    private static final String CONFIG_KEY_SHOW_DESCRIPTION = "showDescription";

    /** 作品集展示标签配置键 */
    private static final String CONFIG_KEY_GROUPS = "groups";

    /** 展示列数配置键 */
    private static final String CONFIG_KEY_COLUMNS = "columns";

    /** 作品集展示标签标识配置键 */
    private static final String CONFIG_KEY_GROUP_KEY = "groupKey";

    /** 作品集展示标签名称配置键 */
    private static final String CONFIG_KEY_GROUP_NAME = "name";

    /** 排序配置键 */
    private static final String CONFIG_KEY_SORT_ORDER = "sortOrder";

    /** 二维码来源配置键 */
    private static final String CONFIG_KEY_QR_URL_SOURCE = "qrUrlSource";

    /** 二维码自定义地址配置键 */
    private static final String CONFIG_KEY_QR_URL = "qrUrl";

    /** 表单字段配置键 */
    private static final String CONFIG_KEY_FIELDS = "fields";

    /** 组件标题配置键 */
    private static final String CONFIG_KEY_TITLE = "title";

    /** 组件内容配置键 */
    private static final String CONFIG_KEY_CONTENT = "content";

    /** 文字说明对齐配置键 */
    private static final String CONFIG_KEY_ALIGNMENT = "alignment";

    /** 分割线颜色配置键 */
    private static final String CONFIG_KEY_DIVIDER_COLOR = "color";

    /** 分割线高度配置键 */
    private static final String CONFIG_KEY_DIVIDER_HEIGHT_PX = "heightPx";

    /** 档期查询范围配置键 */
    private static final String CONFIG_KEY_QUERY_RANGE = "queryRange";

    /** 档期查询展示方式配置键 */
    private static final String CONFIG_KEY_DISPLAY_MODE = "displayMode";

    /** 档期查询范围类型配置键 */
    private static final String CONFIG_KEY_RANGE_TYPE = "type";

    /** 档期未来天数配置键 */
    private static final String CONFIG_KEY_FUTURE_DAYS = "futureDays";

    /** 档期开始日期配置键 */
    private static final String CONFIG_KEY_START_DATE = "startDate";

    /** 档期结束日期配置键 */
    private static final String CONFIG_KEY_END_DATE = "endDate";

    /** 使用资料二维码 */
    private static final String QR_SOURCE_PROFILE = "PROFILE";

    /** 使用自定义二维码 */
    private static final String QR_SOURCE_CUSTOM = "CUSTOM";

    /** 不限制档期查询范围 */
    private static final String QUERY_RANGE_UNLIMITED = "UNLIMITED";

    /** 限制未来天数档期查询 */
    private static final String QUERY_RANGE_FUTURE_DAYS = "FUTURE_DAYS";

    /** 限制固定日期范围档期查询 */
    private static final String QUERY_RANGE_DATE_RANGE = "DATE_RANGE";

    /** 弹层月历展示方式 */
    private static final String SCHEDULE_DISPLAY_MODE_MODAL_CALENDAR = "MODAL_CALENDAR";

    /** 内联月历展示方式 */
    private static final String SCHEDULE_DISPLAY_MODE_INLINE_CALENDAR = "INLINE_CALENDAR";

    /** 联系表单弹层展示方式 */
    private static final String CONTACT_FORM_DISPLAY_MODE_MODAL_FORM = "MODAL_FORM";

    /** 联系表单直接展示方式 */
    private static final String CONTACT_FORM_DISPLAY_MODE_INLINE_FORM = "INLINE_FORM";

    /** 文字说明最大字数 */
    private static final int TEXT_SECTION_CONTENT_MAX_LENGTH = 200;

    /** 文字说明左对齐 */
    private static final String TEXT_SECTION_ALIGNMENT_LEFT = "LEFT";

    /** 文字说明居中 */
    private static final String TEXT_SECTION_ALIGNMENT_CENTER = "CENTER";

    /** 文字说明右对齐 */
    private static final String TEXT_SECTION_ALIGNMENT_RIGHT = "RIGHT";

    /** 支持的文字说明对齐方式 */
    private static final Set<String> TEXT_SECTION_ALIGNMENTS = Set.of(
            TEXT_SECTION_ALIGNMENT_LEFT,
            TEXT_SECTION_ALIGNMENT_CENTER,
            TEXT_SECTION_ALIGNMENT_RIGHT
    );

    /** 分割线黑色 */
    private static final String DIVIDER_COLOR_BLACK = "BLACK";

    /** 分割线白色 */
    private static final String DIVIDER_COLOR_WHITE = "WHITE";

    /** 分割线灰色 */
    private static final String DIVIDER_COLOR_GRAY = "GRAY";

    /** 分割线透明 */
    private static final String DIVIDER_COLOR_TRANSPARENT = "TRANSPARENT";

    /** 支持的分割线颜色 */
    private static final Set<String> DIVIDER_COLORS = Set.of(
            DIVIDER_COLOR_BLACK,
            DIVIDER_COLOR_WHITE,
            DIVIDER_COLOR_GRAY,
            DIVIDER_COLOR_TRANSPARENT
    );

    /** 默认分割线高度 */
    private static final int DEFAULT_DIVIDER_HEIGHT_PX = 16;

    /** 默认作品集展示标签标识前缀 */
    private static final String DEFAULT_GROUP_KEY_PREFIX = "g_";

    /** 旧作品列表迁移默认展示标签 */
    private static final String DEFAULT_WORK_GROUP_NAME = "全部作品";

    /**
     * 编辑器版本引入的配置字段合并步骤，按 revision 升序注册。
     * <p>
     * 新增 editorSchemaRevision 时只需在本列表末尾追加对应步骤，
     * 无需修改 mergeCompatibleConfig 的流程控制。
     */
    private static final List<ConfigFieldMergeStep> CONFIG_FIELD_MERGE_STEPS = List.of(
            new ConfigFieldMergeStep(2, (target, source) -> {
                target.setStyle(source.getStyle());
                target.setBottomNav(source.getBottomNav());
            })
    );

    /**
     * 配置字段合并步骤 — 描述单个 editorSchemaRevision 引入的新字段及其拷贝方式。
     *
     * @param introducedAtRevision 该字段在哪个 editorSchemaRevision 首次引入
     * @param mergeFields          从 source 拷贝字段到 target 的策略
     */
    private record ConfigFieldMergeStep(
            int introducedAtRevision,
            BiConsumer<PortfolioConfigDto, PortfolioConfigDto> mergeFields
    ) {
    }

    /**
     * 组件校验上下文 — 控制错误前缀和菜单级校验规则。
     */
    private record ComponentValidationContext(String menuTitle, boolean menuAware) {

        /** 旧配置无导航时的默认上下文，不使用菜单错误语义。 */
        static final ComponentValidationContext LEGACY = new ComponentValidationContext("", false);

        /** 构造 BusinessException，菜单模式下自动附加【菜单名】前缀。 */
        BusinessException toException(String message) {
            if (!menuAware) {
                return new BusinessException(message);
            }
            return new BusinessException(
                    String.format(PortfolioMessage.MENU_ERROR_PREFIX_TEMPLATE, menuTitle, message));
        }
    }

    /** 作品 Mapper */
    private final WorkEntityMapper workEntityMapper;

    /** 用户 Mapper */
    @SuppressWarnings("unused")
    private final UserEntityMapper userEntityMapper;

    /**
     * 校验并规范化配置。
     *
     * @param userId 当前用户 ID
     * @param config 原始配置
     * @return 规范化配置
     */
    public PortfolioConfigDto normalize(Long userId, PortfolioConfigDto config) {
        return normalizeForDraft(userId, config, null);
    }

    /**
     * 按草稿规则兼容合并、校验并规范化配置。
     *
     * @param userId 当前用户 ID
     * @param incomingConfig 本次请求配置
     * @param existingDraftConfig 服务端当前草稿配置
     * @return 完整规范化配置
     */
    public PortfolioConfigDto normalizeForDraft(
            Long userId,
            PortfolioConfigDto incomingConfig,
            PortfolioConfigDto existingDraftConfig
    ) {
        if (userId == null) {
            throw new BusinessException(PortfolioMessage.USER_REQUIRED_MESSAGE);
        }
        if (incomingConfig == null) {
            throw new BusinessException(PortfolioMessage.PORTFOLIO_CONFIG_REQUIRED_MESSAGE);
        }
        Integer incomingRevision = incomingConfig.getEditorSchemaRevision();
        if (incomingRevision != null && incomingRevision > PortfolioConfigDto.EDITOR_SCHEMA_REVISION_CURRENT) {
            throw new BusinessException(PortfolioMessage.EDITOR_SCHEMA_REVISION_UNSUPPORTED_MESSAGE);
        }

        PortfolioConfigDto config = mergeCompatibleConfig(incomingConfig, existingDraftConfig);
        if (!PortfolioConfigDto.SCHEMA_VERSION_STANDARD_PERSONAL_V1.equals(config.getSchemaVersion())) {
            throw new BusinessException(PortfolioMessage.PORTFOLIO_CONFIG_VERSION_UNSUPPORTED_MESSAGE);
        }

        PortfolioConfigDto normalized = new PortfolioConfigDto();
        normalized.setSchemaVersion(config.getSchemaVersion());
        normalized.setEditorSchemaRevision(config.getEditorSchemaRevision());
        normalized.setShare(copyShare(config.getShare()));
        normalized.setStyle(normalizeStyle(config.getStyle()));

        PortfolioConfigDto.BottomNav normalizedBottomNav = normalizeBottomNavMetadata(config.getBottomNav());
        normalized.setBottomNav(normalizedBottomNav);

        Set<String> componentKeys = new LinkedHashSet<>();
        int[] generatedKeySequence = {1};
        boolean navigationEnabled = normalizedBottomNav != null
                && Boolean.TRUE.equals(normalizedBottomNav.getEnabled());
        String firstMenuTitle = navigationEnabled
                ? normalizedBottomNav.getItems().getFirst().getTitle()
                : "";
        ComponentValidationContext primaryCtx = navigationEnabled
                ? new ComponentValidationContext(firstMenuTitle, true)
                : ComponentValidationContext.LEGACY;
        List<PortfolioConfigDto.Component> normalizedTopLevel = normalizeComponentList(
                userId,
                config.getComponents(),
                primaryCtx,
                componentKeys,
                generatedKeySequence
        );
        if (normalizedTopLevel.isEmpty()) {
            throw new BusinessException(PortfolioMessage.ENABLED_COMPONENT_REQUIRED_MESSAGE);
        }
        normalized.setComponents(normalizedTopLevel);

        if (normalizedBottomNav != null && Boolean.TRUE.equals(normalizedBottomNav.getEnabled())) {
            List<PortfolioConfigDto.BottomNavItem> sourceItems = safeList(config.getBottomNav().getItems());
            List<PortfolioConfigDto.BottomNavItem> normalizedItems = normalizedBottomNav.getItems();
            for (int menuIndex = 1; menuIndex < normalizedItems.size(); menuIndex++) {
                PortfolioConfigDto.BottomNavItem sourceItem = sourceItems.get(menuIndex);
                PortfolioConfigDto.BottomNavItem normalizedItem = normalizedItems.get(menuIndex);
                ComponentValidationContext secondaryCtx = new ComponentValidationContext(
                        normalizedItem.getTitle(), true);
                normalizedItem.setComponents(normalizeComponentList(
                        userId,
                        sourceItem == null ? null : sourceItem.getComponents(),
                        secondaryCtx,
                        componentKeys,
                        generatedKeySequence
                ));
            }
        }
        return normalized;
    }

    /**
     * 按发布规则重新校验完整草稿。
     *
     * @param userId 当前用户 ID
     * @param normalizedDraftConfig 已规范化草稿配置
     */
    public void validateForPublish(Long userId, PortfolioConfigDto normalizedDraftConfig) {
        PortfolioConfigDto validated = normalizeForDraft(userId, normalizedDraftConfig, null);
        PortfolioConfigDto.BottomNav bottomNav = validated.getBottomNav();
        if (bottomNav == null || !Boolean.TRUE.equals(bottomNav.getEnabled())) {
            return;
        }
        List<PortfolioConfigDto.BottomNavItem> items = safeList(bottomNav.getItems());
        for (int menuIndex = 1; menuIndex < items.size(); menuIndex++) {
            PortfolioConfigDto.BottomNavItem item = items.get(menuIndex);
            if (item == null || safeList(item.getComponents()).isEmpty()) {
                throw new BusinessException(String.format(
                        PortfolioMessage.MENU_COMPONENT_REQUIRED_TEMPLATE,
                        item == null ? "" : defaultString(item.getTitle())
                ));
            }
        }
    }

    /**
     * 合并新旧编辑器请求配置。
     * <p>
     * 核心字段（schemaVersion、share、components）始终以本次请求为准。
     * editorSchemaRevision 本身：新请求用新值，旧请求保留草稿中的值。
     * 其余版本相关字段按 {@link #CONFIG_FIELD_MERGE_STEPS} 逐版本合并。
     *
     * @param incomingConfig       本次请求配置
     * @param existingDraftConfig 服务端当前草稿
     * @return 待规范化配置
     */
    private PortfolioConfigDto mergeCompatibleConfig(
            PortfolioConfigDto incomingConfig,
            PortfolioConfigDto existingDraftConfig
    ) {
        PortfolioConfigDto merged = new PortfolioConfigDto();
        merged.setSchemaVersion(incomingConfig.getSchemaVersion());
        merged.setShare(incomingConfig.getShare());
        merged.setComponents(incomingConfig.getComponents());

        // editorSchemaRevision 本身：新请求用新值，缺省或显式低版本请求保留草稿中已有的新能力版本
        Integer incomingRevision = incomingConfig.getEditorSchemaRevision();
        Integer existingRevision = existingDraftConfig == null
                ? null
                : existingDraftConfig.getEditorSchemaRevision();
        boolean legacyIncoming = incomingRevision == null
                || incomingRevision < PortfolioConfigDto.EDITOR_SCHEMA_REVISION_CURRENT;
        boolean existingHasCurrentFields = existingRevision != null
                && existingRevision >= PortfolioConfigDto.EDITOR_SCHEMA_REVISION_CURRENT;
        merged.setEditorSchemaRevision(
                legacyIncoming && existingHasCurrentFields ? existingRevision : incomingRevision
        );

        // 逐版本字段合并：新编辑器携带对应版本则取 incoming，否则从草稿保留
        for (ConfigFieldMergeStep step : CONFIG_FIELD_MERGE_STEPS) {
            boolean incomingHasFields = incomingConfig.getEditorSchemaRevision() != null
                    && incomingConfig.getEditorSchemaRevision() >= step.introducedAtRevision();
            boolean existingHasFields = existingDraftConfig != null
                    && existingDraftConfig.getEditorSchemaRevision() != null
                    && existingDraftConfig.getEditorSchemaRevision() >= step.introducedAtRevision();

            if (incomingHasFields) {
                step.mergeFields().accept(merged, incomingConfig);
            } else if (existingHasFields) {
                step.mergeFields().accept(merged, existingDraftConfig);
            }
            // 两边都没有 → 保持 null，由后续 normalize 赋默认值
        }
        return merged;
    }

    /**
     * 规范化页面样式。
     *
     * @param style 原页面样式
     * @return 页面样式
     */
    private PortfolioConfigDto.Style normalizeStyle(PortfolioConfigDto.Style style) {
        PortfolioConfigDto.Style normalized = new PortfolioConfigDto.Style();
        String backgroundColor = style == null ? "" : defaultString(style.getBackgroundColor());
        normalized.setBackgroundColor(BACKGROUND_COLOR_PATTERN.matcher(backgroundColor).matches()
                ? backgroundColor.toUpperCase()
                : PortfolioConfigDto.DEFAULT_BACKGROUND_COLOR);
        return normalized;
    }

    /**
     * 校验并复制底部导航元数据。
     *
     * @param bottomNav 原导航配置
     * @return 规范化导航配置
     */
    private PortfolioConfigDto.BottomNav normalizeBottomNavMetadata(PortfolioConfigDto.BottomNav bottomNav) {
        PortfolioConfigDto.BottomNav normalized = new PortfolioConfigDto.BottomNav();
        boolean enabled = bottomNav != null && Boolean.TRUE.equals(bottomNav.getEnabled());
        normalized.setEnabled(enabled);
        if (!enabled) {
            normalized.setItems(null);
            return normalized;
        }
        List<PortfolioConfigDto.BottomNavItem> items = safeList(bottomNav.getItems());
        if (items.size() < BOTTOM_NAV_MIN_ITEM_COUNT || items.size() > BOTTOM_NAV_MAX_ITEM_COUNT) {
            throw new BusinessException(PortfolioMessage.BOTTOM_NAV_ITEM_COUNT_INVALID_MESSAGE);
        }
        PortfolioConfigDto.BottomNavItem firstItem = items.getFirst();
        if (firstItem != null && firstItem.getComponents() != null) {
            throw new BusinessException(PortfolioMessage.FIRST_BOTTOM_NAV_COMPONENTS_DUPLICATE_MESSAGE);
        }

        Set<String> keys = new LinkedHashSet<>();
        Set<String> titles = new LinkedHashSet<>();
        List<PortfolioConfigDto.BottomNavItem> normalizedItems = new ArrayList<>();
        for (int menuIndex = 0; menuIndex < items.size(); menuIndex++) {
            PortfolioConfigDto.BottomNavItem item = items.get(menuIndex);
            String key = item == null ? "" : defaultString(item.getKey());
            if (!BOTTOM_NAV_KEY_PATTERN.matcher(key).matches()) {
                throw new BusinessException(PortfolioMessage.BOTTOM_NAV_KEY_INVALID_MESSAGE);
            }
            if (!keys.add(key)) {
                throw new BusinessException(PortfolioMessage.BOTTOM_NAV_KEY_DUPLICATE_MESSAGE);
            }
            String title = item == null ? "" : defaultString(item.getTitle());
            if (title.isEmpty()) {
                throw new BusinessException(PortfolioMessage.BOTTOM_NAV_TITLE_REQUIRED_MESSAGE);
            }
            if (title.codePointCount(0, title.length()) > BOTTOM_NAV_TITLE_MAX_CODE_POINTS) {
                throw new BusinessException(PortfolioMessage.BOTTOM_NAV_TITLE_TOO_LONG_MESSAGE);
            }
            if (!titles.add(title)) {
                throw new BusinessException(PortfolioMessage.BOTTOM_NAV_TITLE_DUPLICATE_MESSAGE);
            }
            PortfolioConfigDto.BottomNavItem normalizedItem = new PortfolioConfigDto.BottomNavItem();
            normalizedItem.setKey(key);
            normalizedItem.setTitle(title);
            normalizedItem.setIconUrl(defaultString(item == null ? null : item.getIconUrl()));
            normalizedItem.setComponents(menuIndex == 0 ? null : List.of());
            normalizedItems.add(normalizedItem);
        }
        normalized.setItems(normalizedItems);
        return normalized;
    }

    /**
     * 校验并规范化单个菜单的组件列表。
     *
     * @param userId                当前用户 ID
     * @param components            原组件列表
     * @param ctx                   校验上下文
     * @param componentKeys         全菜单组件键集合
     * @param generatedKeySequence 自动组件键序号
     * @return 规范化组件列表
     */
    private List<PortfolioConfigDto.Component> normalizeComponentList(
            Long userId,
            List<PortfolioConfigDto.Component> components,
            ComponentValidationContext ctx,
            Set<String> componentKeys,
            int[] generatedKeySequence
    ) {
        List<PortfolioConfigDto.Component> enabledComponents = safeList(components).stream()
                .filter(component -> Boolean.TRUE.equals(component.getEnabled()))
                .map(this::copyComponent)
                .sorted(Comparator
                        .comparing(this::safeSortOrder)
                        .thenComparing(component -> defaultString(component.getComponentKey())))
                .toList();
        validateProfileComponentLimit(enabledComponents, ctx);

        List<PortfolioConfigDto.Component> normalizedComponents = new ArrayList<>();
        int index = 0;
        for (PortfolioConfigDto.Component component : enabledComponents) {
            String key = defaultString(component.getComponentKey());
            if (key.isBlank()) {
                do {
                    key = "c_" + generatedKeySequence[0]++;
                } while (componentKeys.contains(key));
                component.setComponentKey(key);
            }
            if (!componentKeys.add(key)) {
                throw new BusinessException(PortfolioMessage.COMPONENT_KEY_CROSS_MENU_DUPLICATE_MESSAGE);
            }
            component.setSortOrder((index + 1) * DEFAULT_SORT_ORDER_STEP);
            try {
                validateComponent(userId, component);
            } catch (BusinessException exception) {
                if (!ctx.menuAware()) {
                    throw exception;
                }
                throw ctx.toException(exception.getMessage());
            }
            normalizedComponents.add(component);
            index++;
        }
        return normalizedComponents;
    }

    /**
     * 校验个人资料组件的单例限制。
     *
     * @param enabledComponents 已启用组件
     * @param ctx               校验上下文
     */
    private void validateProfileComponentLimit(
            List<PortfolioConfigDto.Component> enabledComponents,
            ComponentValidationContext ctx
    ) {
        long profileComponentCount = enabledComponents.stream()
                .filter(component -> PortfolioComponentTypeDict.PROFILE.getCode().equals(component.getComponentType()))
                .count();
        if (profileComponentCount > 1) {
            if (ctx.menuAware()) {
                throw ctx.toException(PortfolioMessage.MENU_PROFILE_COMPONENT_LIMIT_MESSAGE);
            }
            throw new BusinessException(PortfolioMessage.PROFILE_COMPONENT_LIMIT_MESSAGE);
        }
    }

    /**
     * 构建指定配置作用域下的引用记录。
     *
     * @param portfolioId 作品集 ID
     * @param ownerId 归属用户 ID
     * @param configScope 配置作用域
     * @param config 规范化配置
     * @return 引用列表
     */
    public List<PortfolioReferenceEntity> buildReferences(
            Long portfolioId,
            Long ownerId,
            String configScope,
            PortfolioConfigDto config
    ) {
        List<PortfolioReferenceEntity> references = new ArrayList<>();
        for (PortfolioComponentTraversal.ComponentLocation location
                : PortfolioComponentTraversal.listComponentLocations(config)) {
            PortfolioConfigDto.Component component = location.component();
            if (component == null) {
                continue;
            }
            PortfolioComponentTypeDict componentType = PortfolioComponentTypeDict.fromCode(component.getComponentType());
            if (componentType == null) {
                continue;
            }
            String componentPath = location.componentPath();
            switch (componentType) {
                case PROFILE -> references.add(reference(
                        portfolioId,
                        configScope,
                        ReferenceTypeDict.USER_PROFILE.getCode(),
                        ownerId,
                        component,
                        componentPath + ".config.profile",
                        0
                ));
                case SCHEDULE_QUERY -> references.add(reference(
                        portfolioId,
                        configScope,
                        ReferenceTypeDict.SCHEDULE_COMPONENT.getCode(),
                        ownerId,
                        component,
                        componentPath + ".config",
                        0
                ));
                case QR_CONTACT -> references.add(reference(
                        portfolioId,
                        configScope,
                        ReferenceTypeDict.QR_CODE_ASSET.getCode(),
                        ownerId,
                        component,
                        componentPath + ".config.qrUrl",
                        0
                ));
                case CAROUSEL -> addFlatWorkReferences(references, portfolioId, configScope, component, componentPath);
                case WORK_GRID, WORK_LIST -> addGroupedWorkReferences(references, portfolioId, configScope, component, componentPath);
                case SINGLE_WORK -> addSingleWorkReference(
                        references,
                        portfolioId,
                        configScope,
                        component,
                        componentPath
                );
                default -> {
                }
            }
        }
        return references;
    }

    /**
     * 校验联系表单提交内容。
     *
     * @param component 联系表单组件
     * @param payload 表单提交内容
     */
    public void validateContactFormSubmission(PortfolioConfigDto.Component component, Map<String, String> payload) {
        if (component == null || !PortfolioComponentTypeDict.CONTACT_FORM.getCode().equals(component.getComponentType())) {
            throw new BusinessException(PortfolioMessage.CONTACT_FORM_COMPONENT_NOT_FOUND_MESSAGE);
        }
        Map<String, String> safePayload = payload == null ? Map.of() : payload;
        if (!hasText(safePayload.get(CONTACT_FIELD_NAME))) {
            throw new BusinessException(PortfolioMessage.CONTACT_NAME_REQUIRED_MESSAGE);
        }
        if (!hasText(safePayload.get(CONTACT_FIELD_PHONE)) && !hasText(safePayload.get(CONTACT_FIELD_WECHAT))) {
            throw new BusinessException(PortfolioMessage.CONTACT_METHOD_REQUIRED_MESSAGE);
        }
    }

    /**
     * 校验单个组件。
     *
     * @param userId 当前用户 ID
     * @param component 组件
     */
    private void validateComponent(Long userId, PortfolioConfigDto.Component component) {
        PortfolioComponentTypeDict componentType = PortfolioComponentTypeDict.fromCode(component.getComponentType());
        if (componentType == null) {
            throw new BusinessException(String.format(
                    PortfolioMessage.COMPONENT_UNSUPPORTED_TEMPLATE,
                    defaultString(component.getComponentType())
            ));
        }
        switch (componentType) {
            case CAROUSEL -> validateCarousel(userId, component);
            case WORK_GRID -> validateWorkDisplayGroups(userId, component, WORK_GRID_MAX_COUNT, 2);
            case WORK_LIST -> validateWorkDisplayGroups(userId, component, WORK_LIST_MAX_COUNT, 1);
            case SINGLE_WORK -> validateSingleWork(userId, component);
            case SCHEDULE_QUERY -> validateScheduleQuery(component);
            case QR_CONTACT -> validateQrContact(component);
            case CONTACT_FORM -> validateContactForm(component);
            case TEXT_SECTION -> validateTextSection(component);
            case DIVIDER -> validateDivider(component);
            default -> {
            }
        }
    }

    /**
     * 校验轮播图组件。
     *
     * @param userId 当前用户 ID
     * @param component 组件
     */
    private void validateCarousel(Long userId, PortfolioConfigDto.Component component) {
        List<Long> workIds = normalizeWorkIds(component, CAROUSEL_WORK_MAX_COUNT);
        Map<Long, WorkEntity> workMap = loadUsableWorks(userId, workIds);
        if (workMap.size() != workIds.size()) {
            throw new BusinessException(PortfolioMessage.WORK_REFERENCE_INVALID_MESSAGE);
        }
        boolean hasVideo = workMap.values().stream()
                .anyMatch(work -> !MediaTypeDict.IMAGE.getCode().equals(work.getMediaType()));
        if (hasVideo) {
            throw new BusinessException(PortfolioMessage.CAROUSEL_IMAGE_ONLY_MESSAGE);
        }
        component.getConfig().put(CONFIG_KEY_WORK_IDS, workIds);
    }

    /**
     * 校验作品列表展示标签。
     *
     * @param userId 当前用户 ID
     * @param component 组件
     * @param maxCount 每个展示标签最大作品数
     * @param columns 展示列数
     */
    private void validateWorkDisplayGroups(
            Long userId,
            PortfolioConfigDto.Component component,
            int maxCount,
            int columns
    ) {
        List<Map<String, Object>> groups = normalizeDisplayGroups(component, maxCount);
        List<Long> workIds = groups.stream()
                .flatMap(group -> asLongList(group.get(CONFIG_KEY_WORK_IDS)).stream())
                .distinct()
                .toList();
        Map<Long, WorkEntity> workMap = loadUsableWorks(userId, workIds);
        if (workMap.size() != workIds.size()) {
            throw new BusinessException(PortfolioMessage.WORK_REFERENCE_INVALID_MESSAGE);
        }
        boolean hasUnsupportedMedia = workMap.values().stream()
                .anyMatch(work -> !BULK_WORK_MEDIA_TYPES.contains(work.getMediaType()));
        if (hasUnsupportedMedia) {
            throw new BusinessException(PortfolioMessage.WORK_REFERENCE_INVALID_MESSAGE);
        }
        component.getConfig().put(CONFIG_KEY_GROUPS, groups);
        component.getConfig().put(CONFIG_KEY_COLUMNS, columns);
        normalizeWorkDisplayOptions(component.getConfig());
    }

    /**
     * 校验并规范化单个作品组件。
     *
     * @param userId 当前用户 ID
     * @param component 组件
     */
    private void validateSingleWork(Long userId, PortfolioConfigDto.Component component) {
        Long workId = asExactLong(component.getConfig().get(CONFIG_KEY_WORK_ID));
        if (workId == null || workId <= 0L) {
            throw new BusinessException(PortfolioMessage.WORK_REFERENCE_INVALID_MESSAGE);
        }
        Map<Long, WorkEntity> workMap = loadUsableWorks(userId, List.of(workId));
        WorkEntity work = workMap.get(workId);
        if (work == null || !SINGLE_WORK_MEDIA_TYPES.contains(work.getMediaType())) {
            throw new BusinessException(PortfolioMessage.WORK_REFERENCE_INVALID_MESSAGE);
        }
        Map<String, Object> normalizedConfig = new LinkedHashMap<>();
        normalizedConfig.put(CONFIG_KEY_WORK_ID, workId);
        Object showTitle = component.getConfig().get(CONFIG_KEY_SHOW_TITLE);
        normalizedConfig.put(CONFIG_KEY_SHOW_TITLE, showTitle instanceof Boolean value ? value : Boolean.TRUE);
        Object showDescription = component.getConfig().get(CONFIG_KEY_SHOW_DESCRIPTION);
        normalizedConfig.put(
                CONFIG_KEY_SHOW_DESCRIPTION,
                showDescription instanceof Boolean value ? value : Boolean.FALSE
        );
        component.setConfig(normalizedConfig);
    }

    /**
     * 规范化作品标题和说明展示开关。
     *
     * @param config 组件配置
     */
    private void normalizeWorkDisplayOptions(Map<String, Object> config) {
        Object showTitle = config.get(CONFIG_KEY_SHOW_TITLE);
        config.put(CONFIG_KEY_SHOW_TITLE, showTitle instanceof Boolean value ? value : Boolean.TRUE);
        Object showDescription = config.get(CONFIG_KEY_SHOW_DESCRIPTION);
        config.put(
                CONFIG_KEY_SHOW_DESCRIPTION,
                showDescription instanceof Boolean value ? value : Boolean.FALSE
        );
    }

    /**
     * 校验二维码联系组件。
     *
     * @param component 组件
     */
    private void validateQrContact(PortfolioConfigDto.Component component) {
        String source = defaultString(asString(component.getConfig().get(CONFIG_KEY_QR_URL_SOURCE)), QR_SOURCE_PROFILE);
        if (!QR_SOURCE_PROFILE.equals(source) && !QR_SOURCE_CUSTOM.equals(source)) {
            throw new BusinessException(PortfolioMessage.QR_SOURCE_UNSUPPORTED_MESSAGE);
        }
        if (QR_SOURCE_CUSTOM.equals(source) && !hasText(asString(component.getConfig().get(CONFIG_KEY_QR_URL)))) {
            throw new BusinessException(PortfolioMessage.CUSTOM_QR_REQUIRED_MESSAGE);
        }
        component.getConfig().put(CONFIG_KEY_QR_URL_SOURCE, source);
    }

    /**
     * 校验联系表单组件。
     *
     * @param component 组件
     */
    private void validateContactForm(PortfolioConfigDto.Component component) {
        List<String> fields = asStringList(component.getConfig().get(CONFIG_KEY_FIELDS));
        if (fields.isEmpty()) {
            fields = DEFAULT_CONTACT_FORM_FIELDS;
        }
        if (!fields.contains(CONTACT_FIELD_NAME)) {
            throw new BusinessException(PortfolioMessage.CONTACT_FORM_NAME_FIELD_REQUIRED_MESSAGE);
        }
        if (!fields.contains(CONTACT_FIELD_PHONE) && !fields.contains(CONTACT_FIELD_WECHAT)) {
            throw new BusinessException(PortfolioMessage.CONTACT_FORM_CONTACT_FIELD_REQUIRED_MESSAGE);
        }
        String displayMode = defaultString(
                asString(component.getConfig().get(CONFIG_KEY_DISPLAY_MODE)),
                CONTACT_FORM_DISPLAY_MODE_MODAL_FORM
        );
        if (!CONTACT_FORM_DISPLAY_MODE_MODAL_FORM.equals(displayMode)
                && !CONTACT_FORM_DISPLAY_MODE_INLINE_FORM.equals(displayMode)) {
            throw new BusinessException(PortfolioMessage.CONTACT_FORM_DISPLAY_MODE_UNSUPPORTED_MESSAGE);
        }
        component.getConfig().put(CONFIG_KEY_FIELDS, fields);
        component.getConfig().put(CONFIG_KEY_DISPLAY_MODE, displayMode);
    }

    /**
     * 校验档期查询组件。
     *
     * @param component 组件
     */
    private void validateScheduleQuery(PortfolioConfigDto.Component component) {
        Map<String, Object> source = asObjectMap(component.getConfig().get(CONFIG_KEY_QUERY_RANGE));
        String displayMode = defaultString(
                asString(component.getConfig().get(CONFIG_KEY_DISPLAY_MODE)),
                SCHEDULE_DISPLAY_MODE_MODAL_CALENDAR
        );
        if (!SCHEDULE_DISPLAY_MODE_MODAL_CALENDAR.equals(displayMode)
                && !SCHEDULE_DISPLAY_MODE_INLINE_CALENDAR.equals(displayMode)) {
            throw new BusinessException(PortfolioMessage.SCHEDULE_QUERY_DISPLAY_MODE_UNSUPPORTED_MESSAGE);
        }
        String rangeType = defaultString(asString(source.get(CONFIG_KEY_RANGE_TYPE)), QUERY_RANGE_UNLIMITED);
        Map<String, Object> normalized = new LinkedHashMap<>();
        normalized.put(CONFIG_KEY_RANGE_TYPE, rangeType);
        switch (rangeType) {
            case QUERY_RANGE_UNLIMITED -> {
                normalized.put(CONFIG_KEY_FUTURE_DAYS, null);
                normalized.put(CONFIG_KEY_START_DATE, null);
                normalized.put(CONFIG_KEY_END_DATE, null);
            }
            case QUERY_RANGE_FUTURE_DAYS -> normalizeFutureDaysRange(source, normalized);
            case QUERY_RANGE_DATE_RANGE -> normalizeDateRange(source, normalized);
            default -> throw new BusinessException(PortfolioMessage.SCHEDULE_QUERY_RANGE_UNSUPPORTED_MESSAGE);
        }
        component.getConfig().put(CONFIG_KEY_DISPLAY_MODE, displayMode);
        component.getConfig().put(CONFIG_KEY_QUERY_RANGE, normalized);
    }

    /**
     * 校验文字说明组件。
     *
     * @param component 组件
     */
    private void validateTextSection(PortfolioConfigDto.Component component) {
        String content = asString(component.getConfig().get(CONFIG_KEY_CONTENT));
        if (!hasText(content)) {
            throw new BusinessException(PortfolioMessage.TEXT_SECTION_CONTENT_REQUIRED_MESSAGE);
        }
        if (content.codePointCount(0, content.length()) > TEXT_SECTION_CONTENT_MAX_LENGTH) {
            throw new BusinessException(PortfolioMessage.TEXT_SECTION_CONTENT_LENGTH_MESSAGE);
        }
        String alignment = defaultString(
                asString(component.getConfig().get(CONFIG_KEY_ALIGNMENT)),
                TEXT_SECTION_ALIGNMENT_LEFT
        );
        if (!TEXT_SECTION_ALIGNMENTS.contains(alignment)) {
            throw new BusinessException(PortfolioMessage.TEXT_SECTION_ALIGNMENT_UNSUPPORTED_MESSAGE);
        }
        Object fontFamilySource = component.getConfig().get(
                PortfolioTextTypographySupport.FONT_FAMILY_CONFIG_KEY);
        String fontFamily = fontFamilySource == null
                ? PortfolioTextFontFamilyDict.SYSTEM.getCode()
                : PortfolioTextTypographySupport.asSupportedFontFamily(fontFamilySource);
        if (fontFamily == null) {
            throw new BusinessException(PortfolioMessage.TEXT_SECTION_FONT_UNSUPPORTED_MESSAGE);
        }
        Object fontSizeSource = component.getConfig().get(
                PortfolioTextTypographySupport.FONT_SIZE_RPX_CONFIG_KEY);
        Integer fontSizeRpx = fontSizeSource == null
                ? Integer.valueOf(PortfolioTextTypographyConstants.LEGACY_PERSONAL_FONT_SIZE_RPX)
                : PortfolioTextTypographySupport.asExactFontSizeRpx(fontSizeSource);
        if (!PortfolioTextTypographySupport.isValidFontSizeRpx(fontSizeRpx)) {
            throw new BusinessException(PortfolioMessage.TEXT_SECTION_FONT_SIZE_INVALID_MESSAGE);
        }
        component.getConfig().put(CONFIG_KEY_CONTENT, content);
        component.getConfig().put(CONFIG_KEY_ALIGNMENT, alignment);
        component.getConfig().put(PortfolioTextTypographySupport.FONT_FAMILY_CONFIG_KEY, fontFamily);
        component.getConfig().put(PortfolioTextTypographySupport.FONT_SIZE_RPX_CONFIG_KEY, fontSizeRpx);
    }

    /**
     * 校验分割线组件。
     *
     * @param component 组件
     */
    private void validateDivider(PortfolioConfigDto.Component component) {
        String color = defaultString(
                asString(component.getConfig().get(CONFIG_KEY_DIVIDER_COLOR)),
                DIVIDER_COLOR_GRAY
        );
        if (!DIVIDER_COLORS.contains(color)) {
            throw new BusinessException(PortfolioMessage.DIVIDER_COLOR_UNSUPPORTED_MESSAGE);
        }
        Object heightSource = component.getConfig().get(CONFIG_KEY_DIVIDER_HEIGHT_PX);
        Integer heightPx = heightSource == null ? DEFAULT_DIVIDER_HEIGHT_PX : asInteger(heightSource);
        if (heightPx == null || heightPx <= 0) {
            throw new BusinessException(PortfolioMessage.DIVIDER_HEIGHT_INVALID_MESSAGE);
        }
        component.getConfig().put(CONFIG_KEY_DIVIDER_COLOR, color);
        component.getConfig().put(CONFIG_KEY_DIVIDER_HEIGHT_PX, heightPx);
    }

    /**
     * 规范化未来天数档期范围。
     *
     * @param source 原始范围
     * @param normalized 规范化范围
     */
    private void normalizeFutureDaysRange(Map<String, Object> source, Map<String, Object> normalized) {
        Integer futureDays = asInteger(source.get(CONFIG_KEY_FUTURE_DAYS));
        if (futureDays == null || futureDays <= 0) {
            throw new BusinessException(PortfolioMessage.SCHEDULE_QUERY_FUTURE_DAYS_INVALID_MESSAGE);
        }
        normalized.put(CONFIG_KEY_FUTURE_DAYS, futureDays);
        normalized.put(CONFIG_KEY_START_DATE, null);
        normalized.put(CONFIG_KEY_END_DATE, null);
    }

    /**
     * 规范化固定日期档期范围。
     *
     * @param source 原始范围
     * @param normalized 规范化范围
     */
    private void normalizeDateRange(Map<String, Object> source, Map<String, Object> normalized) {
        LocalDate startDate = parseDate(source.get(CONFIG_KEY_START_DATE));
        LocalDate endDate = parseDate(source.get(CONFIG_KEY_END_DATE));
        if (startDate.isAfter(endDate)) {
            throw new BusinessException(PortfolioMessage.SCHEDULE_QUERY_DATE_RANGE_INVALID_MESSAGE);
        }
        normalized.put(CONFIG_KEY_FUTURE_DAYS, null);
        normalized.put(CONFIG_KEY_START_DATE, startDate.toString());
        normalized.put(CONFIG_KEY_END_DATE, endDate.toString());
    }

    /**
     * 读取并校验用户作品。
     *
     * @param userId 当前用户 ID
     * @param workIds 作品 ID 列表
     * @return 可用作品映射
     */
    private Map<Long, WorkEntity> loadUsableWorks(Long userId, List<Long> workIds) {
        List<WorkEntity> works = workIds.isEmpty()
                ? List.of()
                : safeList(workEntityMapper.selectBatchIds(workIds));
        Set<Long> requestedIds = new LinkedHashSet<>(workIds);
        Map<Long, WorkEntity> result = new LinkedHashMap<>();
        for (WorkEntity work : works) {
            if (work == null || work.getId() == null) {
                continue;
            }
            if (!requestedIds.contains(work.getId())) {
                continue;
            }
            if (!Objects.equals(userId, work.getUserId())) {
                continue;
            }
            if (!WorkStatusDict.ACTIVE.getCode().equals(work.getStatus())) {
                continue;
            }
            if (MediaTypeDict.ANIMATION.getCode().equals(work.getMediaType())
                    && !WorkAuditStatusDict.PASSED.getCode().equals(work.getAuditStatus())) {
                continue;
            }
            result.put(work.getId(), work);
        }
        return result;
    }

    /**
     * 规范化作品集展示标签。
     *
     * @param component 组件
     * @param maxCount 每个展示标签最大作品数
     * @return 规范化展示标签列表
     */
    private List<Map<String, Object>> normalizeDisplayGroups(PortfolioConfigDto.Component component, int maxCount) {
        List<Map<String, Object>> sourceGroups = asMapList(component.getConfig().get(CONFIG_KEY_GROUPS));
        if (sourceGroups.isEmpty()) {
            List<Long> workIds = normalizeWorkIds(component, maxCount);
            Map<String, Object> group = new LinkedHashMap<>();
            group.put(CONFIG_KEY_GROUP_KEY, DEFAULT_GROUP_KEY_PREFIX + "all");
            group.put(CONFIG_KEY_GROUP_NAME, DEFAULT_WORK_GROUP_NAME);
            group.put(CONFIG_KEY_SORT_ORDER, DEFAULT_SORT_ORDER_STEP);
            group.put(CONFIG_KEY_WORK_IDS, workIds);
            sourceGroups = List.of(group);
            component.getConfig().put(CONFIG_KEY_WORK_IDS, workIds);
        }
        List<Map<String, Object>> sortedGroups = sourceGroups.stream()
                .sorted(Comparator
                        .comparing(this::safeGroupSortOrder)
                        .thenComparing(group -> defaultString(asString(group.get(CONFIG_KEY_GROUP_KEY)))))
                .toList();
        List<Map<String, Object>> normalized = new ArrayList<>();
        Set<String> groupNames = new LinkedHashSet<>();
        Set<String> groupKeys = new LinkedHashSet<>();
        for (int index = 0; index < sortedGroups.size(); index++) {
            Map<String, Object> group = sortedGroups.get(index);
            String groupKey = defaultString(asString(group.get(CONFIG_KEY_GROUP_KEY)), DEFAULT_GROUP_KEY_PREFIX + (index + 1));
            if (!groupKeys.add(groupKey)) {
                throw new BusinessException(PortfolioMessage.DISPLAY_TAG_KEY_DUPLICATE_MESSAGE);
            }
            String name = asString(group.get(CONFIG_KEY_GROUP_NAME));
            if (!hasText(name)) {
                throw new BusinessException(PortfolioMessage.DISPLAY_TAG_NAME_REQUIRED_MESSAGE);
            }
            if (!groupNames.add(name)) {
                throw new BusinessException(PortfolioMessage.DISPLAY_TAG_NAME_DUPLICATE_MESSAGE);
            }
            List<Long> workIds = normalizeWorkIds(group, maxCount);
            Map<String, Object> normalizedGroup = new LinkedHashMap<>();
            normalizedGroup.put(CONFIG_KEY_GROUP_KEY, groupKey);
            normalizedGroup.put(CONFIG_KEY_GROUP_NAME, name);
            normalizedGroup.put(CONFIG_KEY_SORT_ORDER, (index + 1) * DEFAULT_SORT_ORDER_STEP);
            normalizedGroup.put(CONFIG_KEY_WORK_IDS, workIds);
            normalized.add(normalizedGroup);
        }
        return normalized;
    }

    /**
     * 规范化组件作品 ID。
     *
     * @param component 组件
     * @param maxCount 最大数量
     * @return 去重后的作品 ID
     */
    private List<Long> normalizeWorkIds(PortfolioConfigDto.Component component, int maxCount) {
        return normalizeWorkIds(component.getConfig(), maxCount);
    }

    /**
     * 规范化配置 Map 中的作品 ID。
     *
     * @param config 配置 Map
     * @param maxCount 最大数量
     * @return 去重后的作品 ID
     */
    private List<Long> normalizeWorkIds(Map<String, Object> config, int maxCount) {
        List<Long> workIds = asLongList(config.get(CONFIG_KEY_WORK_IDS));
        if (workIds.isEmpty()) {
            throw new BusinessException(PortfolioMessage.DISPLAY_WORK_REQUIRED_MESSAGE);
        }
        if (workIds.size() > maxCount) {
            throw new BusinessException(PortfolioMessage.DISPLAY_WORK_COUNT_LIMIT_MESSAGE);
        }
        return workIds;
    }

    /**
     * 添加扁平作品引用。
     *
     * @param references 引用列表
     * @param portfolioId 作品集 ID
     * @param configScope 配置作用域
     * @param component 组件
     * @param componentPath 组件路径
     */
    private void addFlatWorkReferences(
            List<PortfolioReferenceEntity> references,
            Long portfolioId,
            String configScope,
            PortfolioConfigDto.Component component,
            String componentPath
    ) {
        List<Long> workIds = asLongList(component.getConfig().get(CONFIG_KEY_WORK_IDS));
        for (int index = 0; index < workIds.size(); index++) {
            references.add(reference(
                    portfolioId,
                    configScope,
                    ReferenceTypeDict.WORK.getCode(),
                    workIds.get(index),
                    component,
                    componentPath + ".config." + CONFIG_KEY_WORK_IDS + "[" + index + "]",
                    index
            ));
        }
    }

    /**
     * 添加作品集展示标签中的作品引用。
     *
     * @param references 引用列表
     * @param portfolioId 作品集 ID
     * @param configScope 配置作用域
     * @param component 组件
     * @param componentPath 组件路径
     */
    private void addGroupedWorkReferences(
            List<PortfolioReferenceEntity> references,
            Long portfolioId,
            String configScope,
            PortfolioConfigDto.Component component,
            String componentPath
    ) {
        List<Map<String, Object>> groups = asMapList(component.getConfig().get(CONFIG_KEY_GROUPS));
        for (int groupIndex = 0; groupIndex < groups.size(); groupIndex++) {
            List<Long> workIds = asLongList(groups.get(groupIndex).get(CONFIG_KEY_WORK_IDS));
            for (int workIndex = 0; workIndex < workIds.size(); workIndex++) {
                references.add(reference(
                        portfolioId,
                        configScope,
                        ReferenceTypeDict.WORK.getCode(),
                        workIds.get(workIndex),
                        component,
                        componentPath + ".config." + CONFIG_KEY_GROUPS + "[" + groupIndex + "]."
                                + CONFIG_KEY_WORK_IDS + "[" + workIndex + "]",
                        workIndex
                ));
            }
        }
    }

    /**
     * 添加单个作品引用。
     *
     * @param references 引用列表
     * @param portfolioId 作品集 ID
     * @param configScope 配置作用域
     * @param component 组件
     * @param componentPath 组件路径
     */
    private void addSingleWorkReference(
            List<PortfolioReferenceEntity> references,
            Long portfolioId,
            String configScope,
            PortfolioConfigDto.Component component,
            String componentPath
    ) {
        Long workId = asLong(component.getConfig().get(CONFIG_KEY_WORK_ID));
        if (workId == null || workId <= 0L) {
            return;
        }
        references.add(reference(
                portfolioId,
                configScope,
                ReferenceTypeDict.WORK.getCode(),
                workId,
                component,
                componentPath + ".config." + CONFIG_KEY_WORK_ID,
                0
        ));
    }

    /**
     * 创建引用实体。
     *
     * @param portfolioId 作品集 ID
     * @param configScope 配置作用域
     * @param referenceType 引用类型
     * @param referenceId 引用 ID
     * @param component 组件
     * @param componentPath 组件路径
     * @param sortOrder 组件内排序
     * @return 引用实体
     */
    private PortfolioReferenceEntity reference(
            Long portfolioId,
            String configScope,
            String referenceType,
            Long referenceId,
            PortfolioConfigDto.Component component,
            String componentPath,
            int sortOrder
    ) {
        PortfolioReferenceEntity reference = new PortfolioReferenceEntity();
        reference.setPortfolioId(portfolioId);
        reference.setConfigScope(configScope);
        reference.setReferenceType(referenceType);
        reference.setReferenceId(referenceId);
        reference.setComponentKey(component.getComponentKey());
        reference.setComponentPath(componentPath);
        reference.setSortOrder(sortOrder);
        reference.setIsValid(1);
        return reference;
    }

    /**
     * 复制组件。
     *
     * @param component 原组件
     * @return 新组件
     */
    private PortfolioConfigDto.Component copyComponent(PortfolioConfigDto.Component component) {
        if (component == null) {
            throw new BusinessException(PortfolioMessage.PORTFOLIO_COMPONENT_EMPTY_MESSAGE);
        }
        PortfolioConfigDto.Component copied = new PortfolioConfigDto.Component();
        copied.setComponentKey(defaultString(component.getComponentKey()));
        copied.setComponentType(defaultString(component.getComponentType()));
        copied.setSortOrder(component.getSortOrder());
        copied.setEnabled(Boolean.TRUE);
        copied.setConfig(new LinkedHashMap<>(component.getConfig() == null ? Map.of() : component.getConfig()));
        return copied;
    }

    /**
     * 复制分享信息。
     *
     * @param share 原分享信息
     * @return 新分享信息
     */
    private PortfolioConfigDto.Share copyShare(PortfolioConfigDto.Share share) {
        PortfolioConfigDto.Share copied = new PortfolioConfigDto.Share();
        if (share == null) {
            return copied;
        }
        copied.setTitle(defaultString(share.getTitle()));
        copied.setCoverUrl(defaultString(share.getCoverUrl()));
        copied.setAvatarUrl(defaultString(share.getAvatarUrl()));
        return copied;
    }

    /**
     * 安全排序值。
     *
     * @param component 组件
     * @return 排序值
     */
    private int safeSortOrder(PortfolioConfigDto.Component component) {
        return component == null || component.getSortOrder() == null ? Integer.MAX_VALUE : component.getSortOrder();
    }

    /**
     * 安全读取展示标签排序值。
     *
     * @param group 展示标签
     * @return 排序值
     */
    private int safeGroupSortOrder(Map<String, Object> group) {
        Integer sortOrder = asInteger(group.get(CONFIG_KEY_SORT_ORDER));
        return sortOrder == null ? Integer.MAX_VALUE : sortOrder;
    }

    /**
     * 转换为 Long 列表。
     *
     * @param value 原值
     * @return Long 列表
     */
    private List<Long> asLongList(Object value) {
        if (!(value instanceof Collection<?> collection)) {
            return List.of();
        }
        LinkedHashSet<Long> result = new LinkedHashSet<>();
        for (Object item : collection) {
            Long longValue = asLong(item);
            if (longValue != null && longValue > 0L) {
                result.add(longValue);
            }
        }
        return new ArrayList<>(result);
    }

    /**
     * 转换为 Map 列表。
     *
     * @param value 原值
     * @return Map 列表
     */
    private List<Map<String, Object>> asMapList(Object value) {
        if (!(value instanceof Collection<?> collection)) {
            return List.of();
        }
        List<Map<String, Object>> result = new ArrayList<>();
        for (Object item : collection) {
            Map<String, Object> map = asObjectMap(item);
            if (!map.isEmpty()) {
                result.add(map);
            }
        }
        return result;
    }

    /**
     * 转换为字符串键 Map。
     *
     * @param value 原值
     * @return 字符串键 Map
     */
    private Map<String, Object> asObjectMap(Object value) {
        if (!(value instanceof Map<?, ?> map)) {
            return Map.of();
        }
        Map<String, Object> result = new LinkedHashMap<>();
        for (Map.Entry<?, ?> entry : map.entrySet()) {
            if (entry.getKey() != null) {
                result.put(String.valueOf(entry.getKey()), entry.getValue());
            }
        }
        return result;
    }

    /**
     * 转换为字符串列表。
     *
     * @param value 原值
     * @return 字符串列表
     */
    private List<String> asStringList(Object value) {
        if (!(value instanceof Collection<?> collection)) {
            return List.of();
        }
        LinkedHashSet<String> result = new LinkedHashSet<>();
        for (Object item : collection) {
            String text = asString(item);
            if (hasText(text)) {
                result.add(text.strip());
            }
        }
        return new ArrayList<>(result);
    }

    /**
     * 转换为 Long。
     *
     * @param value 原值
     * @return Long 值
     */
    private Long asLong(Object value) {
        if (value instanceof Number number) {
            return number.longValue();
        }
        if (value instanceof String text && hasText(text)) {
            try {
                return Long.parseLong(text.strip());
            } catch (NumberFormatException e) {
                return null;
            }
        }
        return null;
    }

    /**
     * 精确转换为 Long，拒绝小数和超出 Long 范围的数值。
     *
     * @param value 原值
     * @return 精确 Long 值
     */
    private Long asExactLong(Object value) {
        if (value instanceof Number number) {
            try {
                return new BigDecimal(number.toString()).longValueExact();
            } catch (NumberFormatException | ArithmeticException e) {
                return null;
            }
        }
        if (value instanceof String text && hasText(text)) {
            try {
                return Long.parseLong(text.strip());
            } catch (NumberFormatException e) {
                return null;
            }
        }
        return null;
    }

    /**
     * 转换为 Integer。
     *
     * @param value 原值
     * @return Integer 值
     */
    private Integer asInteger(Object value) {
        if (value instanceof Number number) {
            return number.intValue();
        }
        if (value instanceof String text && hasText(text)) {
            try {
                return Integer.parseInt(text.strip());
            } catch (NumberFormatException e) {
                return null;
            }
        }
        return null;
    }

    /**
     * 解析 ISO 日期。
     *
     * @param value 原值
     * @return 日期
     */
    private LocalDate parseDate(Object value) {
        String text = asString(value);
        if (!hasText(text)) {
            throw new BusinessException(PortfolioMessage.SCHEDULE_QUERY_DATE_INVALID_MESSAGE);
        }
        try {
            return LocalDate.parse(text);
        } catch (DateTimeParseException e) {
            throw new BusinessException(PortfolioMessage.SCHEDULE_QUERY_DATE_INVALID_MESSAGE);
        }
    }

    /**
     * 转换为字符串。
     *
     * @param value 原值
     * @return 字符串
     */
    private String asString(Object value) {
        return value == null ? "" : String.valueOf(value).strip();
    }

    /**
     * 默认字符串。
     *
     * @param value 原字符串
     * @return 非空字符串
     */
    private String defaultString(String value) {
        return value == null ? "" : value.strip();
    }

    /**
     * 默认字符串。
     *
     * @param value 原字符串
     * @param fallback 兜底值
     * @return 非空字符串
     */
    private String defaultString(String value, String fallback) {
        String normalized = defaultString(value);
        return normalized.isBlank() ? fallback : normalized;
    }

    /**
     * 判断字符串是否有内容。
     *
     * @param value 原字符串
     * @return true 表示有内容
     */
    private boolean hasText(String value) {
        return value != null && !value.isBlank();
    }

    /**
     * 空列表兜底。
     *
     * @param list 原列表
     * @param <T> 元素类型
     * @return 非空列表
     */
    private <T> List<T> safeList(List<T> list) {
        return list == null ? List.of() : list;
    }
}
