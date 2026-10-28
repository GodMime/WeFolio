package com.jxc.wefolio.service;

import com.alibaba.fastjson2.JSON;
import com.fasterxml.jackson.databind.JsonNode;
import com.jxc.wefolio.exception.BusinessException;
import com.jxc.wefolio.message.PortfolioTimelineMessage;
import java.time.DateTimeException;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/** 个人与团队时间轴共用的严格配置规则、字体继承和节点标记展示。 */
public final class PortfolioTimelineConfigSupport {
    /** 固定组件编码。 */
    public static final String COMPONENT_TYPE = "TIMELINE";
    /** 最大节点数量。 */
    public static final int MAX_NODES = 20;
    /** 组件与节点标题码点上限。 */
    public static final int MAX_TITLE_CODE_POINTS = 60;
    /** 节点描述码点上限。 */
    public static final int MAX_DESCRIPTION_CODE_POINTS = 1000;
    /** 自定义节点标记码点上限。 */
    public static final int MAX_CUSTOM_LABEL_CODE_POINTS = 20;
    /** 全轴原始标题、描述与自定义节点标记的码点总量。 */
    public static final int MAX_TOTAL_TEXT_CODE_POINTS = 6000;
    /** 时间轴固定字段、枚举和字体语义。 */
    public static final String VARIANT = "variant";
    public static final String DIRECTION = "direction";
    public static final String TITLE = "title";
    public static final String SHOW_KICKER = "showKicker";
    public static final String MIN_GAP_RPX = "minGapRpx";
    public static final String HORIZONTAL_GAP = "horizontal";
    public static final String VERTICAL_GAP = "vertical";
    public static final String HEADING = "heading";
    public static final String DATE = "date";
    public static final String DESCRIPTION = "description";
    public static final String DATE_PRECISION = "datePrecision";
    public static final String FONT_FAMILY = "fontFamily";
    public static final String FONT_ID = "fontId";
    public static final String FONT_SIZE_RPX = "fontSizeRpx";
    public static final String COLOR = "color";
    public static final String SYSTEM = "SYSTEM";
    public static final String WECHAT_SANS_SS = "WECHAT_SANS_SS";
    public static final String AUTO = "AUTO";
    public static final String MINIMAL = "MINIMAL";
    public static final String DATE_COLUMN = "DATE_COLUMN";
    public static final String MILESTONE_CARD = "MILESTONE_CARD";
    public static final String HORIZONTAL = "HORIZONTAL";
    public static final String VERTICAL = "VERTICAL";
    public static final String YEAR = "YEAR";
    public static final String MONTH = "MONTH";
    public static final String DAY = "DAY";
    /** 自定义标记沿用日期字段保存原文。 */
    public static final String CUSTOM = "CUSTOM";
    public static final String CONTENT = "content";
    public static final String NORMAL = "NORMAL";
    public static final String BOLD = "BOLD";
    public static final String LINE_HEIGHT = "lineHeight";
    public static final String FONT_WEIGHT = "fontWeight";
    public static final String CONFIG = "config";
    public static final String TOTAL_TEXT = "totalText";

    /** 配置层闭合字段。 */
    public static final Set<String> CONFIG_FIELDS = Set.of(VARIANT, DIRECTION, TITLE, SHOW_KICKER, MIN_GAP_RPX, "textStyles", "nodes");
    /** 节点层闭合字段。 */
    public static final Set<String> NODE_FIELDS = Set.of("nodeKey", DATE_PRECISION, DATE, TITLE, DESCRIPTION, "textStyles");
    /** 字体样式只存实际覆盖字段。 */
    public static final Set<String> STYLE_FIELDS = Set.of(FONT_FAMILY, FONT_ID, FONT_SIZE_RPX, COLOR);
    /** 组件统一文字角色。 */
    public static final List<String> ROLES = List.of(HEADING, DATE, TITLE, DESCRIPTION);
    /** 节点可覆盖的文字角色。 */
    public static final List<String> NODE_ROLES = List.of(DATE, TITLE, DESCRIPTION);
    /** 时间轴样式编号。 */
    private static final Set<String> VARIANTS = Set.of(MINIMAL, DATE_COLUMN, MILESTONE_CARD);
    /** 时间轴方向。 */
    private static final Set<String> DIRECTIONS = Set.of(HORIZONTAL, VERTICAL);
    /** 支持的日期精度与自定义节点标记。 */
    private static final Set<String> PRECISIONS = Set.of(YEAR, MONTH, DAY, CUSTOM);
    /** 自定义标记为单行文字，不接受控制字符或行分隔符。 */
    private static final Pattern CUSTOM_LABEL_FORBIDDEN_PATTERN = Pattern.compile("[\\x00-\\x1F\\x7F\\x{0085}\\x{2028}\\x{2029}]");
    /** JavaScript trim 将字节顺序标记作为空白处理。 */
    private static final int BYTE_ORDER_MARK = 0xFEFF;
    /** 内置字体取值。 */
    private static final Set<String> FAMILIES = Set.of(SYSTEM, WECHAT_SANS_SS);
    /** 字体标识与既有字体协议一致。 */
    private static final Pattern FONT_ID_PATTERN = Pattern.compile("[A-Z][A-Z0-9_]{0,63}");
    /** 自动色或六位颜色。 */
    private static final Pattern COLOR_PATTERN = Pattern.compile("AUTO|#[0-9a-fA-F]{6}");
    /** 固定元数据名称，统一复用以免字体合并误识别。 */
    public static final String TEXT_STYLES = "textStyles", NODES = "nodes", NODE_KEY = "nodeKey";
    /** 工具类不能实例化。 */
    private PortfolioTimelineConfigSupport() { }

    /** 高级原始树在普通 DTO 转换前复用同一严格检查。 */
    public static void validate(JsonNode config) {
        if (config == null || !config.isObject()) { throw invalid(CONFIG); }
        normalize(JSON.parseObject(config.toString()));
    }

    /** 校验并生成不包含布局结果的独立规范配置。 */
    public static Map<String, Object> normalize(Map<String, Object> source) {
        Map<String, Object> input = object(source, CONFIG_FIELDS, CONFIG);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put(VARIANT, option(input, VARIANT, VARIANTS, MINIMAL, VARIANT));
        result.put(DIRECTION, option(input, DIRECTION, DIRECTIONS, HORIZONTAL, DIRECTION));
        String heading = text(input, TITLE, "", MAX_TITLE_CODE_POINTS, TITLE);
        result.put(TITLE, heading);
        Object kicker = input.getOrDefault(SHOW_KICKER, Boolean.TRUE);
        if (!(kicker instanceof Boolean)) { throw invalid(SHOW_KICKER); }
        result.put(SHOW_KICKER, kicker);
        Map<String, Object> gaps = input.containsKey(MIN_GAP_RPX)
                ? object(input.get(MIN_GAP_RPX), Set.of(HORIZONTAL_GAP, VERTICAL_GAP), MIN_GAP_RPX) : Map.of();
        result.put(MIN_GAP_RPX, new LinkedHashMap<>(Map.of(
                HORIZONTAL_GAP, integer(gaps, HORIZONTAL_GAP, 320, 120, 640, "minGapRpx.horizontal"),
                VERTICAL_GAP, integer(gaps, VERTICAL_GAP, 200, 120, 640, "minGapRpx.vertical"))));
        result.put(TEXT_STYLES, input.containsKey(TEXT_STYLES)
                ? styles(input.get(TEXT_STYLES), ROLES, TEXT_STYLES) : new LinkedHashMap<>());
        if (!(input.get(NODES) instanceof List<?> nodes) || nodes.isEmpty() || nodes.size() > MAX_NODES) {
            throw invalid(NODES);
        }
        List<Map<String, Object>> normalized = new ArrayList<>();
        Set<String> identities = new HashSet<>();
        int total = codePoints(heading);
        for (int index = 0; index < nodes.size(); index++) {
            String path = NODES + "[" + index + "]";
            Map<String, Object> node = object(nodes.get(index), NODE_FIELDS, path);
            String key = text(node, NODE_KEY, null, 64, path + ".nodeKey");
            if (key.isBlank() || !identities.add(key)) { throw invalid(path + ".nodeKey"); }
            String precision = option(node, DATE_PRECISION, PRECISIONS, null, path + ".datePrecision");
            String date = text(node, DATE, null, CUSTOM.equals(precision) ? MAX_CUSTOM_LABEL_CODE_POINTS : 10, path + ".date");
            requireDate(precision, date, path + ".date");
            String title = text(node, TITLE, "", MAX_TITLE_CODE_POINTS, path + ".title");
            String description = text(node, DESCRIPTION, "", MAX_DESCRIPTION_CODE_POINTS, path + ".description");
            if (title.isBlank() && description.isBlank()) { throw invalid(path + ".content"); }
            total += codePoints(title) + codePoints(description) + (CUSTOM.equals(precision) ? codePoints(date) : 0);
            Map<String, Object> value = new LinkedHashMap<>();
            value.put(NODE_KEY, key); value.put(DATE_PRECISION, precision); value.put(DATE, date);
            value.put(TITLE, title); value.put(DESCRIPTION, description);
            if (node.containsKey(TEXT_STYLES)) { value.put(TEXT_STYLES, styles(node.get(TEXT_STYLES), NODE_ROLES, path + ".textStyles")); }
            normalized.add(value);
        }
        if (total > MAX_TOTAL_TEXT_CODE_POINTS) { throw invalid(TOTAL_TEXT); }
        result.put(NODES, normalized);
        return result;
    }

    /** 日期不经过时区或时间戳，始终显示规范的点分隔形式。 */
    public static String displayDate(String date) { return date.replace('-', '.'); }

    /** 自定义标记保留空格与标点原文，日期继续使用原有点分隔格式。 */
    public static String displayDate(String precision, String date) { return CUSTOM.equals(precision) ? date : displayDate(date); }

    /** 解析默认、统一与节点补丁；字体选择作为一个原子覆盖。 */
    public static Map<String, Object> effectiveStyle(Map<String, Object> config, Map<String, Object> node, String role) {
        String variant = String.valueOf(config.getOrDefault(VARIANT, MINIMAL));
        int size = switch (role) {
            case HEADING -> 44;
            case DATE -> variant.equals(DATE_COLUMN) ? 40 : 24;
            case TITLE -> variant.equals(DATE_COLUMN) ? 30 : variant.equals(MILESTONE_CARD) ? 26 : 28;
            default -> variant.equals(MINIMAL) ? 24 : 22;
        };
        boolean bold = role.equals(HEADING) || role.equals(TITLE) || role.equals(DATE) && !variant.equals(DATE_COLUMN);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put(FONT_FAMILY, SYSTEM); result.put(FONT_SIZE_RPX, size); result.put(COLOR, AUTO);
        result.put(FONT_WEIGHT, bold ? BOLD : NORMAL);
        result.put(LINE_HEIGHT, role.equals(DESCRIPTION) ? 1.85 : role.equals(TITLE) ? 1.65 : 1.4);
        mergeStyle(result, rolePatch(config, role));
        if (node != null) { mergeStyle(result, rolePatch(node, role)); }
        return result;
    }

    /** 枚举原始角色补丁，值保留原引用供版本表合并，不生成继承字段。 */
    public static Map<String, Map<String, Object>> rawStyleNodes(Map<String, Object> config) {
        Map<String, Map<String, Object>> result = new LinkedHashMap<>();
        addRawStyles(result, "", config, ROLES);
        if (config.get(NODES) instanceof List<?> nodes) {
            for (Object value : nodes) {
                if (value instanceof Map<?, ?> raw) {
                    Map<String, Object> node = cast(raw);
                    addRawStyles(result, node.get(NODE_KEY) + ":", node, NODE_ROLES);
                }
            }
        }
        return result;
    }

    /** 将实际展示片段适配为既有字体聚合器的逻辑需求。 */
    public static List<Map<String, Object>> fontDemandNodes(Map<String, Object> config) {
        List<Map<String, Object>> result = new ArrayList<>();
        demand(result, config, null, HEADING, String.valueOf(config.getOrDefault(TITLE, "")));
        if (config.get(NODES) instanceof List<?> nodes) {
            for (Object value : nodes) {
                if (!(value instanceof Map<?, ?> raw)) { continue; }
                Map<String, Object> node = cast(raw);
                demand(result, config, node, DATE, displayDate(String.valueOf(node.get(DATE_PRECISION)), String.valueOf(node.get(DATE))));
                demand(result, config, node, TITLE, String.valueOf(node.getOrDefault(TITLE, "")));
                demand(result, config, node, DESCRIPTION, String.valueOf(node.getOrDefault(DESCRIPTION, "")));
            }
        }
        return result;
    }

    /** 不可见空文字不生成空字体组。 */
    private static void demand(List<Map<String, Object>> result, Map<String, Object> config, Map<String, Object> node, String role, String text) {
        if (text.isEmpty()) { return; }
        Map<String, Object> style = effectiveStyle(config, node, role);
        if (!style.containsKey(FONT_ID)) { return; }
        style.put(CONTENT, text); result.add(style);
    }
    /** 显式内置选择删除上级远程字体，空补丁继续继承。 */
    private static void mergeStyle(Map<String, Object> output, Map<String, Object> patch) {
        if (patch.containsKey(FONT_FAMILY) || patch.containsKey(FONT_ID)) { output.remove(FONT_ID); output.remove(FONT_FAMILY); }
        output.putAll(patch);
    }
    /** 读取现有补丁，不生成新的持久对象。 */
    private static Map<String, Object> rolePatch(Map<String, Object> holder, String role) {
        if (holder.get(TEXT_STYLES) instanceof Map<?, ?> styles && styles.get(role) instanceof Map<?, ?> patch) { return cast(patch); }
        return Map.of();
    }
    /** 只枚举实际存在的角色对象。 */
    private static void addRawStyles(Map<String, Map<String, Object>> output, String prefix, Map<String, Object> holder, List<String> roles) {
        if (holder.get(TEXT_STYLES) instanceof Map<?, ?> styles) {
            for (String role : roles) { if (styles.get(role) instanceof Map<?, ?> patch) { output.put(prefix + role, cast(patch)); } }
        }
    }
    /** 文字样式严格校验，空对象保持空对象。 */
    private static Map<String, Object> styles(Object raw, List<String> roles, String path) {
        Map<String, Object> source = object(raw, Set.copyOf(roles), path);
        Map<String, Object> result = new LinkedHashMap<>();
        for (String role : roles) {
            if (!source.containsKey(role)) { continue; }
            String field = path + "." + role;
            Map<String, Object> patch = object(source.get(role), STYLE_FIELDS, field);
            Map<String, Object> value = new LinkedHashMap<>();
            if (patch.containsKey(FONT_FAMILY)) { value.put(FONT_FAMILY, option(patch, FONT_FAMILY, FAMILIES, null, field + ".fontFamily")); }
            if (patch.containsKey(FONT_ID)) {
                Object id = patch.get(FONT_ID);
                if (!(id instanceof String text) || !FONT_ID_PATTERN.matcher(text).matches()) { throw invalid(field + ".fontId"); }
                value.put(FONT_ID, id); value.put(FONT_FAMILY, SYSTEM);
            }
            if (patch.containsKey(FONT_SIZE_RPX)) { value.put(FONT_SIZE_RPX, integer(patch, FONT_SIZE_RPX, null, 10, 96, field + ".fontSizeRpx")); }
            if (patch.containsKey(COLOR)) {
                Object color = patch.get(COLOR);
                if (!(color instanceof String text) || !COLOR_PATTERN.matcher(text).matches()) { throw invalid(field + ".color"); }
                value.put(COLOR, color);
            }
            result.put(role, value);
        }
        return result;
    }
    /** 自定义节点校验单行原文，日期结构与真实公历一起验证。 */
    private static void requireDate(String precision, String date, String path) {
        if (CUSTOM.equals(precision)) {
            if (CUSTOM_LABEL_FORBIDDEN_PATTERN.matcher(date).find()
                    || date.codePoints().allMatch(value -> Character.isWhitespace(value) || Character.isSpaceChar(value) || value == BYTE_ORDER_MARK)) {
                throw invalid(path);
            }
            return;
        }
        String pattern = precision.equals(YEAR) ? "[0-9]{4}" : precision.equals(MONTH) ? "[0-9]{4}-[0-9]{2}" : "[0-9]{4}-[0-9]{2}-[0-9]{2}";
        if (!date.matches(pattern) || date.startsWith("0000")) { throw invalid(path); }
        try {
            if (precision.equals(MONTH)) { YearMonth.parse(date); }
            else if (precision.equals(DAY)) { LocalDate.parse(date); }
        } catch (DateTimeException exception) { throw invalid(path); }
    }
    /** 严格读取对象并拒绝所有未知字段。 */
    private static Map<String, Object> object(Object value, Set<String> keys, String path) {
        if (!(value instanceof Map<?, ?> map) || map.keySet().stream().anyMatch(key -> !(key instanceof String) || !keys.contains(key))) { throw invalid(path); }
        return cast(map);
    }
    /** 已检查的 JSON 字符串键对象。 */
    @SuppressWarnings("unchecked")
    private static Map<String, Object> cast(Map<?, ?> map) { return (Map<String, Object>) map; }
    /** 字符串使用原始码点计数，保留空格与换行。 */
    private static String text(Map<String, Object> map, String key, String fallback, int max, String path) {
        Object value = map.containsKey(key) ? map.get(key) : fallback;
        if (!(value instanceof String text) || codePoints(text) > max) { throw invalid(path); }
        return text;
    }
    /** 选择字段不接受隐式转换或非法默认。 */
    private static String option(Map<String, Object> map, String key, Set<String> allowed, String fallback, String path) {
        Object value = map.containsKey(key) ? map.get(key) : fallback;
        if (!(value instanceof String text) || !allowed.contains(text)) { throw invalid(path); }
        return text;
    }
    /** JSON 整数不接受小数、字符串或布尔值。 */
    private static int integer(Map<String, Object> map, String key, Integer fallback, int min, int max, String path) {
        Object value = map.containsKey(key) ? map.get(key) : fallback;
        if (!(value instanceof Integer || value instanceof Long) || ((Number) value).longValue() < min || ((Number) value).longValue() > max) { throw invalid(path); }
        return ((Number) value).intValue();
    }
    /** 统一 Unicode 码点计数。 */
    private static int codePoints(String text) { return text.codePointCount(0, text.length()); }
    /** 错误位置始终来自固定规则，不回显用户原文。 */
    private static BusinessException invalid(String path) { return new BusinessException(PortfolioTimelineMessage.INVALID_CONFIG + path); }
}
