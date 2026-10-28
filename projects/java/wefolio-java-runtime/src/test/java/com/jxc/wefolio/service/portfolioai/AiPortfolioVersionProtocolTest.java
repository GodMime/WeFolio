package com.jxc.wefolio.service.portfolioai;

import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import static org.assertj.core.api.Assertions.*;

/** 累计协议和时间轴按真实投影／输出校验覆盖，保留全部 V1 历史规格。 */
class AiPortfolioVersionProtocolTest {
    /** 已下线且未产生线上数据的旧 V2 运行规格。 */
    private static final String RETIRED_V2_SPEC = "portfolio-v2-2026-10-27.1";
    /** 旧 V2 提示词版本，不能与新规则组合使用。 */
    private static final String RETIRED_V2_PROMPT = "portfolio-ai-2026-10-27.1";

    /** CUSTOM 能力使用独立的新规格身份，冻结后的请求可直接解析。 */
    @Test void customLabelRulesHaveNewFrozenSpecIdentity() {
        var spec = AiPortfolioRunSpecRegistry.forVersion(2);
        assertThat(spec.id()).isEqualTo("portfolio-v2-2026-10-28.1");
        assertThat(spec.contractVersion()).isEqualTo("ai-portfolio-v2");
        assertThat(spec.promptVersion()).isEqualTo("portfolio-ai-2026-10-28.1");
        assertThat(spec.rules().path("promptVersion").asText()).isEqualTo(spec.promptVersion());
        ObjectNode request = request();
        request.put("contractVersion", spec.contractVersion()).put("promptVersion", spec.promptVersion())
                .put(AiPortfolioRunSpecRegistry.RULES_HASH, spec.rulesHash());
        assertThat(AiPortfolioRunSpecRegistry.resolve(request)).isEqualTo(spec);
    }

    /** 未登记的旧 V2 身份及跨规格组合都拒绝，不自动改用新规则。 */
    @Test void retiredV2IdentifiersCannotResolveToCustomLabelRules() {
        ObjectNode oldRequest = request().put("runSpecId", RETIRED_V2_SPEC).put("promptVersion", RETIRED_V2_PROMPT);
        assertThatThrownBy(() -> AiPortfolioRunSpecRegistry.resolve(oldRequest)).isInstanceOf(AiProtocolException.class);
        ObjectNode mixedRequest = request().put("promptVersion", RETIRED_V2_PROMPT);
        assertThatThrownBy(() -> AiPortfolioRunSpecRegistry.resolve(mixedRequest)).isInstanceOf(AiProtocolException.class);
    }

    /** 历史 V1 请求没有新版本字段和冻结规格时仍按原始版本对恢复。 */
    @Test void legacyV1WithoutFrozenSpecStillResolves() {
        ObjectNode request = request();
        request.remove("runSpecId");
        request.withObject("input").remove("clientAdvancedVersion");
        request.put("contractVersion", AiPortfolioProtocol.CONTRACT_VERSION).put("promptVersion", AiPortfolioProtocol.PROMPT_VERSION);
        assertThat(AiPortfolioRunSpecRegistry.resolve(request)).isEqualTo(AiPortfolioRunSpecRegistry.forVersion(1));
    }

    private static ObjectNode node(String json) { return StrictAiJson.parseObject(json, StrictAiJson.CONFIG_MAX_BYTES, StrictAiJson.MAX_DEPTH); }
    private ObjectNode request() {
        ObjectNode request = node("""
                {"contractVersion":"ai-portfolio-v2","promptVersion":"portfolio-ai-2026-10-28.1",
                 "input":{"clientAdvancedVersion":2,"sourceMode":"SELECTED_SOURCES","mode":"MODIFY_PAGE","ownerType":"USER","prompt":"调整时间轴","targetMenuKey":"__root__"},
                 "facts":{"sources":[],"bindings":[],"baseConfig":{"advancedPortfolioVersion":1,"components":[
                 {"componentKey":"timeline_real","componentType":"TIMELINE","sortOrder":1000,"enabled":true,"config":{
                 "title":"经历","variant":"MINIMAL","direction":"HORIZONTAL","showKicker":true,"minGapRpx":{"horizontal":320,"vertical":200},"textStyles":{},"nodes":[
                 {"nodeKey":"real_1","datePrecision":"YEAR","date":"2018","title":"开始","description":""},
                 {"nodeKey":"real_2","datePrecision":"DAY","date":"2024-02-29","title":"成长","description":""}]}}]}}}
                """);
        request.put("runSpecId", AiPortfolioRunSpecRegistry.forVersion(2).id()); return request;
    }
    private ObjectNode output() { return node("""
            {"schemaVersion":"ai-portfolio-v2","promptVersion":"portfolio-ai-2026-10-28.1","status":"CANDIDATE","mode":"MODIFY_PAGE","page":null,"operations":[],"questions":[],"notes":[]}
            """); }
    private ObjectNode target(String nodeKey, String role) {
        ObjectNode target = node("{\"menuKey\":\"menu_current\",\"componentKey\":\"cmp_1\"}");
        if (nodeKey != null) { target.put("nodeKey", nodeKey); } if (role != null) { target.put("textRole", role); } return target;
    }
    private void operation(ObjectNode output, ObjectNode request, String op, ObjectNode target, String payload) {
        ObjectNode value = output.withArray("operations").addObject().put("op", op).put("reason", "按要求调整");
        value.set("target", target);
        value.put("expectedHash", AiPortfolioProtocol.expectedHash(target.size() == 2
                ? AiPortfolioProtocol.resolveComponent(request, "cmp_1") : AiTimelineProtocol.hashBase(request, target)));
        value.set("payload", node(payload));
    }
    @Test void frozenSpecsKeepHistoricalPairAndRejectMismatch() {
        ObjectNode request = request();
        assertThat(AiPortfolioRunSpecRegistry.resolve(request).advancedVersion()).isEqualTo(2);
        request.remove("runSpecId"); assertThatThrownBy(() -> AiPortfolioRunSpecRegistry.resolve(request)).isInstanceOf(AiProtocolException.class);
        request.put("runSpecId", AiPortfolioRunSpecRegistry.forVersion(1).id());
        assertThatThrownBy(() -> AiPortfolioProtocol.checkVersion(request)).isInstanceOf(AiProtocolException.class);
        request.remove("runSpecId"); request.put("promptVersion", "future");
        assertThatThrownBy(() -> AiPortfolioProtocol.checkVersion(request)).isInstanceOf(AiProtocolException.class);
    }
    @Test void catalogIsCumulativeAndTextOnlyRestrictsBindings() {
        ObjectNode request = request();
        var v2 = AiPortfolioProtocol.contract(request);
        request.withObject("input").remove("clientAdvancedVersion");
        request.put("contractVersion", AiPortfolioProtocol.CONTRACT_VERSION).put("promptVersion", AiPortfolioProtocol.PROMPT_VERSION).remove("runSpecId");
        var v1 = AiPortfolioProtocol.contract(request);
        v1.path("componentCatalog").fields().forEachRemaining(entry -> assertThat(v2.path("componentCatalog").path(entry.getKey())).isEqualTo(entry.getValue()));
        assertThat(v1.path("componentCatalog").has("TIMELINE")).isFalse();
        request = request(); request.withObject("input").put("mode", "CREATE").put("sourceMode", "TEXT_ONLY");
        var catalog = AiPortfolioProtocol.contract(request).path("componentCatalog");
        assertThat(catalog.size()).isEqualTo(4);
        catalog.forEach(component -> component.path("bindingSlots").fieldNames().forEachRemaining(slot -> assertThat(slot).isEqualTo("fontId")));
    }
    @Test void projectionIncludesMissingRolesAndHidesRealKeys() {
        var projected = AiPortfolioProtocol.project(request());
        assertThat(projected.toString()).doesNotContain("timeline_real", "real_1", "real_2", "advancedPortfolioVersion");
        var params = projected.path("currentPage").path("components").get(0).path("params");
        assertThat(params.path("textStyles").size()).isEqualTo(4);
        assertThat(params.path("nodes").get(0).path("textStyles").size()).isEqualTo(3);
        assertThat(params.path("textStyles").path("heading").path("expectedHash").asText()).isNotBlank();
    }
    @Test void stableNodeIdentitySurvivesMoveThenTextAndStyleChanges() {
        ObjectNode request = request(), output = output();
        operation(output, request, "UPDATE_STRUCTURE", target("node_2", null), "{\"action\":\"MOVE_TIMELINE_NODE\",\"position\":\"START\",\"anchorNodeKey\":null}");
        operation(output, request, "UPDATE_TEXT", target("node_2", null), "{\"field\":\"title\",\"value\":\"保持第二节点身份\"}");
        operation(output, request, "UPDATE_PARAMS", target("node_2", "title"), "{\"params\":{\"fontSizeRpx\":36,\"color\":\"AUTO\"}}");
        assertThat(AiPortfolioProtocol.validate(request, output.toString())).isEqualTo(output);
        ObjectNode component = AiPortfolioProtocol.resolveComponent(request, "cmp_1").deepCopy();
        output.path("operations").forEach(op -> AiTimelineProtocol.apply(request, component, (ObjectNode) op, "new_key"));
        assertThat(component.path("config").path("nodes").get(0).path("nodeKey").asText()).isEqualTo("real_2");
        assertThat(component.path("config").path("nodes").get(0).path("title").asText()).isEqualTo("保持第二节点身份");
        assertThat(component.path("config").path("nodes").get(1).has("textStyles")).isFalse();
    }
    @Test void dateMutationRequiresRealDateAndPairedPrecision() {
        ObjectNode request = request();
        for (String params : List.of("{\"date\":\"2024\"}", "{\"datePrecision\":\"DAY\",\"date\":\"2023-02-29\"}", "{\"datePrecision\":\"YEAR\",\"date\":\"0000\"}")) {
            ObjectNode output = output(); operation(output, request, "UPDATE_PARAMS", target("node_1", null), "{\"params\":" + params + "}");
            assertThatThrownBy(() -> AiPortfolioProtocol.validate(request, output.toString())).isInstanceOf(AiProtocolException.class);
        }
    }
    @Test void deletionThenMutationAndEmptyFinalTimelineAreRejected() {
        ObjectNode request = request(), output = output();
        operation(output, request, "UPDATE_STRUCTURE", target("node_1", null), "{\"action\":\"REMOVE_TIMELINE_NODE\"}");
        operation(output, request, "UPDATE_TEXT", target("node_1", null), "{\"field\":\"title\",\"value\":\"不能修改\"}");
        assertThatThrownBy(() -> AiPortfolioProtocol.validate(request, output.toString())).isInstanceOf(AiProtocolException.class);
        output.withArray("operations").remove(1);
        operation(output, request, "UPDATE_STRUCTURE", target("node_2", null), "{\"action\":\"REMOVE_TIMELINE_NODE\"}");
        assertThatThrownBy(() -> AiPortfolioProtocol.validate(request, output.toString())).isInstanceOf(AiProtocolException.class);
    }
    @Test void resetAndBuiltInFontPreserveOtherFields() {
        ObjectNode request = request(); ObjectNode component = AiPortfolioProtocol.resolveComponent(request, "cmp_1");
        component.withObject("config").withObject("textStyles").putObject("heading").put("fontId", "font_old").put("fontFamily", "SYSTEM").put("fontSizeRpx", 44).put("color", "#ffffff");
        ObjectNode output = output(); operation(output, request, "UPDATE_PARAMS", target(null, "heading"), "{\"params\":{\"fontFamily\":\"WECHAT_SANS_SS\"}}");
        assertThat(AiPortfolioProtocol.validate(request, output.toString())).isEqualTo(output);
        ObjectNode candidate = component.deepCopy(); AiTimelineProtocol.apply(request, candidate, (ObjectNode) output.path("operations").get(0), "new");
        assertThat(candidate.path("config").path("textStyles").path("heading").has("fontId")).isFalse();
        assertThat(candidate.path("config").path("textStyles").path("heading").path("fontSizeRpx").asInt()).isEqualTo(44);
        output = output(); operation(output, request, "UPDATE_STRUCTURE", target(null, "heading"), "{\"action\":\"RESET_TIMELINE_TEXT_STYLE\",\"fields\":[\"font\"]}");
        AiTimelineProtocol.apply(request, candidate, (ObjectNode) output.path("operations").get(0), "new");
        assertThat(candidate.path("config").path("textStyles").path("heading").has("fontFamily")).isFalse();
        assertThat(candidate.path("config").path("textStyles").path("heading").path("color").asText()).isEqualTo("#ffffff");
    }
    @Test void nestedMetadataAndUnknownTargetRolesAreRejected() {
        ObjectNode request = request(), output = output();
        operation(output, request, "UPDATE_PARAMS", target("node_1", "heading"), "{\"params\":{\"color\":\"AUTO\"}}");
        assertThatThrownBy(() -> AiPortfolioProtocol.validate(request, output.toString())).isInstanceOf(AiProtocolException.class);
    }
    @Test void runtimeAndJobIncrementalDefinitionsRemainIdentical() throws Exception {
        Path root = Path.of("").toAbsolutePath().getParent();
        for (String file : List.of("AiPortfolioRunSpecRegistry.java", "AiTimelineProtocol.java")) {
            String relative = "src/main/java/com/jxc/wefolio/service/portfolioai/" + file;
            assertThat(Files.readString(root.resolve("wefolio-java-runtime/" + relative))).isEqualTo(Files.readString(root.resolve("wefolio-java-job/" + relative)));
        }
        String relative = "src/main/resources/portfolioai/contract-v2.json";
        assertThat(Files.readString(root.resolve("wefolio-java-runtime/" + relative))).isEqualTo(Files.readString(root.resolve("wefolio-java-job/" + relative)));
    }

    @Test void newNodeAliasesDoNotChangeV1VisibleTextContract() {
        ObjectNode request = request(); request.withObject("facts").withObject("baseConfig").withArray("components").removeAll();
        ObjectNode output = output(); output.withArray("notes").add("原文 node_1");
        assertThatThrownBy(() -> AiPortfolioProtocol.validate(request, output.toString())).isInstanceOf(AiProtocolException.class);
        request.remove("runSpecId"); request.withObject("input").remove("clientAdvancedVersion");
        request.put("contractVersion", AiPortfolioProtocol.CONTRACT_VERSION).put("promptVersion", AiPortfolioProtocol.PROMPT_VERSION);
        output.put("schemaVersion", AiPortfolioProtocol.CONTRACT_VERSION).put("promptVersion", AiPortfolioProtocol.PROMPT_VERSION);
        assertThat(AiPortfolioProtocol.validate(request, output.toString())).isEqualTo(output);
    }

    @Test void nestedFontBindingAcceptsSystemFallbackAndEnforcesOriginalRole() {
        ObjectNode request = request();
        ObjectNode resource = request.withObject("facts").withArray("bindings").addObject().put("ref", "font_1").put("kind", "FONT").put("reuseOnly", false);
        resource.putArray("allowedSlots").add("fontId"); resource.putObject("value").put("fontId", "ALLURA").putObject("reference").put("fontVersion", "2026-01");
        request.withObject("input").put("mode", "CREATE");
        ObjectNode output = output().put("mode", "CREATE");
        ObjectNode proposal = node("""
                {"type":"TIMELINE","sourceNos":[],"bindings":[],"params":{"title":"经历","textStyles":{"heading":{"fontFamily":"SYSTEM","bindings":[{"slot":"fontId","ref":"font_1"}]}},
                 "nodes":[{"datePrecision":"YEAR","date":"2018","title":"开始","description":""}]}}
                """);
        output.putObject("page").put("menuKey", "menu_current").putArray("components").add(proposal); output.withObject("page").putObject("settings");
        assertThat(AiPortfolioProtocol.validate(request, output.toString())).isEqualTo(output);
        proposal.withObject("params").withObject("textStyles").withObject("heading").put("fontFamily", "WECHAT_SANS_SS");
        assertThatThrownBy(() -> AiPortfolioProtocol.validate(request, output.toString())).isInstanceOf(AiProtocolException.class);
        resource.put("reuseOnly", true).put("componentKey", "timeline_real").put("textRole", "heading");
        request.withObject("input").put("mode", "MODIFY_PAGE");
        ObjectNode modification = output();
        operation(modification, request, "BIND_RESOURCES", target(null, "heading"), "{\"sourceNos\":[],\"bindings\":[{\"slot\":\"fontId\",\"ref\":\"font_1\"}]}");
        assertThat(AiPortfolioProtocol.validate(request, modification.toString())).isEqualTo(modification);
        ObjectNode invalid = output();
        operation(invalid, request, "BIND_RESOURCES", target(null, "title"), "{\"sourceNos\":[],\"bindings\":[{\"slot\":\"fontId\",\"ref\":\"font_1\"}]}");
        assertThatThrownBy(() -> AiPortfolioProtocol.validate(request, invalid.toString())).isInstanceOf(AiProtocolException.class);
    }
}
