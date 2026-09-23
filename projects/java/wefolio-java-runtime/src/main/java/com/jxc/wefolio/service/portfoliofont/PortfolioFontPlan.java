package com.jxc.wefolio.service.portfoliofont;

import com.alibaba.fastjson2.JSON;
import com.jxc.wefolio.dict.PortfolioTextFontWeightDict;
import com.jxc.wefolio.dto.PortfolioConfigDto;
import com.jxc.wefolio.dto.teamportfolio.TeamPortfolioConfigDto;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.TreeSet;

/** 固定第一版字体需求算法；构建工具变化不影响已发布计划。 */
public record PortfolioFontPlan(String hash, Object versions, List<Group> groups) {
    /** 当前逻辑需求算法版本。 */
    public static final int VERSION = 1;
    /** 固定直立样式。 */
    public static final String NORMAL_STYLE = "normal";
    /** 内容字段。 */
    private static final String CONTENT = "content";
    /** 网格文字字段。 */
    private static final String TEXT = "text";
    /** 列表条目字段。 */
    private static final String ITEMS = "items";
    /** 字重字段。 */
    private static final String WEIGHT = "fontWeight";
    /** 固定逻辑摘要域，不能随部署工具更新。 */
    private static final String HASH_DOMAIN = "portfolio-font-plan-v1:";


    /** 一组字体的全部菜单字符需求；请求字重保留在逻辑摘要中。 */
    public record Group(String fontId, String fontVersion, int fontWeight, String fontStyle,
                        List<Integer> codepoints, String demandHash) { }

    /** 从个人作品集收集全部菜单需求。 */
    public static PortfolioFontPlan from(PortfolioConfigDto config) {
        return collect(PortfolioFontConfigSupport.nodes(config), config == null ? null : config.getFonts());
    }
    /** 从团队作品集收集全部菜单需求。 */
    public static PortfolioFontPlan from(TeamPortfolioConfigDto config) {
        return collect(PortfolioFontConfigSupport.nodes(config), config == null ? null : config.getFonts());
    }
    /** 对确定的 UTF-8 字符串计算摘要。 */
    public static String digest(String value) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8))); }
        catch (NoSuchAlgorithmException exception) { throw new IllegalStateException(exception); }
    }
    /** 按字体身份和请求字重聚合，不包含位置、字号和颜色。 */
    private static PortfolioFontPlan collect(Map<String, Map<String, Object>> nodes, Object versions) {
        Map<String, TreeSet<Integer>> points = new TreeMap<>();
        Map<String, Group> identities = new TreeMap<>();
        for (Map<String, Object> node : nodes.values()) {
            if (!(node.get(PortfolioFontConfigSupport.FONT_ID) instanceof String id)) { continue; }
            String version = PortfolioFontConfigSupport.version(versions, id);
            int weight = PortfolioTextFontWeightDict.BOLD.getCode().equals(node.get(WEIGHT)) ? 700 : 400;
            String key = JSON.toJSONString(List.of(id, version == null ? "" : version, weight, NORMAL_STYLE));
            identities.put(key, new Group(id, version, weight, NORMAL_STYLE, List.of(), null));
            TreeSet<Integer> cps = points.computeIfAbsent(key, ignored -> new TreeSet<>());
            addText(cps, node.get(CONTENT));
            addText(cps, node.get(TEXT));
            if (node.get(ITEMS) instanceof List<?> items) { items.forEach(item -> addText(cps, item)); }
        }
        List<Group> groups = new ArrayList<>();
        points.forEach((key, cps) -> {
            Group group = identities.get(key);
            List<Integer> characters = List.copyOf(cps);
            groups.add(new Group(group.fontId(), group.fontVersion(), group.fontWeight(), NORMAL_STYLE,
                    characters, digest(key + JSON.toJSONString(characters))));
        });
        String logicalHash = digest(HASH_DOMAIN + JSON.toJSONString(groups));
        Map<String, TreeSet<Integer>> physicalPoints = new TreeMap<>();
        Map<String, Group> physicalIdentities = new TreeMap<>();
        for (Group group : groups) {
            int weight = PortfolioFontWeights.physical(group.fontId(), group.fontVersion(), group.fontWeight());
            String key = JSON.toJSONString(List.of(group.fontId(), group.fontVersion() == null ? "" : group.fontVersion(), weight, NORMAL_STYLE));
            physicalIdentities.put(key, new Group(group.fontId(), group.fontVersion(), weight, NORMAL_STYLE, List.of(), null));
            physicalPoints.computeIfAbsent(key, ignored -> new TreeSet<>()).addAll(group.codepoints());
        }
        List<Group> physical = new ArrayList<>();
        physicalPoints.forEach((key, cps) -> {
            Group identity = physicalIdentities.get(key);
            List<Integer> characters = List.copyOf(cps);
            physical.add(new Group(identity.fontId(), identity.fontVersion(), identity.fontWeight(), NORMAL_STYLE,
                    characters, digest(key + JSON.toJSONString(characters))));
        });
        return new PortfolioFontPlan(logicalHash, versions, List.copyOf(physical));
    }
    /** Unicode 码点去重，补充字符和组合符不按 UTF-16 拆分。 */
    private static void addText(TreeSet<Integer> points, Object value) {
        if (value instanceof String text) { text.codePoints().forEach(points::add); }
    }
}
