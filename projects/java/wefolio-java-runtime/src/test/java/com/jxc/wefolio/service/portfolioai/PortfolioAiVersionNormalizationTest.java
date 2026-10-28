package com.jxc.wefolio.service.portfolioai;

import com.fasterxml.jackson.databind.node.ObjectNode;
import com.jxc.wefolio.config.PortfolioAiProperties;
import com.jxc.wefolio.entity.PortfolioEntity;
import com.jxc.wefolio.exception.BusinessException;
import com.jxc.wefolio.mapper.WorkEntityMapper;
import com.jxc.wefolio.service.PortfolioConfigValidator;
import com.jxc.wefolio.service.PortfolioHyperlinkGraphService;
import com.jxc.wefolio.message.AdvancedPortfolioMessage;
import org.junit.jupiter.api.Test;
import java.util.List;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

/** 使用真实高级守卫和普通规范化验证版本原表示，查询不混入数据库正文。 */
class PortfolioAiVersionNormalizationTest {
    private ObjectNode node(String value) { return StrictAiJson.parseObject(value, 262144, 20); }
    private PortfolioAiConfigService service() {
        return new PortfolioAiConfigService(new PortfolioAiProperties(), new PortfolioConfigValidator(mock(WorkEntityMapper.class)),
                null, mock(PortfolioHyperlinkGraphService.class), null, null);
    }
    /** 测试按新任务实际形态冻结规格，再进入生产候选重验入口。 */
    private ObjectNode validateCandidate(ObjectNode input, PortfolioEntity portfolio, ObjectNode candidate, ObjectNode base) {
        var spec = AiPortfolioRunSpecRegistry.forVersion(AiPortfolioRunSpecRegistry.clientVersion(input));
        ObjectNode request = node("{\"facts\":{}}"); request.set("input", input);
        request.put("runSpecId", spec.id()).put("contractVersion", spec.contractVersion()).put("promptVersion", spec.promptVersion());
        request.withObject("facts").set("baseConfig", base);
        return service().validateCandidate(request, portfolio, candidate);
    }
    private ObjectNode input() { return node("""
            {"ownerType":"USER","ownerId":"101","portfolioId":null,"mode":"MODIFY_PAGE","baseDraftRevision":0,
             "clientAdvancedVersion":2,"editorSchemaRevision":15,"targetMenuKey":"__root__"}
            """); }
    private ObjectNode base() { return node("""
            {"schemaVersion":"standard-personal-v1","editorSchemaRevision":15,"fonts":{},"share":{"title":"作品"},
             "components":[{"componentKey":"text","componentType":"TEXT_SECTION","sortOrder":1000,"enabled":true,"config":{"content":"本地未保存文字"}}]}
            """); }
    @Test void unsavedModificationPreservesAbsentAndExplicitMetadataWithoutMutatingInput() {
        for (Integer version : new Integer[]{null, 1, 2}) {
            ObjectNode original = base(); if (version != null) { original.put("advancedPortfolioVersion", version); }
            ObjectNode before = original.deepCopy(); ObjectNode normalized = service().validate(input(), null, original);
            assertThat(original).isEqualTo(before);
            assertThat(normalized.get("advancedPortfolioVersion")).isEqualTo(original.get("advancedPortfolioVersion"));
            ObjectNode candidate = validateCandidate(input(), null, normalized, normalized.deepCopy());
            assertThat(candidate.get("advancedPortfolioVersion")).isEqualTo(original.get("advancedPortfolioVersion"));
        }
    }
    @Test void malformedUnsavedMetadataCannotBeHiddenByNormalization() {
        for (String value : List.of("null", "\"2\"", "true", "1.5", "0", "3", "99")) {
            ObjectNode base = base(); base.set("advancedPortfolioVersion", node("{\"value\":" + value + "}").get("value"));
            assertThatThrownBy(() -> service().validate(input(), null, base)).isInstanceOf(BusinessException.class);
        }
    }
    @Test void newCreateInitializesVersionButHistoricalCandidateKeepsMissingField() {
        ObjectNode input = input().put("mode", "CREATE");
        assertThat(service().initialConfig(input).path("advancedPortfolioVersion").asInt()).isEqualTo(2);
        ObjectNode oldInput = input(); oldInput.remove("clientAdvancedVersion"); oldInput.put("editorSchemaRevision", 14);
        ObjectNode historical = base().put("editorSchemaRevision", 14);
        assertThat(validateCandidate(oldInput, null, historical, historical).has("advancedPortfolioVersion")).isFalse();
    }
    @Test void persistedMetadataUsesDatabaseButKeepsUnsavedClientText() {
        PortfolioEntity portfolio = new PortfolioEntity(); portfolio.setId(7L); portfolio.setDraftConfigJson(base().put("advancedPortfolioVersion", 1).toString());
        ObjectNode input = input().put("portfolioId", "7");
        ObjectNode client = base().put("advancedPortfolioVersion", 2);
        ObjectNode normalized = service().validate(input, portfolio, client);
        assertThat(normalized.path("advancedPortfolioVersion").asInt()).isEqualTo(1);
        assertThat(normalized.path("components").get(0).path("config").path("content").asText()).isEqualTo("本地未保存文字");
        portfolio.setDraftConfigJson(base().put("advancedPortfolioVersion", 2).toString());
        ObjectNode old = input.deepCopy(); old.remove("clientAdvancedVersion"); old.put("editorSchemaRevision", 14);
        assertThatThrownBy(() -> service().validate(old, portfolio, base())).hasMessage(AdvancedPortfolioMessage.CLIENT_UPGRADE_REQUIRED);
        assertThat(validateCandidate(input, portfolio, normalized, normalized).path("advancedPortfolioVersion").asInt()).isEqualTo(1);
        ObjectNode changed = normalized.deepCopy().put("advancedPortfolioVersion", 2);
        assertThatThrownBy(() -> validateCandidate(input, portfolio, changed, normalized)).isInstanceOf(BusinessException.class);
    }
    @Test void textOnlyCreateThenUnsavedModifyRetainsVersionAndStableTimelineNodes() {
        ObjectNode input = input().put("mode", "CREATE").put("sourceMode", "TEXT_ONLY");
        ObjectNode request = node("{\"facts\":{\"sources\":[],\"bindings\":[]}}");
        request.set("input", input);
        var spec = AiPortfolioRunSpecRegistry.forVersion(2);
        request.put("runSpecId", spec.id()).put("contractVersion", spec.contractVersion()).put("promptVersion", spec.promptVersion());
        request.withObject("facts").set("baseConfig", service().initialConfig(input));
        ObjectNode output = node("""
                {"schemaVersion":"ai-portfolio-v2","promptVersion":"portfolio-ai-2026-10-28.1","status":"CANDIDATE","mode":"CREATE",
                 "page":{"menuKey":"menu_current","settings":{},"components":[{"type":"TIMELINE","sourceNos":[],"bindings":[],"params":{
                 "title":"经历","nodes":[{"datePrecision":"YEAR","date":"2018","title":"入行","description":""}]}}]},
                 "operations":[],"questions":[],"notes":[]}
                """);
        ObjectNode candidate = PortfolioAiCompiler.compile(123L, request, output);
        ObjectNode normalized = validateCandidate(input, null, candidate, AiPortfolioProtocol.baseConfig(request));
        assertThat(normalized.path("advancedPortfolioVersion").asInt()).isEqualTo(2);
        String key = normalized.path("components").get(0).path("config").path("nodes").get(0).path("nodeKey").asText();
        assertThat(key).isNotBlank();
        input.put("mode", "MODIFY_PAGE").put("sourceMode", "SELECTED_SOURCES");
        ObjectNode next = service().validate(input, null, normalized); request.withObject("facts").set("baseConfig", next);
        ObjectNode modification = node("""
                {"schemaVersion":"ai-portfolio-v2","promptVersion":"portfolio-ai-2026-10-28.1","status":"CANDIDATE","mode":"MODIFY_PAGE",
                 "page":null,"operations":[{"op":"UPDATE_TEXT","target":{"menuKey":"menu_current","componentKey":"cmp_1","nodeKey":"node_1"},
                 "expectedHash":"","payload":{"field":"title","value":"已调整的经历"},"reason":"调整标题"}],"questions":[],"notes":[]}
                """);
        ObjectNode operation = (ObjectNode) modification.path("operations").get(0);
        operation.put("expectedHash", AiPortfolioProtocol.expectedHash(AiTimelineProtocol.frozenNode(request, (ObjectNode) operation.path("target"))));
        ObjectNode modified = PortfolioAiCompiler.compile(124L, request, modification);
        ObjectNode verified = validateCandidate(input, null, modified, next);
        assertThat(verified.path("advancedPortfolioVersion").asInt()).isEqualTo(2);
        assertThat(verified.path("components").get(0).path("config").path("nodes").get(0).path("nodeKey").asText()).isEqualTo(key);
    }

    /** 自定义标签经过创建编译、普通规范化、投影和二次修改后仍保留原始空格。 */
    @Test void customLabelsSurviveCreateNormalizeAndIncrementalCompilation() {
        ObjectNode input = input().put("mode", "CREATE").put("sourceMode", "TEXT_ONLY");
        ObjectNode request = node("{\"facts\":{\"sources\":[],\"bindings\":[]}}");
        request.set("input", input);
        var spec = AiPortfolioRunSpecRegistry.forVersion(2);
        request.put("runSpecId", spec.id()).put("contractVersion", spec.contractVersion()).put("promptVersion", spec.promptVersion());
        request.withObject("facts").set("baseConfig", service().initialConfig(input));
        ObjectNode output = node("""
                {"schemaVersion":"ai-portfolio-v2","promptVersion":"portfolio-ai-2026-10-28.1","status":"CANDIDATE","mode":"CREATE",
                 "page":{"menuKey":"menu_current","settings":{},"components":[{"type":"TIMELINE","sourceNos":[],"bindings":[],"params":{
                 "title":"经历","nodes":[{"datePrecision":"CUSTOM","date":"  筹备  阶段.v1  ","title":"启程","description":""}]}}]},
                 "operations":[],"questions":[],"notes":[]}
                """);
        ObjectNode candidate = PortfolioAiCompiler.compile(125L, request, output);
        ObjectNode normalized = validateCandidate(input, null, candidate, AiPortfolioProtocol.baseConfig(request));
        assertThat(normalized.path("components").get(0).path("config").path("nodes").get(0).path("date").asText()).isEqualTo("  筹备  阶段.v1  ");
        String key = normalized.path("components").get(0).path("config").path("nodes").get(0).path("nodeKey").asText();
        input.put("mode", "MODIFY_PAGE").put("sourceMode", "SELECTED_SOURCES");
        ObjectNode next = service().validate(input, null, normalized);
        request.withObject("facts").set("baseConfig", next);
        assertThat(AiPortfolioProtocol.project(request).path("currentPage").path("components").get(0).path("params")
                .path("nodes").get(0).path("date").asText()).isEqualTo("  筹备  阶段.v1  ");
        ObjectNode modification = node("""
                {"schemaVersion":"ai-portfolio-v2","promptVersion":"portfolio-ai-2026-10-28.1","status":"CANDIDATE","mode":"MODIFY_PAGE",
                 "page":null,"operations":[{"op":"UPDATE_PARAMS","target":{"menuKey":"menu_current","componentKey":"cmp_1","nodeKey":"node_1"},
                 "expectedHash":"","payload":{"params":{"datePrecision":"CUSTOM","date":"  下一  程.v2  "}},"reason":"修改标记"},
                 {"op":"UPDATE_STRUCTURE","target":{"menuKey":"menu_current","componentKey":"cmp_1"},"expectedHash":"",
                 "payload":{"action":"INSERT_TIMELINE_NODE","position":"END","anchorNodeKey":null,
                 "node":{"datePrecision":"CUSTOM","date":"  未来  ","title":"继续","description":""}},"reason":"增加节点"}],"questions":[],"notes":[]}
                """);
        ObjectNode update = (ObjectNode) modification.path("operations").get(0);
        update.put("expectedHash", AiPortfolioProtocol.expectedHash(AiTimelineProtocol.hashBase(request, (ObjectNode) update.path("target"))));
        ((ObjectNode) modification.path("operations").get(1)).put("expectedHash", AiPortfolioProtocol.expectedHash(AiPortfolioProtocol.resolveComponent(request, "cmp_1")));
        ObjectNode modified = PortfolioAiCompiler.compile(126L, request, modification);
        ObjectNode verified = validateCandidate(input, null, modified, next);
        assertThat(verified.path("advancedPortfolioVersion").asInt()).isEqualTo(2);
        var nodes = verified.path("components").get(0).path("config").path("nodes");
        assertThat(nodes.get(0).path("nodeKey").asText()).isEqualTo(key);
        assertThat(nodes.get(0).path("date").asText()).isEqualTo("  下一  程.v2  ");
        assertThat(nodes.get(1).path("nodeKey").asText()).isNotBlank().isNotEqualTo(key);
        assertThat(nodes.get(1).path("date").asText()).isEqualTo("  未来  ");
        assertThat(verified.toString()).doesNotContain("customLabel");
        ObjectNode trimmed = verified.deepCopy();
        ((ObjectNode) trimmed.path("components").get(0).path("config").path("nodes").get(0)).put("date", "下一  程.v2");
        assertThatThrownBy(() -> PortfolioAiConfigService.requireExplicitValuesPreserved(verified, trimmed, ""))
                .isInstanceOf(PortfolioAiNormalizationException.class);
    }

    /** 字体兜底只允许相同远程身份；空角色和标题空白不能被规范化改写。 */
    @Test void timelineFidelityOnlyAllowsSameRemoteFontFallback() {
        ObjectNode before = node("{\"components\":[{\"componentType\":\"TIMELINE\",\"config\":{\"title\":\"  标题  \",\"textStyles\":{\"heading\":{\"fontId\":\"ALLURA\",\"fontFamily\":\"WECHAT_SANS_SS\"},\"date\":{}},\"nodes\":[{\"textStyles\":{\"title\":{}}}]}}]}");
        ObjectNode after = before.deepCopy();
        ((ObjectNode) after.path("components").get(0).path("config").path("textStyles").path("heading")).put("fontFamily", "SYSTEM");
        assertThatCode(() -> PortfolioAiConfigService.requireExplicitValuesPreserved(before, after, "")).doesNotThrowAnyException();
        ObjectNode added = after.deepCopy(); ((ObjectNode) added.path("components").get(0).path("config").path("textStyles").path("date")).put("fontFamily", "SYSTEM");
        assertThatThrownBy(() -> PortfolioAiConfigService.requireExplicitValuesPreserved(before, added, "")).isInstanceOf(PortfolioAiNormalizationException.class);
        ObjectNode trimmed = after.deepCopy(); ((ObjectNode) trimmed.path("components").get(0).path("config")).put("title", "标题");
        assertThatThrownBy(() -> PortfolioAiConfigService.requireExplicitValuesPreserved(before, trimmed, "")).isInstanceOf(PortfolioAiNormalizationException.class);
        ObjectNode swapped = after.deepCopy(); ((ObjectNode) swapped.path("components").get(0).path("config").path("textStyles").path("heading")).put("fontId", "OTHER");
        assertThatThrownBy(() -> PortfolioAiConfigService.requireExplicitValuesPreserved(before, swapped, "")).isInstanceOf(PortfolioAiNormalizationException.class);
    }
}
