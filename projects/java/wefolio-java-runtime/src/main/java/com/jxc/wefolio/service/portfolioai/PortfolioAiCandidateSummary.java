package com.jxc.wefolio.service.portfolioai;

import com.fasterxml.jackson.databind.JsonNode;
import com.jxc.wefolio.message.PortfolioAiMessage;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static com.jxc.wefolio.service.portfolioai.PortfolioAiConstants.*;

/** 以实际完整配置重算候选说明与来源使用情况，不展示模型自报统计或真实业务ID。 */
final class PortfolioAiCandidateSummary {
    /** 固定组件身份字段。 */
    private static final String COMPONENT_KEY = KEY_COMPONENT_KEY;
    /** 来源在本次候选中未使用时的固定说明。 */
    private static final String UNUSED_REASON = "未在候选页面中使用，可继续手动添加";
    /** 已失效来源的固定说明。 */
    private static final String INVALID_REASON = "来源当前不可用，候选未绑定该素材";
    /** 仅供服务端调用。 */
    private PortfolioAiCandidateSummary() { }

    /** 按真实身份与内容计算新增、移除、修改和位置调整；不把派生sortOrder算作文案修改。 */
    static List<String> describe(ObjectNode before, ObjectNode after, ObjectNode request) {
        Map<String, JsonNode> old = components(before), now = components(after);
        List<String> oldOrder = new ArrayList<>(old.keySet()), newOrder = new ArrayList<>(now.keySet());
        int inserted = 0, removed = 0, modified = 0, moved = 0, retained = 0;
        for (String key : now.keySet()) {
            if (!old.containsKey(key)) { inserted++; continue; }
            ObjectNode previous = old.get(key).deepCopy(), candidate = now.get(key).deepCopy();
            previous.remove(KEY_SORT_ORDER); candidate.remove(KEY_SORT_ORDER);
            if (!previous.equals(candidate)) { modified++; }
            if (oldOrder.indexOf(key) != newOrder.indexOf(key)) { moved++; }
            else if (previous.equals(candidate)) { retained++; }
        }
        for (String key : old.keySet()) { if (!now.containsKey(key)) { removed++; } }
        List<String> result = new ArrayList<>();
        if (inserted > 0) { result.add(SUMMARY_INSERT_PREFIX + inserted + SUMMARY_COMPONENT_COUNT_SUFFIX); }
        if (removed > 0) { result.add(SUMMARY_REMOVE_PREFIX + removed + SUMMARY_COMPONENT_COUNT_SUFFIX); }
        if (modified > 0) { result.add(SUMMARY_MODIFY_PREFIX + modified + SUMMARY_MODIFY_SUFFIX); }
        if (moved > 0) { result.add(SUMMARY_MOVE_PREFIX + moved + SUMMARY_MOVE_SUFFIX); }
        if (retained > 0) { result.add(SUMMARY_RETAIN_PREFIX + retained + SUMMARY_RETAIN_SUFFIX); }
        for (String field : List.of(KEY_SHARE, KEY_STYLE, KEY_BACKGROUND_AUDIO)) {
            if (!before.path(field).equals(after.path(field))) { result.add(SUMMARY_GLOBAL_PREFIX + switch (field) {
                case KEY_SHARE -> SUMMARY_SHARE_LABEL; case KEY_STYLE -> SUMMARY_STYLE_LABEL; default -> SUMMARY_AUDIO_LABEL;
            }); }
        }
        Map<String, Object> usage = sources(after, request);
        List<?> used = (List<?>) usage.get(KEY_USED_SOURCE_NOS);
        if (!used.isEmpty()) { result.add(SUMMARY_USED_SOURCES_PREFIX + used); }
        for (Object value : (List<?>) usage.get(KEY_UNUSED_SOURCES)) {
            Map<?, ?> unused = (Map<?, ?>) value;
            result.add(SUMMARY_UNUSED_SOURCE_PREFIX + unused.get(KEY_SOURCE_NO) + SUMMARY_REASON_SEPARATOR + unused.get(KEY_REASON));
        }
        if (result.isEmpty()) { result.add(PortfolioAiMessage.NO_CHANGES); }
        return result;
    }

    /** 来源使用包含所有菜单及背景引用，WORK和成员作品集身份命名空间保持独立。 */
    static Map<String, Object> sources(ObjectNode candidate, ObjectNode request) {
        Set<String> usedReferences = new LinkedHashSet<>(); references(candidate, usedReferences);
        List<Integer> used = new ArrayList<>(); List<Map<String, Object>> unused = new ArrayList<>();
        for (JsonNode source : request.path(KEY_FACTS).path(KEY_SOURCES)) {
            int number = source.path(KEY_SOURCE_NO).intValue();
            String identity = source.path(KEY_KIND).asText() + REFERENCE_SEPARATOR + source.path(KEY_SOURCE_ID).asText();
            if (usedReferences.contains(identity)) { used.add(number); }
            else { unused.add(Map.of(KEY_SOURCE_NO, number, KEY_REASON, source.path(KEY_CAN_BIND).asBoolean(true) ? UNUSED_REASON : INVALID_REASON)); }
        }
        return Map.of(KEY_USED_SOURCE_NOS, used, KEY_UNUSED_SOURCES, unused);
    }

    /** 只检查普通配置真实引用键，不把文字、分享路径或其它ID误算成素材。 */
    private static void references(JsonNode node, Set<String> used) {
        if (node.isObject()) {
            for (String field : List.of(KEY_WORK_ID, KEY_BACKGROUND_WORK_ID)) {
                if (node.path(field).isIntegralNumber()) { used.add(WORK_REFERENCE_PREFIX + node.path(field).asText()); }
            }
            for (JsonNode id : node.path(KEY_WORK_IDS)) { used.add(WORK_REFERENCE_PREFIX + id.asText()); }
            if (node.has(KEY_MEMBER_USER_ID) && node.path(KEY_PORTFOLIO_ID).isIntegralNumber()) {
                used.add(MEMBER_PORTFOLIO_REFERENCE_PREFIX + node.path(KEY_PORTFOLIO_ID).asText());
            }
        }
        for (JsonNode child : node) { references(child, used); }
    }

    /** 按显示顺序收集全配置组件；未修改菜单也参与真实身份比较。 */
    private static Map<String, JsonNode> components(ObjectNode config) {
        Map<String, JsonNode> result = new LinkedHashMap<>();
        config.path(KEY_COMPONENTS).forEach(n -> result.put(n.path(COMPONENT_KEY).asText(), n));
        for (JsonNode menu : config.path(KEY_BOTTOM_NAV).path(KEY_ITEMS)) {
            menu.path(KEY_COMPONENTS).forEach(n -> result.put(n.path(COMPONENT_KEY).asText(), n));
        }
        return result;
    }
}
