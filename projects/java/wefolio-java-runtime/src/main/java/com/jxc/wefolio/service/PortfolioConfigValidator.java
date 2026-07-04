package com.jxc.wefolio.service;

import com.jxc.wefolio.dict.MediaTypeDict;
import com.jxc.wefolio.dict.PortfolioComponentTypeDict;
import com.jxc.wefolio.dict.ReferenceTypeDict;
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

/**
 * 作品集配置校验器 — 负责标准个人作品集组件规则和引用构建。
 */
@Service
@RequiredArgsConstructor
public class PortfolioConfigValidator {

    /** 轮播图最大作品数量 */
    private static final int CAROUSEL_WORK_MAX_COUNT = 9;

    /** 双列作品列表最大作品数量 */
    private static final int WORK_GRID_MAX_COUNT = 50;

    /** 单列作品列表最大作品数量 */
    private static final int WORK_LIST_MAX_COUNT = 50;

    /** 默认排序间隔 */
    private static final int DEFAULT_SORT_ORDER_STEP = 1000;

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
        if (userId == null) {
            throw new BusinessException(PortfolioMessage.USER_REQUIRED_MESSAGE);
        }
        if (config == null) {
            throw new BusinessException(PortfolioMessage.PORTFOLIO_CONFIG_REQUIRED_MESSAGE);
        }
        if (!PortfolioConfigDto.SCHEMA_VERSION_STANDARD_PERSONAL_V1.equals(config.getSchemaVersion())) {
            throw new BusinessException(PortfolioMessage.PORTFOLIO_CONFIG_VERSION_UNSUPPORTED_MESSAGE);
        }
        List<PortfolioConfigDto.Component> enabledComponents = safeList(config.getComponents()).stream()
                .filter(component -> Boolean.TRUE.equals(component.getEnabled()))
                .map(this::copyComponent)
                .sorted(Comparator
                        .comparing(this::safeSortOrder)
                        .thenComparing(component -> defaultString(component.getComponentKey())))
                .toList();
        if (enabledComponents.isEmpty()) {
            throw new BusinessException(PortfolioMessage.ENABLED_COMPONENT_REQUIRED_MESSAGE);
        }

        List<PortfolioConfigDto.Component> normalizedComponents = new ArrayList<>();
        Set<String> componentKeys = new LinkedHashSet<>();
        int index = 0;
        for (PortfolioConfigDto.Component component : enabledComponents) {
            String key = defaultString(component.getComponentKey());
            if (key.isBlank()) {
                key = "c_" + (index + 1);
                component.setComponentKey(key);
            }
            if (!componentKeys.add(key)) {
                throw new BusinessException(PortfolioMessage.COMPONENT_KEY_DUPLICATE_MESSAGE);
            }
            component.setSortOrder((index + 1) * DEFAULT_SORT_ORDER_STEP);
            validateComponent(userId, component);
            normalizedComponents.add(component);
            index++;
        }

        PortfolioConfigDto normalized = new PortfolioConfigDto();
        normalized.setSchemaVersion(config.getSchemaVersion());
        normalized.setShare(copyShare(config.getShare()));
        normalized.setComponents(normalizedComponents);
        return normalized;
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
        List<PortfolioConfigDto.Component> components = safeList(config == null ? null : config.getComponents());
        for (int componentIndex = 0; componentIndex < components.size(); componentIndex++) {
            PortfolioConfigDto.Component component = components.get(componentIndex);
            PortfolioComponentTypeDict componentType = PortfolioComponentTypeDict.fromCode(component.getComponentType());
            if (componentType == null) {
                continue;
            }
            String componentPath = "components[" + componentIndex + "]";
            switch (componentType) {
                case PROFILE -> references.add(reference(
                        portfolioId,
                        configScope,
                        ReferenceTypeDict.USER_PROFILE.getCode(),
                        ownerId,
                        component,
                        componentPath,
                        0
                ));
                case SCHEDULE_QUERY -> references.add(reference(
                        portfolioId,
                        configScope,
                        ReferenceTypeDict.SCHEDULE_COMPONENT.getCode(),
                        ownerId,
                        component,
                        componentPath,
                        0
                ));
                case QR_CONTACT -> references.add(reference(
                        portfolioId,
                        configScope,
                        ReferenceTypeDict.QR_CODE_ASSET.getCode(),
                        ownerId,
                        component,
                        componentPath,
                        0
                ));
                case CAROUSEL -> addFlatWorkReferences(references, portfolioId, configScope, component, componentPath);
                case WORK_GRID, WORK_LIST -> addGroupedWorkReferences(references, portfolioId, configScope, component, componentPath);
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
        component.getConfig().put(CONFIG_KEY_GROUPS, groups);
        component.getConfig().put(CONFIG_KEY_COLUMNS, columns);
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
        component.getConfig().put(CONFIG_KEY_CONTENT, content);
        component.getConfig().put(CONFIG_KEY_ALIGNMENT, alignment);
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
                    componentPath + ".workIds[" + index + "]",
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
                        componentPath + "." + CONFIG_KEY_GROUPS + "[" + groupIndex + "]."
                                + CONFIG_KEY_WORK_IDS + "[" + workIndex + "]",
                        workIndex
                ));
            }
        }
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
