package com.jxc.wefolio.service.portfoliofont;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONWriter;
import com.alibaba.fastjson2.JSONObject;
import com.jxc.wefolio.dict.PortfolioComponentTypeDict;
import com.jxc.wefolio.dict.PortfolioTextBlockTypeDict;
import com.jxc.wefolio.dto.MinePortfolioDraftSaveRequest;
import com.jxc.wefolio.dto.teamportfolio.TeamPortfolioDraftSaveRequest;
import com.jxc.wefolio.dict.PortfolioTextFontFamilyDict;
import com.jxc.wefolio.dto.PortfolioConfigDto;
import com.jxc.wefolio.dto.teamportfolio.TeamPortfolioConfigDto;
import com.jxc.wefolio.exception.BusinessException;
import com.jxc.wefolio.message.PortfolioFontMessage;
import com.jxc.wefolio.service.PortfolioComponentTraversal;
import com.jxc.wefolio.service.PortfolioTimelineConfigSupport;
import com.jxc.wefolio.service.teamportfolio.TeamPortfolioComponentTraversal;
import java.util.ArrayList;
import java.util.AbstractMap;
import java.util.Set;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;
import java.util.regex.Pattern;

/** 个人和团队共用的字体三态合并、版本保留及稳定节点遍历。 */
public final class PortfolioFontConfigSupport {
    /** 节点字体引用。 */
    public static final String FONT_ID = "fontId";
    /** 历史字体属性。 */
    public static final String FONT_FAMILY = "fontFamily";
    /** 固定版本属性。 */
    public static final String FONT_VERSION = "fontVersion";
    /** 结构化区块集合。 */
    private static final String BLOCKS = "blocks";
    /** 网格单元格集合。 */
    private static final String CELLS = "cells";
    /** 网格片段集合。 */
    private static final String RUNS = "runs";
    /** 结构化区块身份。 */
    private static final String BLOCK_KEY = "blockKey";
    /** 网格片段身份。 */
    private static final String RUN_KEY = "runKey";
    /** 结构化区块类型。 */
    private static final String TYPE = "type";
    /** 草稿请求配置字段。 */
    private static final String CONFIG = "config";
    /** 客户端能力字段。 */
    private static final String CLIENT_CAPABILITIES = "clientCapabilities";
    /** 作品集根字体版本表。 */
    private static final String FONTS = "fonts";
    /** 新能力请求摘要域。 */
    private static final String FINGERPRINT_DOMAIN = "portfolio-remote-font-v1:";
    /** 有界字体标识，未知但合法的 ID 仍可保留。 */
    private static final Pattern ID = Pattern.compile("[A-Z][A-Z0-9_]{0,63}");
    /** 有界不可变版本标识。 */
    private static final Pattern VERSION = Pattern.compile("[A-Za-z0-9][A-Za-z0-9._-]{0,95}");
    /** 根表结构容量保护，不是可选字体数量限制。 */
    private static final int MAX_ENTRIES = 256;

    /** 原始时间轴补丁的内存视图；通过类型识别，避免用户 key 影响字体分支。 */
    private static final class TimelineStyleNode extends AbstractMap<String, Object> {
        /** 实际持久补丁对象。 */
        private final Map<String, Object> delegate;
        /** 包装现有补丁，不拷贝或补全继承属性。 */
        private TimelineStyleNode(Map<String, Object> delegate) { this.delegate = delegate; }
        /** 保留原对象的可修改条目视图。 */
        @Override public Set<Entry<String, Object>> entrySet() { return delegate.entrySet(); }
        /** 字体选择写入仍落在原始补丁对象中。 */
        @Override public Object put(String key, Object value) { return delegate.put(key, value); }
    }

    /** 工具类不允许实例化。 */
    private PortfolioFontConfigSupport() { }

    /** 在原字段完成校验后合并个人字体扩展，直接读取原请求以保留 null。 */
    public static void merge(PortfolioConfigDto output, PortfolioConfigDto incoming,
                             PortfolioConfigDto existing, boolean capable) {
        output.setFonts(mergeNodes(nodes(output), nodes(incoming), nodes(existing),
                incoming == null ? null : incoming.getFonts(), incoming != null && incoming.isFontsProvided(),
                existing == null ? null : existing.getFonts(), capable));
    }

    /** 在原字段完成校验后合并团队字体扩展。 */
    public static void merge(TeamPortfolioConfigDto output, TeamPortfolioConfigDto incoming,
                             TeamPortfolioConfigDto existing, boolean capable) {
        output.setFonts(mergeNodes(nodes(output), nodes(incoming), nodes(existing),
                incoming == null ? null : incoming.getFonts(), incoming != null && incoming.isFontsProvided(),
                existing == null ? null : existing.getFonts(), capable));
    }

    /** 能力声明本身不是字体修改，省略和显式清除仍严格区分。 */
    public static boolean hasFontIntent(MinePortfolioDraftSaveRequest request) {
        return request.getClientCapabilities() != null && request.getClientCapabilities().supportsRemoteFonts()
                && request.getConfig() != null && (request.getConfig().isFontsProvided()
                || nodes(request.getConfig()).values().stream().anyMatch(node -> node.containsKey(FONT_ID)));
    }

    /** 团队请求沿用相同的字体意图判定，保留团队自己的幂等范围。 */
    public static boolean hasFontIntent(TeamPortfolioDraftSaveRequest request) {
        return request.getClientCapabilities() != null && request.getClientCapabilities().supportsRemoteFonts()
                && request.getConfig() != null && (request.getConfig().isFontsProvided()
                || nodes(request.getConfig()).values().stream().anyMatch(node -> node.containsKey(FONT_ID)));
    }

    /** 旧个人请求精确投影：只去掉旧 DTO 不含的顶层能力和根字体表。 */
    public static JSONObject legacyRequest(MinePortfolioDraftSaveRequest request) {
        JSONObject result = JSON.parseObject(JSON.toJSONString(request));
        result.remove(CLIENT_CAPABILITIES);
        JSONObject config = result.getJSONObject(CONFIG);
        if (config != null) { config.remove(FONTS); }
        return result;
    }

    /** 旧团队指纹不含幂等键，根字体表不进入旧 DTO 投影。 */
    public static JSONObject legacyConfig(TeamPortfolioConfigDto config) {
        JSONObject result = JSON.parseObject(JSON.toJSONString(config));
        if (result != null) { result.remove(FONTS); }
        return result;
    }

    /** 生成新版指纹原文；直接序列化且保留 null，旧版仍使用原算法。 */
    public static String fingerprint(Object request) {
        boolean fontsProvided = request instanceof MinePortfolioDraftSaveRequest personal
                ? personal.getConfig().isFontsProvided()
                : request instanceof TeamPortfolioDraftSaveRequest team && team.getConfig().isFontsProvided();
        JSONObject projection = JSON.parseObject(JSON.toJSONString(request,
                JSONWriter.Feature.WriteMapNullValue, JSONWriter.Feature.MapSortField));
        var capabilities = request instanceof MinePortfolioDraftSaveRequest personal ? personal.getClientCapabilities()
                : request instanceof TeamPortfolioDraftSaveRequest team ? team.getClientCapabilities() : null;
        if (capabilities != null && !capabilities.hasTimelineIntent()) {
            JSONObject declared = projection.getJSONObject(CLIENT_CAPABILITIES);
            if (declared != null) { declared.remove("portfolioTimeline"); }
        }
        return FINGERPRINT_DOMAIN + fontsProvided + ":" + JSON.toJSONString(projection,
                JSONWriter.Feature.WriteMapNullValue, JSONWriter.Feature.MapSortField);
    }

    /** 列出个人作品集有效菜单中的全部字体节点，键不依赖父菜单或网格位置。 */
    public static Map<String, Map<String, Object>> nodes(PortfolioConfigDto config) {
        Map<String, Map<String, Object>> result = new LinkedHashMap<>();
        for (var location : PortfolioComponentTraversal.listComponentLocations(config)) {
            var component = location.component();
            if (component != null) { addNodes(result, component.getComponentKey(), component.getComponentType(), component.getConfig()); }
        }
        return result;
    }

    /** 列出团队作品集字体节点。 */
    public static Map<String, Map<String, Object>> nodes(TeamPortfolioConfigDto config) {
        Map<String, Map<String, Object>> result = new LinkedHashMap<>();
        for (var location : TeamPortfolioComponentTraversal.listComponentLocations(config)) {
            var component = location.component();
            if (component != null) { addNodes(result, component.getComponentKey(), component.getComponentType(), component.getConfig()); }
        }
        return result;
    }

    /** 个人字体计划使用实际文字需求，不把时间轴原始补丁当成空文字。 */
    public static Map<String, Map<String, Object>> demandNodes(PortfolioConfigDto config) {
        Map<String, Map<String, Object>> result = legacyDemandNodes(nodes(config));
        for (var location : PortfolioComponentTraversal.listComponentLocations(config)) {
            var component = location.component();
            if (component != null && PortfolioTimelineConfigSupport.COMPONENT_TYPE.equals(component.getComponentType())) {
                addTimelineDemands(result, component.getComponentKey(), component.getConfig());
            }
        }
        return result;
    }

    /** 团队复用相同的有效文字与字重计算。 */
    public static Map<String, Map<String, Object>> demandNodes(TeamPortfolioConfigDto config) {
        Map<String, Map<String, Object>> result = legacyDemandNodes(nodes(config));
        for (var location : TeamPortfolioComponentTraversal.listComponentLocations(config)) {
            var component = location.component();
            if (component != null && PortfolioTimelineConfigSupport.COMPONENT_TYPE.equals(component.getComponentType())) {
                addTimelineDemands(result, component.getComponentKey(), component.getConfig());
            }
        }
        return result;
    }

    /** 旧组件原始文字节点和顺序保持不变，确保旧字体摘要不变。 */
    private static Map<String, Map<String, Object>> legacyDemandNodes(Map<String, Map<String, Object>> nodes) {
        Map<String, Map<String, Object>> result = new LinkedHashMap<>();
        nodes.forEach((key, value) -> { if (!(value instanceof TimelineStyleNode)) { result.put(key, value); } });
        return result;
    }

    /** 临时需求身份只用于容器去重，不进入字体摘要。 */
    private static void addTimelineDemands(Map<String, Map<String, Object>> target, String key, Map<String, Object> config) {
        if (config == null) { return; }
        int index = 0;
        for (Map<String, Object> demand : PortfolioTimelineConfigSupport.fontDemandNodes(config)) {
            target.put(key + ":" + PortfolioTimelineConfigSupport.COMPONENT_TYPE + ":" + index++, demand);
        }
    }

    /** 从已校验版本表取固定版本，不自动采用目录最新版。 */
    public static String version(Object fonts, String id) {
        if (!(fonts instanceof Map<?, ?> table) || !(table.get(id) instanceof Map<?, ?> entry)) { return null; }
        Object value = entry.get(FONT_VERSION);
        return value instanceof String text && VERSION.matcher(text).matches() ? text : null;
    }

    /** 合并最终仍存在的节点，并按最终引用裁剪根表。 */
    private static Object mergeNodes(Map<String, Map<String, Object>> output,
            Map<String, Map<String, Object>> input, Map<String, Map<String, Object>> old,
            Object fonts, boolean provided, Object oldFonts, boolean capable) {
        if (capable && provided && !(fonts instanceof Map<?, ?>)) { throw invalid(); }
        if (capable && fonts instanceof Map<?, ?> table && table.size() > MAX_ENTRIES) { throw invalid(); }
        if (capable && fonts instanceof Map<?, ?> table) {
            for (var entry : table.entrySet()) {
                if (!(entry.getKey() instanceof String id) || !ID.matcher(id).matches()) { throw invalid(); }
                if (entry.getValue() instanceof Map<?, ?> value
                        && value.get(FONT_VERSION) instanceof String version && version.length() > 96) { throw invalid(); }
            }
        }
        Map<String, Object> versions = new TreeMap<>();
        for (var entry : output.entrySet()) {
            Map<String, Object> target = entry.getValue();
            Map<String, Object> request = input.get(entry.getKey());
            Map<String, Object> saved = old.get(entry.getKey());
            // 时间轴补丁已完成原子字体选择规范化；删除覆盖与内置选择不得恢复旧 fontId。
            if (target instanceof TimelineStyleNode) {
                Object selected = target.get(FONT_ID);
                if (selected instanceof String id) {
                    if (!ID.matcher(id).matches()) { throw invalid(); }
                    String selectedVersion = capable ? version(fonts, id) : null;
                    if (selectedVersion == null) { selectedVersion = version(oldFonts, id); }
                    if (selectedVersion != null) { versions.put(id, Map.of(FONT_VERSION, selectedVersion)); }
                }
                continue;
            }
            Object selection = null;
            if (capable && request != null && request.containsKey(FONT_ID)) {
                selection = request.get(FONT_ID);
                if (selection != null && (!(selection instanceof String id) || !ID.matcher(id).matches())) { throw invalid(); }
            } else if (saved != null && Objects.equals(family(request == null ? target : request), family(saved))) {
                selection = saved.get(FONT_ID);
            }
            target.remove(FONT_ID);
            // 前序持久配置规范化可能已输出 SYSTEM；清除时恢复原请求已校验的历史选择。
            if (request != null) { target.put(FONT_FAMILY, family(request)); }
            if (selection instanceof String id && ID.matcher(id).matches()) {
                target.put(FONT_ID, id);
                target.put(FONT_FAMILY, PortfolioTextFontFamilyDict.SYSTEM.getCode());
                String selectedVersion = capable ? version(fonts, id) : null;
                if (selectedVersion == null) { selectedVersion = version(oldFonts, id); }
                if (selectedVersion != null) { versions.put(id, Map.of(FONT_VERSION, selectedVersion)); }
            }
        }
        return versions.isEmpty() ? null : versions;
    }

    /** 普通文字原有缺省和 null 均为系统字体；其它旧值由原校验器负责拒绝。 */
    private static Object family(Map<String, Object> node) {
        Object value = node.get(FONT_FAMILY);
        return value == null ? PortfolioTextFontFamilyDict.SYSTEM.getCode() : value;
    }

    /** 根据组件类型收集可设置字体的文字位置。 */
    private static void addNodes(Map<String, Map<String, Object>> result, String key, String type, Map<String, Object> config) {
        if (config == null) { return; }
        String prefix = key + ":" + type + ":";
        if (PortfolioTimelineConfigSupport.COMPONENT_TYPE.equals(type)) {
            PortfolioTimelineConfigSupport.rawStyleNodes(config).forEach((identity, patch) -> result.put(prefix + identity, new TimelineStyleNode(patch)));
        } else if (PortfolioComponentTypeDict.TEXT_SECTION.getCode().equals(type)) { result.put(prefix, config); }
        else if (PortfolioComponentTypeDict.STRUCTURED_TEXT_SECTION.getCode().equals(type)) {
            for (var block : maps(config.get(BLOCKS))) {
                if (!PortfolioTextBlockTypeDict.SPACER.getCode().equals(block.get(TYPE))) { result.put(prefix + block.get(BLOCK_KEY), block); }
            }
        } else if (PortfolioComponentTypeDict.TEXT_GRID.getCode().equals(type)) {
            for (var cell : maps(config.get(CELLS))) {
                for (var block : maps(cell.get(BLOCKS))) {
                    for (var run : maps(block.get(RUNS))) { result.put(prefix + run.get(RUN_KEY), run); }
                }
            }
        }
    }

    /** 在原结构校验之外只遍历对象节点，不构造缺失节点。 */
    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> maps(Object value) {
        List<Map<String, Object>> result = new ArrayList<>();
        if (value instanceof List<?> list) {
            for (Object item : list) { if (item instanceof Map<?, ?> map) { result.add((Map<String, Object>) map); } }
        }
        return result;
    }

    /** 返回字体扩展结构错误。 */
    private static BusinessException invalid() { return new BusinessException(PortfolioFontMessage.INVALID_CONFIG); }
}
