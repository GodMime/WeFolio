package com.jxc.wefolio.service;

import com.jxc.wefolio.dict.PortfolioComponentTypeDict;
import com.jxc.wefolio.dict.WorkStatusDict;
import com.jxc.wefolio.dto.PortfolioConfigDto;
import com.jxc.wefolio.dto.PortfolioRenderDto;
import com.jxc.wefolio.dto.VisitorPortfolioResponse;
import com.jxc.wefolio.entity.PortfolioEntity;
import com.jxc.wefolio.entity.WorkEntity;
import com.jxc.wefolio.mapper.WorkEntityMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

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
 * 作品集渲染服务 — 将草稿或正式配置展开为预览端和访客端共同消费的渲染模型。
 */
@Service
@RequiredArgsConstructor
public class PortfolioRenderService {

    /** 默认页面标题 */
    private static final String DEFAULT_TITLE = "个人作品集";

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

    /** 作品集展示标签标识配置键 */
    private static final String CONFIG_KEY_GROUP_KEY = "groupKey";

    /** 作品集展示标签名称配置键 */
    private static final String CONFIG_KEY_GROUP_NAME = "name";

    /** 排序配置键 */
    private static final String CONFIG_KEY_SORT_ORDER = "sortOrder";

    /** 组件标题配置键 */
    private static final String CONFIG_KEY_TITLE = "title";

    /** 说明配置键 */
    private static final String CONFIG_KEY_DESCRIPTION = "description";

    /** 文字内容配置键 */
    private static final String CONFIG_KEY_CONTENT = "content";

    /** 文字说明对齐配置键 */
    private static final String CONFIG_KEY_ALIGNMENT = "alignment";

    /** 分割线颜色配置键 */
    private static final String CONFIG_KEY_DIVIDER_COLOR = "color";

    /** 分割线高度配置键 */
    private static final String CONFIG_KEY_DIVIDER_HEIGHT_PX = "heightPx";

    /** 个人资料配置键 */
    private static final String CONFIG_KEY_PROFILE = "profile";

    /** 展示字段配置键 */
    private static final String CONFIG_KEY_VISIBLE_FIELDS = "visibleFields";

    /** 个人标签配置键 */
    private static final String CONFIG_KEY_TAGS = "tags";

    /** 头像字段 */
    private static final String PROFILE_KEY_AVATAR_URL = "avatarUrl";

    /** 展示名称字段 */
    private static final String PROFILE_KEY_DISPLAY_NAME = "displayName";

    /** 职业字段 */
    private static final String PROFILE_KEY_PROFESSION = "profession";

    /** 城市字段 */
    private static final String PROFILE_KEY_CITY = "city";

    /** 简介字段 */
    private static final String PROFILE_KEY_BIO = "bio";

    /** 微信二维码字段 */
    private static final String PROFILE_KEY_WECHAT_QR_URL = "wechatQrUrl";

    /** 标签名称字段 */
    private static final String TAG_KEY_NAME = "name";

    /** 标签颜色字段 */
    private static final String TAG_KEY_COLOR = "color";

    /** 档期查询范围配置键 */
    private static final String CONFIG_KEY_QUERY_RANGE = "queryRange";

    /** 档期查询展示方式配置键 */
    private static final String CONFIG_KEY_DISPLAY_MODE = "displayMode";

    /** 二维码来源配置键 */
    private static final String CONFIG_KEY_QR_URL_SOURCE = "qrUrlSource";

    /** 二维码地址配置键 */
    private static final String CONFIG_KEY_QR_URL = "qrUrl";

    /** 表单字段配置键 */
    private static final String CONFIG_KEY_FIELDS = "fields";

    /** 使用资料二维码 */
    private static final String QR_SOURCE_PROFILE = "PROFILE";

    /** 默认全部作品展示标签标识 */
    private static final String DEFAULT_GROUP_KEY_ALL = "g_all";

    /** 默认展示标签名称 */
    private static final String DEFAULT_GROUP_NAME = "全部作品";

    /** 默认展示标签排序值 */
    private static final int DEFAULT_GROUP_SORT_ORDER = 1000;

    /** 档期查询默认弹层月历展示方式 */
    private static final String SCHEDULE_DISPLAY_MODE_MODAL_CALENDAR = "MODAL_CALENDAR";

    /** 联系表单默认弹层展示方式 */
    private static final String CONTACT_FORM_DISPLAY_MODE_MODAL_FORM = "MODAL_FORM";

    /** 文字说明默认左对齐 */
    private static final String TEXT_SECTION_ALIGNMENT_LEFT = "LEFT";

    /** 分割线默认灰色 */
    private static final String DIVIDER_COLOR_GRAY = "GRAY";

    /** 分割线默认高度 */
    private static final int DEFAULT_DIVIDER_HEIGHT_PX = 16;

    /** 作品 Mapper */
    private final WorkEntityMapper workEntityMapper;

    /** COS 服务 */
    private final CosService cosService;

    /**
     * 构建作品集渲染模型。
     *
     * @param portfolio 作品集实体
     * @param config 配置
     * @param preview 是否预览
     * @param underMaintenance 是否维护中
     * @param maintenanceText 维护中文案
     * @param visitRecordId 访问记录 ID
     * @return 作品集渲染模型
     */
    public PortfolioRenderDto render(
            PortfolioEntity portfolio,
            PortfolioConfigDto config,
            boolean preview,
            boolean underMaintenance,
            VisitorPortfolioResponse.MaintenanceText maintenanceText,
            Long visitRecordId
    ) {
        PortfolioRenderDto render = new PortfolioRenderDto();
        render.setShareCode(portfolio == null ? "" : defaultString(portfolio.getShareCode()));
        render.setPortfolioId(portfolio == null ? null : portfolio.getId());
        render.setShare(copyShare(config == null ? null : config.getShare()));
        render.setTitle(resolveTitle(render.getShare()));
        render.setPreview(preview);
        render.setUnderMaintenance(underMaintenance);
        render.setMaintenanceText(maintenanceText);
        render.setVisitRecordId(visitRecordId);
        if (underMaintenance) {
            render.setComponents(List.of());
            return render;
        }
        render.setComponents(buildComponents(portfolio, config));
        return render;
    }

    /**
     * 构建渲染组件列表。
     *
     * @param portfolio 作品集实体
     * @param config 配置
     * @return 渲染组件列表
     */
    private List<PortfolioRenderDto.Component> buildComponents(PortfolioEntity portfolio, PortfolioConfigDto config) {
        Long ownerId = portfolio == null ? null : portfolio.getOwnerId();
        return safeList(config == null ? null : config.getComponents()).stream()
                .filter(component -> component != null && !Boolean.FALSE.equals(component.getEnabled()))
                .sorted(Comparator
                        .comparing(this::safeComponentSortOrder)
                        .thenComparing(component -> defaultString(component.getComponentKey())))
                .map(component -> buildComponent(ownerId, component))
                .toList();
    }

    /**
     * 构建单个渲染组件。
     *
     * @param ownerId 作品集归属用户 ID
     * @param component 配置组件
     * @return 渲染组件
     */
    private PortfolioRenderDto.Component buildComponent(Long ownerId, PortfolioConfigDto.Component component) {
        PortfolioRenderDto.Component render = new PortfolioRenderDto.Component();
        String componentTypeCode = defaultString(component.getComponentType());
        PortfolioComponentTypeDict componentType = PortfolioComponentTypeDict.fromCode(componentTypeCode);
        Map<String, Object> componentConfig = component.getConfig() == null ? Map.of() : component.getConfig();
        render.setComponentKey(defaultString(component.getComponentKey()));
        render.setComponentType(componentTypeCode);
        render.setName(componentType == null ? componentTypeCode : componentType.getDisplayName());
        render.setSortOrder(component.getSortOrder());
        render.setTitle(asString(componentConfig.get(CONFIG_KEY_TITLE)));
        if (componentType == null) {
            return render;
        }
        switch (componentType) {
            case CAROUSEL -> render.setWorks(buildWorks(ownerId, asLongList(componentConfig.get(CONFIG_KEY_WORK_IDS))));
            case PROFILE -> render.setProfile(buildProfile(componentConfig));
            case WORK_GRID, WORK_LIST -> {
                applyWorkDisplayOptions(render, componentConfig);
                render.setGroups(buildDisplayGroups(ownerId, componentConfig));
            }
            case SINGLE_WORK -> buildSingleWork(render, ownerId, componentConfig);
            case SCHEDULE_QUERY -> render.setScheduleQuery(buildScheduleQuery(componentConfig));
            case QR_CONTACT -> render.setQrContact(buildQrContact(componentConfig));
            case CONTACT_FORM -> render.setContactForm(buildContactForm(componentConfig));
            case TEXT_SECTION -> render.setTextSection(buildTextSection(componentConfig));
            case DIVIDER -> render.setDivider(buildDivider(componentConfig));
        }
        return render;
    }

    /**
     * 构建单个作品渲染数据。
     *
     * @param render 渲染组件
     * @param ownerId 作品集归属用户 ID
     * @param componentConfig 组件配置
     */
    private void buildSingleWork(
            PortfolioRenderDto.Component render,
            Long ownerId,
            Map<String, Object> componentConfig
    ) {
        applyWorkDisplayOptions(render, componentConfig);
        Long workId = asLong(componentConfig.get(CONFIG_KEY_WORK_ID));
        if (workId == null || workId <= 0L) {
            return;
        }
        List<PortfolioRenderDto.WorkItem> works = buildWorks(ownerId, List.of(workId));
        render.setWork(works.isEmpty() ? null : works.get(0));
    }

    /**
     * 应用作品标题和说明展示开关。
     *
     * @param render 渲染组件
     * @param componentConfig 组件配置
     */
    private void applyWorkDisplayOptions(
            PortfolioRenderDto.Component render,
            Map<String, Object> componentConfig
    ) {
        Object showTitle = componentConfig.get(CONFIG_KEY_SHOW_TITLE);
        render.setShowTitle(showTitle instanceof Boolean value ? value : Boolean.TRUE);
        Object showDescription = componentConfig.get(CONFIG_KEY_SHOW_DESCRIPTION);
        render.setShowDescription(showDescription instanceof Boolean value ? value : Boolean.FALSE);
    }

    /**
     * 构建作品集展示标签。
     *
     * @param ownerId 作品集归属用户 ID
     * @param componentConfig 组件配置
     * @return 展示标签列表
     */
    private List<PortfolioRenderDto.DisplayGroup> buildDisplayGroups(Long ownerId, Map<String, Object> componentConfig) {
        List<Map<String, Object>> groups = asMapList(componentConfig.get(CONFIG_KEY_GROUPS));
        if (groups.isEmpty()) {
            Map<String, Object> group = new LinkedHashMap<>();
            group.put(CONFIG_KEY_GROUP_KEY, DEFAULT_GROUP_KEY_ALL);
            group.put(CONFIG_KEY_GROUP_NAME, DEFAULT_GROUP_NAME);
            group.put(CONFIG_KEY_SORT_ORDER, DEFAULT_GROUP_SORT_ORDER);
            group.put(CONFIG_KEY_WORK_IDS, asLongList(componentConfig.get(CONFIG_KEY_WORK_IDS)));
            groups = List.of(group);
        }
        return groups.stream()
                .sorted(Comparator
                        .comparing(this::safeGroupSortOrder)
                        .thenComparing(group -> defaultString(asString(group.get(CONFIG_KEY_GROUP_KEY)))))
                .map(group -> {
                    PortfolioRenderDto.DisplayGroup displayGroup = new PortfolioRenderDto.DisplayGroup();
                    displayGroup.setGroupKey(asString(group.get(CONFIG_KEY_GROUP_KEY)));
                    displayGroup.setName(asString(group.get(CONFIG_KEY_GROUP_NAME)));
                    displayGroup.setSortOrder(asInteger(group.get(CONFIG_KEY_SORT_ORDER)));
                    displayGroup.setWorks(buildWorks(ownerId, asLongList(group.get(CONFIG_KEY_WORK_IDS))));
                    return displayGroup;
                })
                .toList();
    }

    /**
     * 构建作品展示项。
     *
     * @param ownerId 作品集归属用户 ID
     * @param workIds 作品 ID
     * @return 作品展示项
     */
    private List<PortfolioRenderDto.WorkItem> buildWorks(Long ownerId, List<Long> workIds) {
        Map<Long, WorkEntity> workMap = loadWorkMap(ownerId, workIds);
        return workIds.stream()
                .map(workMap::get)
                .filter(Objects::nonNull)
                .map(this::buildWorkItem)
                .toList();
    }

    /**
     * 读取作品映射。
     *
     * @param ownerId 作品集归属用户 ID
     * @param workIds 作品 ID
     * @return 作品映射
     */
    private Map<Long, WorkEntity> loadWorkMap(Long ownerId, List<Long> workIds) {
        if (workIds.isEmpty()) {
            return Map.of();
        }
        Set<Long> requestedIds = new LinkedHashSet<>(workIds);
        List<WorkEntity> works = safeList(workEntityMapper.selectBatchIds(requestedIds));
        Map<Long, WorkEntity> result = new LinkedHashMap<>();
        for (WorkEntity work : works) {
            if (work == null || work.getId() == null || !requestedIds.contains(work.getId())) {
                continue;
            }
            if (ownerId != null && !Objects.equals(ownerId, work.getUserId())) {
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
     * 构建作品展示项。
     *
     * @param work 作品实体
     * @return 作品展示项
     */
    private PortfolioRenderDto.WorkItem buildWorkItem(WorkEntity work) {
        PortfolioRenderDto.WorkItem item = new PortfolioRenderDto.WorkItem();
        item.setWorkId(work.getId());
        item.setTitle(defaultString(work.getTitle()));
        item.setMediaType(defaultString(work.getMediaType()));
        item.setMediaUrl(publicUrl(work.getMediaObjectKey()));
        item.setCoverUrl(hasText(work.getCoverObjectKey()) ? publicUrl(work.getCoverObjectKey()) : item.getMediaUrl());
        item.setDurationMs(work.getDurationMs());
        item.setDescription(defaultString(work.getDescription()));
        item.setAspectRatio(defaultString(work.getAspectRatio()));
        return item;
    }

    /**
     * 构建个人资料渲染数据。
     *
     * @param componentConfig 组件配置
     * @return 个人资料
     */
    private PortfolioRenderDto.Profile buildProfile(Map<String, Object> componentConfig) {
        Map<String, Object> profileConfig = asObjectMap(componentConfig.get(CONFIG_KEY_PROFILE));
        PortfolioRenderDto.Profile profile = new PortfolioRenderDto.Profile();
        profile.setAvatarUrl(asString(profileConfig.get(PROFILE_KEY_AVATAR_URL)));
        profile.setDisplayName(asString(profileConfig.get(PROFILE_KEY_DISPLAY_NAME)));
        profile.setProfession(asString(profileConfig.get(PROFILE_KEY_PROFESSION)));
        profile.setCity(asString(profileConfig.get(PROFILE_KEY_CITY)));
        profile.setBio(asString(profileConfig.get(PROFILE_KEY_BIO)));
        profile.setWechatQrUrl(asString(profileConfig.get(PROFILE_KEY_WECHAT_QR_URL)));
        profile.setVisibleFields(asObjectMap(componentConfig.get(CONFIG_KEY_VISIBLE_FIELDS)));
        profile.setTags(buildTags(profileConfig.get(CONFIG_KEY_TAGS)));
        return profile;
    }

    /**
     * 构建个人标签。
     *
     * @param value 原始标签
     * @return 标签列表
     */
    private List<PortfolioRenderDto.Tag> buildTags(Object value) {
        return asMapList(value).stream().map(item -> {
            PortfolioRenderDto.Tag tag = new PortfolioRenderDto.Tag();
            tag.setName(asString(item.get(TAG_KEY_NAME)));
            tag.setColor(asString(item.get(TAG_KEY_COLOR)));
            return tag;
        }).filter(item -> hasText(item.getName())).toList();
    }

    /**
     * 构建档期查询渲染数据。
     *
     * @param componentConfig 组件配置
     * @return 档期查询
     */
    private PortfolioRenderDto.ScheduleQuery buildScheduleQuery(Map<String, Object> componentConfig) {
        PortfolioRenderDto.ScheduleQuery scheduleQuery = new PortfolioRenderDto.ScheduleQuery();
        scheduleQuery.setTitle(asString(componentConfig.get(CONFIG_KEY_TITLE)));
        scheduleQuery.setDescription(asString(componentConfig.get(CONFIG_KEY_DESCRIPTION)));
        scheduleQuery.setDisplayMode(defaultString(
                asString(componentConfig.get(CONFIG_KEY_DISPLAY_MODE)),
                SCHEDULE_DISPLAY_MODE_MODAL_CALENDAR
        ));
        scheduleQuery.setQueryRange(asObjectMap(componentConfig.get(CONFIG_KEY_QUERY_RANGE)));
        return scheduleQuery;
    }

    /**
     * 构建二维码联系渲染数据。
     *
     * @param componentConfig 组件配置
     * @return 二维码联系
     */
    private PortfolioRenderDto.QrContact buildQrContact(Map<String, Object> componentConfig) {
        PortfolioRenderDto.QrContact qrContact = new PortfolioRenderDto.QrContact();
        String source = defaultString(asString(componentConfig.get(CONFIG_KEY_QR_URL_SOURCE)), QR_SOURCE_PROFILE);
        qrContact.setTitle(asString(componentConfig.get(CONFIG_KEY_TITLE)));
        qrContact.setDescription(asString(componentConfig.get(CONFIG_KEY_DESCRIPTION)));
        qrContact.setQrUrlSource(source);
        qrContact.setQrUrl(asString(componentConfig.get(CONFIG_KEY_QR_URL)));
        return qrContact;
    }

    /**
     * 构建联系表单渲染数据。
     *
     * @param componentConfig 组件配置
     * @return 联系表单
     */
    private PortfolioRenderDto.ContactForm buildContactForm(Map<String, Object> componentConfig) {
        PortfolioRenderDto.ContactForm contactForm = new PortfolioRenderDto.ContactForm();
        contactForm.setTitle(asString(componentConfig.get(CONFIG_KEY_TITLE)));
        contactForm.setDescription(asString(componentConfig.get(CONFIG_KEY_DESCRIPTION)));
        contactForm.setDisplayMode(defaultString(
                asString(componentConfig.get(CONFIG_KEY_DISPLAY_MODE)),
                CONTACT_FORM_DISPLAY_MODE_MODAL_FORM
        ));
        contactForm.setFields(asStringList(componentConfig.get(CONFIG_KEY_FIELDS)));
        return contactForm;
    }

    /**
     * 构建文字说明渲染数据。
     *
     * @param componentConfig 组件配置
     * @return 文字说明
     */
    private PortfolioRenderDto.TextSection buildTextSection(Map<String, Object> componentConfig) {
        PortfolioRenderDto.TextSection textSection = new PortfolioRenderDto.TextSection();
        textSection.setTitle(asString(componentConfig.get(CONFIG_KEY_TITLE)));
        textSection.setContent(asString(componentConfig.get(CONFIG_KEY_CONTENT)));
        textSection.setAlignment(defaultString(
                asString(componentConfig.get(CONFIG_KEY_ALIGNMENT)),
                TEXT_SECTION_ALIGNMENT_LEFT
        ));
        return textSection;
    }

    /**
     * 构建分割线渲染数据。
     *
     * @param componentConfig 组件配置
     * @return 分割线
     */
    private PortfolioRenderDto.Divider buildDivider(Map<String, Object> componentConfig) {
        PortfolioRenderDto.Divider divider = new PortfolioRenderDto.Divider();
        divider.setColor(defaultString(
                asString(componentConfig.get(CONFIG_KEY_DIVIDER_COLOR)),
                DIVIDER_COLOR_GRAY
        ));
        Integer heightPx = asInteger(componentConfig.get(CONFIG_KEY_DIVIDER_HEIGHT_PX));
        divider.setHeightPx(heightPx == null || heightPx <= 0 ? DEFAULT_DIVIDER_HEIGHT_PX : heightPx);
        return divider;
    }

    /**
     * 复制分享信息。
     *
     * @param share 原分享信息
     * @return 分享信息
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
     * 解析页面标题。
     *
     * @param share 分享信息
     * @return 页面标题
     */
    private String resolveTitle(PortfolioConfigDto.Share share) {
        if (share != null && hasText(share.getTitle())) {
            return share.getTitle();
        }
        return DEFAULT_TITLE;
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
                result.add(text);
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
     * 转换为字符串。
     *
     * @param value 原值
     * @return 字符串
     */
    private String asString(Object value) {
        return value == null ? "" : String.valueOf(value).strip();
    }

    /**
     * 生成 COS 公开地址。
     *
     * @param objectKey 对象键
     * @return 公开地址
     */
    private String publicUrl(String objectKey) {
        return hasText(objectKey) ? cosService.publicUrl(objectKey) : "";
    }

    /**
     * 安全组件排序值。
     *
     * @param component 组件
     * @return 排序值
     */
    private int safeComponentSortOrder(PortfolioConfigDto.Component component) {
        return component == null || component.getSortOrder() == null ? Integer.MAX_VALUE : component.getSortOrder();
    }

    /**
     * 安全展示标签排序值。
     *
     * @param group 展示标签
     * @return 排序值
     */
    private int safeGroupSortOrder(Map<String, Object> group) {
        Integer sortOrder = asInteger(group.get(CONFIG_KEY_SORT_ORDER));
        return sortOrder == null ? Integer.MAX_VALUE : sortOrder;
    }

    /**
     * 默认字符串。
     *
     * @param value 原值
     * @return 非空字符串
     */
    private String defaultString(String value) {
        return value == null ? "" : value.strip();
    }

    /**
     * 默认字符串。
     *
     * @param value 原值
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
     * @param value 原值
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
