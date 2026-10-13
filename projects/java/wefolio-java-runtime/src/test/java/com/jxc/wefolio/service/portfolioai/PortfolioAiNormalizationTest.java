package com.jxc.wefolio.service.portfolioai;

import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.jxc.wefolio.config.PortfolioAiProperties;
import com.jxc.wefolio.mapper.WorkEntityMapper;
import com.jxc.wefolio.service.PortfolioConfigValidator;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

/** AI 候选规范化回归，覆盖三类文字行高的真实编译和普通配置校验。 */
class PortfolioAiNormalizationTest {
    /** 使用生产严格解析边界，保留 JSON 整数与小数表示。 */
    private ObjectNode node(String text) { return StrictAiJson.parseObject(text, 262144, 16); }

    /** 构造无外部资源依赖的新建请求，采用完整普通配置作为冻结基准。 */
    private ObjectNode request() {
        ObjectNode request = node("""
                {"input":{"ownerType":"USER","ownerId":"101","mode":"CREATE","targetMenuKey":"__root__",
                  "baseDraftRevision":0,"editorSchemaRevision":14,"prompt":"展示作品"},
                 "facts":{"sources":[],"bindings":[],"baseConfig":{"schemaVersion":"standard-personal-v1",
                  "editorSchemaRevision":14,"fonts":{},"share":{"title":"我的作品"},"components":[]}}}
                """);
        request.put("contractVersion", AiPortfolioProtocol.CONTRACT_VERSION).put("promptVersion", AiPortfolioProtocol.PROMPT_VERSION);
        return request;
    }

    /** 从真实中间协议编译到普通配置，整数行高规范化为同值小数仍然可以采用。 */
    @ParameterizedTest
    @CsvSource({"TEXT_SECTION,1", "TEXT_SECTION,2", "TEXT_SECTION,3", "TEXT_SECTION,1.2",
            "STRUCTURED_TEXT_SECTION,1", "STRUCTURED_TEXT_SECTION,2", "STRUCTURED_TEXT_SECTION,3", "STRUCTURED_TEXT_SECTION,1.2",
            "TEXT_GRID,1", "TEXT_GRID,2", "TEXT_GRID,3", "TEXT_GRID,1.2"})
    void exactLineHeightValueSurvivesRealOrdinaryNormalization(String type, String lineHeight) {
        ObjectNode request = request();
        ObjectNode output = node("""
                {"status":"CANDIDATE","mode":"CREATE","operations":[],"questions":[],"notes":[],
                 "page":{"menuKey":"menu_current","settings":{},"components":[]}}
                """);
        output.put("schemaVersion", AiPortfolioProtocol.CONTRACT_VERSION).put("promptVersion", AiPortfolioProtocol.PROMPT_VERSION);
        ObjectNode proposal = node("{\"type\":\"" + type + "\",\"sourceNos\":[],\"bindings\":[],\"params\":{}}");
        ObjectNode params = (ObjectNode) proposal.path("params");
        ObjectNode text = switch (type) {
            case "TEXT_SECTION" -> params.put("content", "记录瞬间");
            case "STRUCTURED_TEXT_SECTION" -> params.putArray("blocks").addObject()
                    .put("type", "PARAGRAPH").put("content", "记录瞬间");
            case "TEXT_GRID" -> params.put("rows", 1).put("columns", 1).putArray("cells").addObject()
                    .put("row", 0).put("column", 0).put("rowSpan", 1).put("columnSpan", 1).putArray("blocks").addObject();
            default -> throw new AssertionError(type);
        };
        if ("TEXT_GRID".equals(type)) { text.putArray("runs").addObject().put("text", "记录瞬间"); }
        text.set("lineHeight", node("{\"lineHeight\":" + lineHeight + "}").get("lineHeight"));
        ((ArrayNode) output.path("page").path("components")).add(proposal);
        ObjectNode candidate = PortfolioAiCompiler.compile(901L, request, output);
        PortfolioAiConfigService validation = new PortfolioAiConfigService(new PortfolioAiProperties(),
                new PortfolioConfigValidator(mock(WorkEntityMapper.class)), null, null, null, null);

        ObjectNode normalized = validation.validate((ObjectNode) request.path("input"), null, candidate);

        assertThat(normalized.path("components").size()).isEqualTo(1);
        assertThat(normalized.path("components").get(0).path("componentType").asText()).isEqualTo(type);
    }

    /** 等价数字仅用于行高，实际值变化、字符串强转和其它字段的类型转换仍然拒绝。 */
    @Test
    void lineHeightEquivalenceDoesNotPermitOtherChanges() {
        assertThatThrownBy(() -> PortfolioAiConfigService.requireExplicitValuesPreserved(
                node("{\"lineHeight\":2}"), node("{\"lineHeight\":2.1}"), ".components[].config"))
                .isInstanceOfSatisfying(PortfolioAiNormalizationException.class,
                        exception -> assertThat(exception.getCode()).isEqualTo("NORMALIZATION_VALUE_CHANGED"));
        assertThatThrownBy(() -> PortfolioAiConfigService.requireExplicitValuesPreserved(
                node("{\"lineHeight\":\"2\"}"), node("{\"lineHeight\":2.0}"), ".components[].config"))
                .isInstanceOfSatisfying(PortfolioAiNormalizationException.class,
                        exception -> assertThat(exception.getCode()).isEqualTo("NORMALIZATION_TYPE_CHANGED"));
        assertThatThrownBy(() -> PortfolioAiConfigService.requireExplicitValuesPreserved(
                node("{\"fontSizeRpx\":28}"), node("{\"fontSizeRpx\":28.0}"), ".components[].config"))
                .isInstanceOfSatisfying(PortfolioAiNormalizationException.class,
                        exception -> assertThat(exception.getCode()).isEqualTo("NORMALIZATION_VALUE_CHANGED"));
    }
}
