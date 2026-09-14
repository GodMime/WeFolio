package com.jxc.wefolio.service;

import com.jxc.wefolio.dict.PortfolioComponentTypeDict;
import com.jxc.wefolio.exception.BusinessException;
import com.jxc.wefolio.message.PortfolioMessage;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** 组件展示选项的共享契约及字段版本保护。 */
public final class PortfolioComponentDisplayOptionsSupport {
    /** 轮播样式键。 */
    public static final String DISPLAY_STYLE = "displayStyle";
    /** 描述开关键。 */
    public static final String SHOW_DESCRIPTION = "showDescription";
    /** 标题开关键。 */
    public static final String SHOW_TITLE = "showTitle";
    /** 视频轮播组件标题开关键，与作品标题开关独立。 */
    public static final String SHOW_COMPONENT_TITLE = "showComponentTitle";
    /** 单作打开方式键。 */
    public static final String OPEN_MODE = "openMode";
    /** 详情选项键。 */
    public static final String DETAIL_OPTIONS = "detailOptions";
    /** 兼容的堆叠轮播。 */
    public static final String STACKED = "STACKED";
    /** 竖向横滑卡片。 */
    public static final String PORTRAIT_CARDS = "PORTRAIT_CARDS";
    /** 原地打开作品。 */
    public static final String INLINE = "INLINE";
    /** 来源快照详情页。 */
    public static final String DETAIL_PAGE = "DETAIL_PAGE";
    /** 字段引入版本按编辑器分别登记，后续能力只增加规则，不修改合并器阈值。 */
    private static final List<FieldRule> FIELD_RULES = List.of(
            new FieldRule(PortfolioComponentTypeDict.VIDEO_CAROUSEL.getCode(), DISPLAY_STYLE, 6, 5),
            new FieldRule(PortfolioComponentTypeDict.VIDEO_CAROUSEL.getCode(), SHOW_DESCRIPTION, 6, 5),
            new FieldRule(PortfolioComponentTypeDict.VIDEO_CAROUSEL.getCode(), SHOW_COMPONENT_TITLE, 14, 10),
            new FieldRule(PortfolioComponentTypeDict.SINGLE_WORK.getCode(), OPEN_MODE, 6, 5),
            new FieldRule(PortfolioComponentTypeDict.SINGLE_WORK.getCode(), DETAIL_OPTIONS, 6, 5),
            new FieldRule(PortfolioComponentTypeDict.TEXT_GRID.getCode(), PortfolioTextGridConfigNormalizer.CELL_BORDER_WIDTH_RPX, 7, 6),
            new FieldRule(PortfolioComponentTypeDict.TEXT_GRID.getCode(), PortfolioTextGridConfigNormalizer.CELL_BORDER_COLOR, 7, 6),
            new FieldRule(PortfolioComponentTypeDict.PROFILE.getCode(), PortfolioProfileConfigSupport.PROFILE_LAYOUT, 9, 8),
            new FieldRule(PortfolioComponentTypeDict.PROFILE.getCode(), PortfolioProfileConfigSupport.PROFILE_BORDER, 10, 8),
            new FieldRule(PortfolioComponentTypeDict.PROFILE.getCode(), PortfolioProfileConfigSupport.PROFILE_BORDER_WIDTH_RPX, 10, 8),
            new FieldRule(PortfolioComponentTypeDict.PROFILE.getCode(), PortfolioProfileConfigSupport.PROFILE_BORDER_COLOR, 10, 8),
            new FieldRule(PortfolioComponentTypeDict.PROFILE.getCode(), PortfolioProfileConfigSupport.PROFILE_HORIZONTAL_MARGIN_RPX, 11, 8),
            new FieldRule(PortfolioComponentTypeDict.PROFILE.getCode(), PortfolioProfileConfigSupport.PROFILE_VERTICAL_MARGIN_RPX, 11, 8),
            new FieldRule(PortfolioComponentTypeDict.TEXT_GRID.getCode(), PortfolioTextGridConfigNormalizer.HORIZONTAL_MARGIN_RPX, 9, 8),
            new FieldRule(PortfolioComponentTypeDict.TEXT_GRID.getCode(), PortfolioTextGridConfigNormalizer.VERTICAL_MARGIN_RPX, 9, 8),
            new FieldRule(PortfolioComponentTypeDict.CONTACT_INFO.getCode(), PortfolioContactInfoConfigSupport.CONTACT_BORDER, 13, 9),
            new FieldRule(PortfolioComponentTypeDict.CONTACT_INFO.getCode(), PortfolioContactInfoConfigSupport.CONTACT_BORDER_WIDTH_RPX, 13, 9),
            new FieldRule(PortfolioComponentTypeDict.CONTACT_INFO.getCode(), PortfolioContactInfoConfigSupport.CONTACT_BORDER_COLOR, 13, 9),
            new FieldRule(PortfolioComponentTypeDict.CONTACT_INFO.getCode(), PortfolioContactInfoConfigSupport.HORIZONTAL_MARGIN_RPX, 13, 9),
            new FieldRule(PortfolioComponentTypeDict.CONTACT_INFO.getCode(), PortfolioContactInfoConfigSupport.VERTICAL_MARGIN_RPX, 13, 9));

    /** 字段规则所对应的编辑器。 */
    public enum EditorType { PERSONAL, TEAM }

    /** 单个字段及其在两个编辑器中的引入版本。 */
    record FieldRule(String componentType, String field, int personalRevision, int teamRevision) {
        /** 按当前编辑器选择字段自身的引入版本。 */
        int introducedAtRevision(EditorType editorType) {
            return editorType == EditorType.PERSONAL ? personalRevision : teamRevision;
        }
    }
    /** 不实例化工具类。 */
    private PortfolioComponentDisplayOptionsSupport() { }
    /** 缺省保持堆叠，显式非法值拒绝。 */
    public static String displayStyle(Map<String, Object> source) {
        return choice(source.get(DISPLAY_STYLE), STACKED, PORTRAIT_CARDS);
    }
    /** 缺省保持原地打开。 */
    public static String openMode(Map<String, Object> source) { return choice(source.get(OPEN_MODE), INLINE, DETAIL_PAGE); }
    /** 描述缺省关闭；新增字段非法类型拒绝。 */
    public static boolean showDescription(Map<String, Object> source) { return bool(source.get(SHOW_DESCRIPTION), false); }
    /** 组件标题缺省开启，兼容历史配置；关闭时仍保留标题文字。 */
    public static boolean showComponentTitle(Map<String, Object> source) { return bool(source.get(SHOW_COMPONENT_TITLE), true); }
    /** 详情选项独立默认开启，切回原地展示时仍保留。 */
    public static Map<String, Object> detailOptions(Map<String, Object> source) {
        Object raw = source.get(DETAIL_OPTIONS);
        if (raw != null && !(raw instanceof Map<?, ?>)) { throw invalid(); }
        Map<?, ?> options = raw == null ? Map.of() : (Map<?, ?>) raw;
        return new LinkedHashMap<>(Map.of(SHOW_TITLE, bool(options.get(SHOW_TITLE), true),
                SHOW_DESCRIPTION, bool(options.get(SHOW_DESCRIPTION), true)));
    }
    /** 仅复制旧请求中仍存在且类型相同组件的新字段，允许已知组件删除及移动。 */
    public static void protect(Map<String, Object> target, Map<String, Object> existing, String type,
            EditorType editorType, int incomingRevision) {
        protect(target, existing, type, editorType, incomingRevision, FIELD_RULES);
    }

    /** 逐条应用能力规则；允许不同字段在不同版本引入。 */
    static void protect(Map<String, Object> target, Map<String, Object> existing, String type,
            EditorType editorType, int incomingRevision, List<FieldRule> rules) {
        if (target == null || existing == null) { return; }
        for (FieldRule rule : rules) {
            if (!rule.componentType().equals(type) || incomingRevision >= rule.introducedAtRevision(editorType)) {
                continue;
            }
            String field = rule.field();
            if (existing.containsKey(field)) {
                target.put(field, copyValue(existing.get(field)));
            } else { target.remove(field); }
        }
    }

    /** 配置值递归复制，避免保留字段与数据库草稿共享嵌套对象。 */
    private static Object copyValue(Object value) {
        if (value instanceof Map<?, ?> map) {
            Map<Object, Object> copy = new LinkedHashMap<>();
            map.forEach((key, item) -> copy.put(key, copyValue(item)));
            return copy;
        }
        if (value instanceof List<?> list) {
            List<Object> copy = new ArrayList<>();
            list.forEach(item -> copy.add(copyValue(item)));
            return copy;
        }
        return value;
    }
    /** 严格枚举解析。 */
    private static String choice(Object value, String fallback, String alternative) {
        if (value == null) { return fallback; }
        if (fallback.equals(value) || alternative.equals(value)) { return (String) value; }
        throw invalid();
    }
    /** 严格布尔解析。 */
    private static boolean bool(Object value, boolean fallback) {
        if (value == null) { return fallback; }
        if (value instanceof Boolean result) { return result; }
        throw invalid();
    }
    /** 展示配置受控异常。 */
    private static BusinessException invalid() { return new BusinessException(PortfolioMessage.COMPONENT_DISPLAY_OPTIONS_INVALID); }
}
