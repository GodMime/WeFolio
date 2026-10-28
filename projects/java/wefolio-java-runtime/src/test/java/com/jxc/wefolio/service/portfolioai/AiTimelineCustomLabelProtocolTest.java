package com.jxc.wefolio.service.portfolioai;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import java.util.ArrayList;
import java.util.List;
import static org.assertj.core.api.Assertions.*;

/** 自定义节点标记贯穿 V2 新建、局部修改和结构动作，V1 契约保持冻结。 */
class AiTimelineCustomLabelProtocolTest {
    /** 通过生产 JSON 入口构造严格节点。 */
    private ObjectNode node(String json) {
        return StrictAiJson.parseObject(json, StrictAiJson.CONFIG_MAX_BYTES, StrictAiJson.MAX_DEPTH);
    }

    /** 冻结包含原日期节点的 V2 请求，测试不依赖跨工程运行时。 */
    private ObjectNode request(String mode) {
        ObjectNode request = node("""
                {"contractVersion":"ai-portfolio-v2","promptVersion":"portfolio-ai-2026-10-28.1",
                 "input":{"clientAdvancedVersion":2,"sourceMode":"SELECTED_SOURCES","mode":"MODIFY_PAGE","ownerType":"USER","prompt":"调整节点标记","targetMenuKey":"__root__"},
                 "facts":{"sources":[],"bindings":[],"baseConfig":{"advancedPortfolioVersion":2,"components":[
                 {"componentKey":"timeline_real","componentType":"TIMELINE","sortOrder":1000,"enabled":true,"config":{"title":"","nodes":[
                 {"nodeKey":"real_1","datePrecision":"DAY","date":"2024-02-29","title":"启程","description":""}]}}]}}}
                """);
        request.put("runSpecId", AiPortfolioRunSpecRegistry.forVersion(2).id());
        request.withObject("input").put("mode", mode);
        return request;
    }

    /** 完整八字段输出，避免辅助字段遮蔽被测试的节点规则。 */
    private ObjectNode output(String mode) {
        return node("""
                {"schemaVersion":"ai-portfolio-v2","promptVersion":"portfolio-ai-2026-10-28.1","status":"CANDIDATE",
                 "mode":"MODIFY_PAGE","page":null,"operations":[],"questions":[],"notes":[]}
                """).put("mode", mode);
    }

    /** 自定义节点仅使用 date 保存原文，不生成额外身份和字段。 */
    private ObjectNode customNode(String label) {
        return node("{\"datePrecision\":\"CUSTOM\",\"title\":\"下一阶段\",\"description\":\"\"}").put("date", label);
    }

    /** 构造完整的新建候选。 */
    private ObjectNode creation(JsonNode config) {
        ObjectNode output = output("CREATE");
        ObjectNode page = output.putObject("page").put("menuKey", "menu_current");
        page.putObject("settings");
        ObjectNode component = page.putArray("components").addObject().put("type", "TIMELINE");
        component.putArray("sourceNos"); component.putArray("bindings"); component.set("params", config);
        return output;
    }

    /** 建立协议允许的新建配置。 */
    private ObjectNode config(String label) {
        ObjectNode config = node("{\"title\":\"\",\"nodes\":[]}");
        config.withArray("nodes").add(customNode(label));
        return config;
    }

    /** 从真实冻结目标计算哈希，节点和组件目标各自保持原语义。 */
    private ObjectNode addOperation(ObjectNode request, ObjectNode output, String operation, boolean nodeTarget, ObjectNode payload) {
        ObjectNode target = node("{\"menuKey\":\"menu_current\",\"componentKey\":\"cmp_1\"}");
        if (nodeTarget) { target.put("nodeKey", "node_1"); }
        ObjectNode result = output.withArray("operations").addObject().put("op", operation).put("reason", "按要求修改节点标记");
        result.set("target", target);
        result.put("expectedHash", AiPortfolioProtocol.expectedHash(nodeTarget ? AiTimelineProtocol.hashBase(request, target)
                : AiPortfolioProtocol.resolveComponent(request, "cmp_1")));
        result.set("payload", payload);
        return result;
    }

    /** 修改节点标记同时提供类型与原始文本。 */
    private ObjectNode update(String label) {
        ObjectNode payload = node("{\"params\":{\"datePrecision\":\"CUSTOM\"}}");
        payload.withObject("params").put("date", label);
        return payload;
    }

    /** 插入动作携带完整自定义节点，不伪造真实节点身份。 */
    private ObjectNode insert(String label) {
        ObjectNode payload = node("{\"action\":\"INSERT_TIMELINE_NODE\",\"position\":\"END\",\"anchorNodeKey\":null}");
        payload.set("node", customNode(label));
        return payload;
    }

    /** V2 的新建和局部 schema 同时开放 CUSTOM，V1 不接受新组件。 */
    @Test void onlyV2ExposesCustomLabelsInBothNodeSchemas() {
        ObjectNode request = request("CREATE");
        JsonNode rules = AiPortfolioProtocol.contract(request);
        for (JsonNode schema : List.of(rules.path("componentCatalog").path("TIMELINE").path("params").path("nodes").path("items").path("properties"),
                rules.path("nodeParams").path("TIMELINE_NODE"))) {
            assertThat(schema.path("datePrecision").path("enum")).contains(node("{\"value\":\"CUSTOM\"}").path("value"));
            assertThat(schema.path("date").path("maxCodePoints").asInt()).isEqualTo(20);
        }
        request.remove("runSpecId"); request.withObject("input").remove("clientAdvancedVersion");
        request.put("contractVersion", AiPortfolioProtocol.CONTRACT_VERSION).put("promptVersion", AiPortfolioProtocol.PROMPT_VERSION);
        assertThat(AiPortfolioProtocol.contract(request).path("componentCatalog").has("TIMELINE")).isFalse();
        ObjectNode output = creation(config("未来"));
        output.put("schemaVersion", AiPortfolioProtocol.CONTRACT_VERSION).put("promptVersion", AiPortfolioProtocol.PROMPT_VERSION);
        assertThatThrownBy(() -> AiPortfolioProtocol.validate(request, output.toString())).isInstanceOf(AiProtocolException.class);
    }

    /** 中文、补充字符、前后和连续空格以及句点均按原文传递。 */
    @Test void createAndProjectionPreserveCustomLabelsForBothOwners() {
        for (String owner : List.of("USER", "TEAM")) {
            for (String label : List.of("  筹备  阶段.v1  ", "<未来>", "🎉".repeat(20), "é".repeat(10), "\u200B", "\u180E", "\uFEFF未来\u00A0")) {
                ObjectNode request = request("CREATE"); request.withObject("input").put("ownerType", owner);
                ObjectNode output = creation(config(label));
                assertThat(AiPortfolioProtocol.validate(request, output.toString())).isEqualTo(output);
                ObjectNode original = (ObjectNode) AiPortfolioProtocol.resolveComponent(request, "cmp_1").path("config").path("nodes").get(0);
                original.put("datePrecision", "CUSTOM").put("date", label);
                request.withObject("input").put("mode", "MODIFY_PAGE");
                JsonNode projected = AiPortfolioProtocol.project(request).path("currentPage").path("components").get(0).path("params").path("nodes").get(0);
                assertThat(projected.path("date").asText()).isEqualTo(label);
                assertThat(projected.path("datePrecision").asText()).isEqualTo("CUSTOM");
                assertThat(projected.has("customLabel")).isFalse();
            }
        }
    }

    /** 节点新增、日期转换和移动均保留原文及稳定身份。 */
    @Test void insertUpdateAndMovePreserveOriginalLabelAndNodeIdentity() {
        String label = "  即将  开始.v2  ";
        ObjectNode request = request("MODIFY_PAGE"), output = output("MODIFY_PAGE");
        addOperation(request, output, "UPDATE_PARAMS", true, update(label));
        addOperation(request, output, "UPDATE_STRUCTURE", false, insert("未来"));
        addOperation(request, output, "UPDATE_STRUCTURE", true, node("{\"action\":\"MOVE_TIMELINE_NODE\",\"position\":\"END\",\"anchorNodeKey\":null}"));
        assertThat(AiPortfolioProtocol.validate(request, output.toString())).isEqualTo(output);
        ObjectNode candidate = AiPortfolioProtocol.resolveComponent(request, "cmp_1").deepCopy();
        output.path("operations").forEach(operation -> AiTimelineProtocol.apply(request, candidate, (ObjectNode) operation, "new_custom"));
        JsonNode nodes = candidate.path("config").path("nodes");
        assertThat(nodes.get(0).path("nodeKey").asText()).isEqualTo("new_custom");
        assertThat(nodes.get(0).path("date").asText()).isEqualTo("未来");
        assertThat(nodes.get(1).path("nodeKey").asText()).isEqualTo("real_1");
        assertThat(nodes.get(1).path("date").asText()).isEqualTo(label);
        assertThat(candidate.toString()).doesNotContain("customLabel");
        assertThat(AiPortfolioProtocol.resolveComponent(request, "cmp_1").path("config").path("nodes").get(0).path("date").asText()).isEqualTo("2024-02-29");
    }

    /** 逐个控制符、所有 trim 空白及码点超限在三种写入路径中一致拒绝。 */
    @Test void invalidLabelsAreRejectedByCreateInsertAndUpdate() {
        List<String> invalid = new ArrayList<>(List.of("", " ", "未".repeat(21), "🎉".repeat(21), "é".repeat(10) + "a"));
        for (int codePoint : new int[]{0x09, 0x0A, 0x0B, 0x0C, 0x0D, 0x20, 0xA0, 0x1680,
                0x2000, 0x2001, 0x2002, 0x2003, 0x2004, 0x2005, 0x2006, 0x2007, 0x2008, 0x2009, 0x200A,
                0x2028, 0x2029, 0x202F, 0x205F, 0x3000, 0xFEFF}) {
            invalid.add(new String(Character.toChars(codePoint)));
        }
        for (int codePoint = 0; codePoint <= 0x1F; codePoint++) { invalid.add("前" + (char) codePoint + "后"); }
        for (int codePoint : new int[]{0x7F, 0x85, 0x2028, 0x2029}) { invalid.add("前" + (char) codePoint + "后"); }
        for (String label : invalid) {
            ObjectNode config = config(label);
            assertThatThrownBy(() -> AiTimelineProtocol.validateContent(config, false)).isInstanceOf(AiProtocolException.class);
            ObjectNode creation = creation(config);
            assertThatThrownBy(() -> AiPortfolioProtocol.validate(request("CREATE"), creation.toString())).isInstanceOf(AiProtocolException.class);
            ObjectNode request = request("MODIFY_PAGE"), update = output("MODIFY_PAGE"), insert = output("MODIFY_PAGE");
            addOperation(request, update, "UPDATE_PARAMS", true, update(label));
            addOperation(request, insert, "UPDATE_STRUCTURE", false, insert(label));
            assertThatThrownBy(() -> AiPortfolioProtocol.validate(request, update.toString())).isInstanceOf(AiProtocolException.class);
            assertThatThrownBy(() -> AiPortfolioProtocol.validate(request, insert.toString())).isInstanceOf(AiProtocolException.class);
        }
    }

    /** 日期和自定义互转必须成对写入，既有三精度格式不放宽。 */
    @Test void pairedDateMutationAndCalendarValidationRemainStrict() {
        ObjectNode request = request("MODIFY_PAGE");
        ObjectNode original = (ObjectNode) AiPortfolioProtocol.resolveComponent(request, "cmp_1").path("config").path("nodes").get(0);
        original.put("datePrecision", "CUSTOM").put("date", "未来");
        for (String params : List.of("{\"date\":\"未来\"}", "{\"datePrecision\":\"CUSTOM\"}",
                "{\"datePrecision\":\"CUSTOM\",\"date\":\"未来\",\"customLabel\":\"未来\"}",
                "{\"datePrecision\":\"DAY\",\"date\":\"2023-02-29\"}", "{\"datePrecision\":\"YEAR\",\"date\":\"未来\"}",
                "{\"datePrecision\":\"YEAR\",\"date\":\"0000\"}", "{\"datePrecision\":\"MONTH\",\"date\":\"2024-13\"}")) {
            ObjectNode output = output("MODIFY_PAGE"), payload = node("{}"); payload.set("params", node(params));
            addOperation(request, output, "UPDATE_PARAMS", true, payload);
            assertThatThrownBy(() -> AiPortfolioProtocol.validate(request, output.toString())).isInstanceOf(AiProtocolException.class);
        }
        ObjectNode output = output("MODIFY_PAGE");
        addOperation(request, output, "UPDATE_PARAMS", true, node("{\"params\":{\"datePrecision\":\"DAY\",\"date\":\"2024-02-29\"}}"));
        assertThat(AiPortfolioProtocol.validate(request, output.toString())).isEqualTo(output);
    }

    /** 六段正文各不超过单字段上限，单独验证含自定义标签的 6000/6001 总量边界。 */
    @Test void aggregateLimitCountsCustomLabelsButNotCalendarDates() {
        ObjectNode config = node("{\"title\":\"\",\"nodes\":[]}");
        for (int index = 0; index < 6; index++) {
            config.withArray("nodes").addObject().put("datePrecision", "YEAR").put("date", "2018").put("title", "")
                    .put("description", "文".repeat(1000));
        }
        assertThat(AiPortfolioProtocol.validate(request("CREATE"), creation(config).toString())).isNotNull();
        ObjectNode first = (ObjectNode) config.path("nodes").get(0);
        first.put("datePrecision", "CUSTOM").put("date", "🎉".repeat(20)).put("description", "文".repeat(980));
        assertThat(AiPortfolioProtocol.validate(request("CREATE"), creation(config).toString())).isNotNull();
        first.put("description", "文".repeat(981));
        assertThatThrownBy(() -> AiPortfolioProtocol.validate(request("CREATE"), creation(config).toString())).isInstanceOf(AiProtocolException.class);
    }

    /** 局部日期修改与插入完成后都重验整轴总量，不能只检查单个标签。 */
    @Test void updateAndInsertCannotBypassAggregateLimit() {
        ObjectNode request = request("MODIFY_PAGE");
        ArrayNode nodes = AiPortfolioProtocol.resolveComponent(request, "cmp_1").withObject("config").withArray("nodes"); nodes.removeAll();
        for (int index = 0; index < 6; index++) {
            nodes.addObject().put("nodeKey", "real_" + (index + 1)).put("datePrecision", "YEAR").put("date", "2018")
                    .put("title", "").put("description", "文".repeat(1000));
        }
        ObjectNode update = output("MODIFY_PAGE"); addOperation(request, update, "UPDATE_PARAMS", true, update("新"));
        assertThatThrownBy(() -> AiPortfolioProtocol.validate(request, update.toString())).isInstanceOf(AiProtocolException.class);
        nodes.forEach(value -> ((ObjectNode) value).put("description", "文".repeat(995)));
        ObjectNode payload = insert("🎉".repeat(20)); payload.withObject("node").put("title", "").put("description", "文".repeat(10));
        ObjectNode insert = output("MODIFY_PAGE"); addOperation(request, insert, "UPDATE_STRUCTURE", false, payload);
        assertThat(AiPortfolioProtocol.validate(request, insert.toString())).isEqualTo(insert);
        payload.withObject("node").put("description", "文".repeat(11));
        assertThatThrownBy(() -> AiPortfolioProtocol.validate(request, insert.toString())).isInstanceOf(AiProtocolException.class);
    }
}
