package com.jxc.wefolio.service.advancedportfolio;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.jxc.wefolio.dto.PortfolioConfigDto;
import com.jxc.wefolio.dto.teamportfolio.TeamPortfolioConfigDto;
import com.jxc.wefolio.dict.PortfolioTextFontFamilyDict;
import com.jxc.wefolio.exception.BusinessException;
import com.jxc.wefolio.message.AdvancedPortfolioMessage;
import com.jxc.wefolio.service.PortfolioTextBackgroundConfigSupport;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.util.LinkedHashMap;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/** 高级独立入口的原始配置结构守卫；先拒绝未知键及类型强转，再进入普通业务校验。 */
public final class AdvancedPortfolioConfigGuard {
    /** 组件基础字段。 */
    private static final Set<String> COMPONENT_KEYS = Set.of("componentKey", "componentType", "sortOrder", "enabled", "config", "name");
    /** 普通超链接的站内目标模式。 */
    private static final String INTERNAL_PORTFOLIO_ACTION = "INTERNAL_PORTFOLIO";
    /** 普通超链接的外链模式。 */
    private static final String EXTERNAL_LINK_ACTION = "EXTERNAL_LINK";
    /** 布尔型组件参数。 */
    private static final Set<String> BOOLEAN_KEYS = Set.of("enabled", "showMemberName", "showTitle", "showDescription", "showComponentTitle", "showSwipeHint", "showClickIcon", "profileBorder", "contactBorder", "cellBorder", "backgroundEnabled");
    /** 整数型组件参数。 */
    private static final Set<String> INTEGER_KEYS = Set.of("sortOrder", "workId", "memberUserId", "portfolioId", "teamId", "targetPortfolioId", "backgroundWorkId", "backgroundMemberUserId", "heightPx", "fontSizeRpx", "profileBorderWidthRpx", "profileHorizontalMarginRpx", "profileVerticalMarginRpx", "contactBorderWidthRpx", "horizontalMarginRpx", "verticalMarginRpx", "rows", "columns", "gapRpx", "cellPaddingRpx", "cellRadiusRpx", "cellBorderWidthRpx", "row", "column", "rowSpan", "columnSpan", "marginTopRpx", "marginBottomRpx", "heightRpx", "futureDays");
    /** 各普通组件私有配置的完整受理字段。 */
    private static final Map<String,Set<String>> CONFIG_KEYS = new LinkedHashMap<>();
    static {
        define("PROFILE", "profile visibleFields profileLayout profileBorder profileBorderWidthRpx profileBorderColor profileHorizontalMarginRpx profileVerticalMarginRpx");
        define("TEAM_PROFILE", "team visibleFields profileLayout profileBorder profileBorderWidthRpx profileBorderColor profileHorizontalMarginRpx profileVerticalMarginRpx");
        define("CAROUSEL", "workIds items displayStyle showTitle showDescription");
        define("VIDEO_CAROUSEL", "title workIds items displayStyle showTitle showDescription showSwipeHint showComponentTitle");
        define("SINGLE_WORK", "workId memberUserId showTitle showDescription openMode detailOptions");
        define("WORK_GRID", "title groups workIds columns showTitle showDescription");
        define("WORK_LIST", "title groups workIds columns showTitle showDescription");
        define("MEMBER_PORTFOLIO_GRID", "title items showTitle showDescription showMemberName");
        define("MEMBER_PORTFOLIO_LIST", "title items showTitle showDescription showMemberName");
        define("HYPERLINK", "workId actionType targetPortfolioId externalContent promptText showClickIcon iconPosition");
        define("QR_CONTACT", "title description qrUrlSource qrUrl");
        define("CONTACT_FORM", "title description displayMode fields");
        define("SCHEDULE_QUERY", "title description buttonText displayMode queryRange");
        define("DIVIDER", "color heightPx");
        define("CONTACT_INFO", "contactPhone contactWechat contactBorder contactBorderWidthRpx contactBorderColor horizontalMarginRpx verticalMarginRpx");
        define("TEXT_SECTION", "title content alignment fontFamily fontId fontSizeRpx color lineHeightRpx lineHeight backgroundEnabled backgroundWorkId backgroundMemberUserId backgroundTreatment verticalAlignment");
        define("STRUCTURED_TEXT_SECTION", "blocks backgroundEnabled backgroundWorkId backgroundMemberUserId backgroundTreatment");
        define("TEXT_GRID", "rows columns columnWeights rowMinHeightsRpx gapRpx horizontalMarginRpx verticalMarginRpx cellPaddingRpx cellRadiusRpx cellBorder cellBorderWidthRpx cellBorderColor cellBackground cells");
    }
    /** 工具类不实例化。 */
    private AdvancedPortfolioConfigGuard() { }
    /** 按真实归属检查普通配置对象，个人与团队结构分别受理。 */
    public static void validate(ObjectNode config, boolean team) {
        if (config == null) { throw invalid(); }
        bean(config, team ? TeamPortfolioConfigDto.class : PortfolioConfigDto.class);
        components(config.get("components"), team);
        JsonNode nav = config.get("bottomNav");
        if (nav != null && nav.isObject() && nav.has("items") && nav.get("items").isArray()) {
            for (JsonNode item : nav.get("items")) { components(item.get("components"), team); }
        }
        JsonNode fonts = config.get("fonts");
        if (fonts != null && !fonts.isNull()) {
            if (!fonts.isObject()) { throw invalid(); }
            fonts.fields().forEachRemaining(entry -> fields(entry.getValue(), Set.of("fontVersion"), false));
        }
    }
    /** 校验规范化没有改变明确提交的普通字段；仅允许已登记的派生排序、字体裁剪和显示名称。 */
    public static void assertNormalized(ObjectNode incoming, ObjectNode normalized) {
        ObjectNode expected = incoming.deepCopy();
        JsonNode fonts = expected.get("fonts");
        if (fonts != null && fonts.isObject()) {
            Set<String> referenced = new HashSet<>();
            collectFontIds(normalized, referenced);
            ObjectNode retained = ((ObjectNode) fonts).deepCopy();
            List<String> removed = new ArrayList<>();
            retained.fieldNames().forEachRemaining(id -> { if (!referenced.contains(id)) { removed.add(id); } });
            retained.remove(removed);
            expected.set("fonts", retained);
        }
        preserved(expected, normalized, "");
    }

    /** 递归比较明确字段，精确登记组件名称、排序及最终字体引用的派生值。 */
    private static void preserved(JsonNode before, JsonNode after, String path) {
        String field = path.substring(path.lastIndexOf('.') + 1);
        if (path.endsWith(".component.name")) { return; }
        if (field.equals("fonts")) { preservedFonts(before, after); return; }
        if (before == null || before.isNull()) {
            if (after == null || after.isNull()) { return; }
            throw invalid();
        }
        if (after == null) { throw invalid(); }
        if (before.isObject()) {
            if (!after.isObject()) { throw invalid(); }
            ObjectNode expected = ((ObjectNode) before).deepCopy();
            deriveConfig(expected, after, path);
            expected.fields().forEachRemaining(entry -> preserved(entry.getValue(), after.get(entry.getKey()), path + "." + entry.getKey()));
            return;
        }
        if (before.isArray()) {
            if (!after.isArray()) { throw invalid(); }
            if (field.equals("components") || field.equals("groups")) {
                String id = field.equals("components") ? "componentKey" : "groupKey";
                List<JsonNode> ordered = new ArrayList<>();
                before.forEach(item -> {
                    if (!field.equals("components") || !item.has("enabled") || item.path("enabled").asBoolean()) { ordered.add(item); }
                });
                ordered.sort(Comparator.comparingInt((JsonNode item) -> item.has("sortOrder")
                        ? item.path("sortOrder").intValue() : Integer.MAX_VALUE).thenComparing(item -> item.path(id).asText()));
                if (ordered.size() != after.size()) { throw invalid(); }
                for (int index = 0; index < ordered.size(); index++) {
                    ObjectNode expected = ordered.get(index).deepCopy();
                    // 两类普通保存均按最终排序派生每项1000递增，不能忽略任意sortOrder变化。
                    expected.put("sortOrder", (index + 1) * 1000);
                    if (!expected.path(id).equals(after.get(index).path(id))) { throw invalid(); }
                    preserved(expected, after.get(index), path + (field.equals("components") ? ".component" : ".group"));
                }
            } else {
                if (before.size() != after.size()) { throw invalid(); }
                for (int index = 0; index < before.size(); index++) { preserved(before.get(index), after.get(index), path + ".item"); }
            }
            return;
        }
        if (before.isNumber() && after.isNumber() && before.decimalValue().compareTo(after.decimalValue()) == 0) { return; }
        if (before.isTextual() && after.isTextual()) {
            String expected = before.textValue(); String actual = after.textValue();
            if ((field.toLowerCase(Locale.ROOT).contains("color") || field.equals("backgroundColor")) && expected.equalsIgnoreCase(actual)) { return; }
            if (Set.of("title", "contactPhone", "contactWechat", "name", "groupKey").contains(field) && expected.strip().equals(actual)) { return; }
        }
        if (!before.equals(after)) { throw invalid(); }
    }

    /** 仅登记普通选择器明确清理的非活动字段和远程字体的历史字体派生值。 */
    private static void deriveConfig(ObjectNode expected, JsonNode after, String path) {
        if (!path.endsWith(".component.config") && !path.endsWith(".block") && !path.endsWith(".item")) { return; }
        if (expected.has("fontId") && expected.get("fontId").isTextual()
                && expected.get("fontId").equals(after.get("fontId")) && expected.has("fontFamily")) {
            expected.put("fontFamily", PortfolioTextFontFamilyDict.SYSTEM.getCode());
        }
        if (expected.has(PortfolioTextBackgroundConfigSupport.ENABLED)
                && expected.get(PortfolioTextBackgroundConfigSupport.ENABLED).isBoolean()
                && !expected.get(PortfolioTextBackgroundConfigSupport.ENABLED).booleanValue()) {
            expected.remove(PortfolioTextBackgroundConfigSupport.WORK_ID);
            expected.remove(PortfolioTextBackgroundConfigSupport.MEMBER_ID);
        }
        if (expected.has("actionType")) {
            if (INTERNAL_PORTFOLIO_ACTION.equals(expected.path("actionType").asText())) {
                expected.remove("externalContent"); expected.remove("promptText");
            } else if (EXTERNAL_LINK_ACTION.equals(expected.path("actionType").asText())) {
                expected.remove("targetPortfolioId");
            }
        }
    }

    /** 从最终普通配置的真实节点提取字体ID，不接受任意fonts条目扩展。 */
    private static void collectFontIds(JsonNode node, Set<String> ids) {
        if (node == null) { return; }
        if (node.isObject()) {
            if (node.has("fontId") && node.get("fontId").isTextual()) { ids.add(node.get("fontId").textValue()); }
            node.fields().forEachRemaining(entry -> { if (!entry.getKey().equals("fonts")) { collectFontIds(entry.getValue(), ids); } });
        } else if (node.isArray()) { node.forEach(item -> collectFontIds(item, ids)); }
    }

    /** 字体仅允许删除已不再引用的条目，保留条目的固定版本不能被替换或补造。 */
    private static void preservedFonts(JsonNode before, JsonNode after) {
        if (before == null || before.isNull()) {
            if (after == null || after.isNull()) { return; }
            throw invalid();
        }
        if (before.isObject() && before.isEmpty() && (after == null || after.isNull())) { return; }
        if (!before.isObject() || after == null || !after.isObject()) { throw invalid(); }
        if (before.size() != after.size()) { throw invalid(); }
        after.fields().forEachRemaining(entry -> {
            JsonNode expected = before.get(entry.getKey());
            if (expected == null || !expected.equals(entry.getValue())) { throw invalid(); }
        });
    }

    /** 检查 DTO 实际声明字段；不允许宽松 JSON 转换器丢弃输入。 */
    private static void bean(JsonNode node, Class<?> type) {
        if (node == null || node.isNull()) { return; }
        if (!node.isObject()) { throw invalid(); }
        Map<String,Field> fields = new LinkedHashMap<>();
        for (Field field : type.getDeclaredFields()) {
            if (!Modifier.isStatic(field.getModifiers()) && !field.getName().equals("fontsProvided")) { fields.put(field.getName(), field); }
        }
        node.fields().forEachRemaining(entry -> {
            Field field = fields.get(entry.getKey());
            if (field == null && entry.getKey().equals("name") && (type == PortfolioConfigDto.Component.class || type == TeamPortfolioConfigDto.ComponentEnvelope.class)) {
                if (!entry.getValue().isTextual()) { throw invalid(); }
                return;
            }
            if (field == null) { throw invalid(); }
            typed(entry.getValue(), field.getGenericType());
        });
    }
    /** 原始 DTO 标量及数组严格分型，Map 由组件字段守卫接续检查。 */
    private static void typed(JsonNode node, Type type) {
        if (node.isNull()) { return; }
        if (type instanceof ParameterizedType parameter && parameter.getRawType() == List.class) {
            if (!node.isArray()) { throw invalid(); }
            for (JsonNode item : node) { typed(item, parameter.getActualTypeArguments()[0]); }
            return;
        }
        if (!(type instanceof Class<?> raw)) { return; }
        if (raw == String.class && !node.isTextual() || (raw == Boolean.class || raw == boolean.class) && !node.isBoolean()
                || raw == Integer.class && (!node.isIntegralNumber() || !node.canConvertToInt())
                || raw == Long.class && (!node.isIntegralNumber() || !node.canConvertToLong())) { throw invalid(); }
        if (raw != String.class && raw != Boolean.class && raw != Integer.class && raw != Long.class
                && raw != boolean.class && raw != Object.class && !Map.class.isAssignableFrom(raw)) { bean(node, raw); }
    }
    /** 检查全部菜单组件，禁止个人与团队互相借用组件类型。 */
    private static void components(JsonNode array, boolean team) {
        if (array == null || array.isNull()) { return; }
        if (!array.isArray()) { throw invalid(); }
        for (JsonNode component : array) {
            fields(component, COMPONENT_KEYS, false);
            JsonNode type = component.get("componentType");
            if (type == null || !type.isTextual() || !CONFIG_KEYS.containsKey(type.textValue())) { throw invalid(); }
            String name = type.textValue();
            if (team && Set.of("PROFILE", "WORK_GRID", "WORK_LIST", "HYPERLINK").contains(name)
                    || !team && Set.of("TEAM_PROFILE", "MEMBER_PORTFOLIO_GRID", "MEMBER_PORTFOLIO_LIST").contains(name)) { throw invalid(); }
            fields(component.get("config"), CONFIG_KEYS.get(name), false);
            JsonNode config = component.get("config");
            if (config == null || config.isNull()) { continue; }
            config.fields().forEachRemaining(entry -> nested(entry.getKey(), entry.getValue(), name));
        }
    }
    /** 按普通字段集检查封闭对象及严格标量类型。 */
    private static void fields(JsonNode node, Set<String> allowed, boolean booleanValues) {
        if (node == null || node.isNull()) { return; }
        if (!node.isObject()) { throw invalid(); }
        node.fields().forEachRemaining(entry -> {
            if (!allowed.contains(entry.getKey())) { throw invalid(); }
            JsonNode value = entry.getValue();
            if (value.isNull()) {
                if (booleanValues || BOOLEAN_KEYS.contains(entry.getKey())) { throw invalid(); }
                return;
            }
            boolean bool = booleanValues || BOOLEAN_KEYS.contains(entry.getKey()) && !Set.of("profile", "team").contains(entry.getKey());
            if (bool && !value.isBoolean() || INTEGER_KEYS.contains(entry.getKey()) && (!value.isIntegralNumber() || !value.canConvertToLong())) { throw invalid(); }
            if (!bool && !INTEGER_KEYS.contains(entry.getKey()) && !Set.of("config", "components", "profile", "team", "visibleFields", "detailOptions", "queryRange", "workIds", "items", "fields", "groups", "cells", "blocks", "runs", "tags", "columnWeights", "rowMinHeightsRpx", "lineHeight").contains(entry.getKey())
                    && !value.isTextual()) { throw invalid(); }
            if (entry.getKey().equals("lineHeight") && !value.isNumber()) { throw invalid(); }
        });
    }
    /** 检查普通嵌套对象、数组元素和文字节点。 */
    private static void nested(String key, JsonNode node, String component) {
        if (node == null) { throw invalid(); }
        if (node.isNull()) { return; }
        switch (key) {
            case "lineHeight" -> { if (!node.isNumber()) { throw invalid(); } }
            case "profile" -> {
                fields(node, Set.of("avatarUrl", "displayName", "profession", "city", "bio", "wechatQrUrl", "tags"), false);
                node.fields().forEachRemaining(entry -> {
                    if (entry.getKey().equals("tags")) {
                        requireArray(entry.getValue());
                        for (JsonNode tag : entry.getValue()) { textObject(tag, "name color"); }
                    } else if (!entry.getValue().isNull() && !entry.getValue().isTextual()) { throw invalid(); }
                });
            }
            case "team" -> { fields(node, Set.of("teamId", "avatarUrl", "teamName", "intro"), false); }
            case "visibleFields" -> fields(node, component.equals("TEAM_PROFILE") ? Set.of("avatar", "teamName", "intro") : Set.of("avatar", "displayName", "profession", "city", "bio", "tags", "wechatQr"), true);
            case "detailOptions" -> fields(node, Set.of("enabled", "showMemberName", "showTitle", "showDescription"), true);
            case "queryRange" -> fields(node, Set.of("type", "futureDays", "startDate", "endDate"), false);
            case "workIds", "columnWeights", "rowMinHeightsRpx" -> array(node, true);
            case "fields" -> array(node, false);
            case "groups" -> { requireArray(node); for (JsonNode item : node) { fields(item, Set.of("groupKey", "name", "sortOrder", "workIds"), false); if (item.has("workIds")) { array(item.get("workIds"), true); } } }
            case "items" -> { requireArray(node); if (component.equals("STRUCTURED_TEXT_SECTION")) { array(node, false); } else { for (JsonNode item : node) { fields(item, component.startsWith("MEMBER_PORTFOLIO") ? Set.of("memberUserId", "portfolioId") : Set.of("memberUserId", "workId"), false); } } }
            case "cells" -> { requireArray(node); for (JsonNode cell : node) { fields(cell, Set.of("cellKey", "row", "column", "rowSpan", "columnSpan", "verticalAlignment", "blocks"), false); nested("blocks", cell.get("blocks"), "TEXT_GRID"); } }
            case "blocks" -> { requireArray(node); for (JsonNode block : node) { fields(block, component.equals("TEXT_GRID") ? Set.of("blockKey", "alignment", "marginTopRpx", "marginBottomRpx", "runs", "lineHeight") : Set.of("blockKey", "type", "content", "items", "heightRpx", "marginTopRpx", "marginBottomRpx", "fontFamily", "fontId", "fontSizeRpx", "fontWeight", "color", "alignment", "lineHeight"), false); if (block.has("items")) { array(block.get("items"), false); } if (block.has("runs")) { nested("runs", block.get("runs"), component); } } }
            case "runs" -> { requireArray(node); for (JsonNode run : node) { fields(run, Set.of("runKey", "text", "fontFamily", "fontId", "fontSizeRpx", "fontWeight", "color"), false); } }
            default -> { if (!BOOLEAN_KEYS.contains(key) && !INTEGER_KEYS.contains(key) && !node.isTextual()) { throw invalid(); } }
        }
    }
    /** 文本对象逐字段禁止隐式 stringify。 */
    private static void textObject(JsonNode node, String keys) { fields(node, Set.of(keys.split(" ")), false); node.forEach(value -> { if (!value.isNull() && !value.isTextual()) { throw invalid(); } }); }
    /** 数组类型和元素类型严格检查，数值不允许字符串冒充。 */
    private static void array(JsonNode node, boolean numbers) { requireArray(node); for (JsonNode item : node) { if (numbers ? !item.isIntegralNumber() : !item.isTextual()) { throw invalid(); } } }
    /** 容器必须真实为数组。 */
    private static void requireArray(JsonNode node) { if (node == null || !node.isArray()) { throw invalid(); } }
    /** 登记普通字段白名单。 */
    private static void define(String component, String fields) { CONFIG_KEYS.put(component, Set.of(fields.split(" "))); }
    /** 返回固定错误，避免回显用户配置中的敏感数据。 */
    private static BusinessException invalid() { return new BusinessException(AdvancedPortfolioMessage.INVALID_CONFIG); }
}
