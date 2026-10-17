package com.jxc.wefolio.service.portfolioai;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.jxc.wefolio.service.PortfolioStructuredTextConfigSupport;
import com.jxc.wefolio.service.advancedportfolio.AdvancedPortfolioConfigGuard;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** 验证多段文字共用视频背景时，可信字体逐块编译并通过普通组件规则。 */
class PortfolioAiStructuredFontCompilerTest {
    /** 将编译候选交给真实普通文字规范化器。 */
    private static final ObjectMapper MAPPER = new ObjectMapper();
    /** 普通组件配置类型。 */
    private static final TypeReference<Map<String, Object>> CONFIG_TYPE = new TypeReference<>() { };

    /** 构造包含已有文字、视频素材与两个独立字体的冻结任务。 */
    private ObjectNode request(String owner, String mode) {
        ObjectNode request = node("""
                {"input":{"ownerType":"USER","ownerId":"101","mode":"CREATE","targetMenuKey":"__root__"},
                 "facts":{"baseConfig":{"schemaVersion":"standard-personal-v1","fonts":{},
                   "share":{"title":"手动名称"},"style":{"backgroundColor":"#000000"},
                   "components":[{"componentKey":"manual","componentType":"TEXT_SECTION","sortOrder":1000,
                     "enabled":true,"config":{"content":"保留原文","fontFamily":"SYSTEM"}}]},
                   "sources":[{"sourceNo":2,"kind":"WORK","sourceId":"102","memberUserId":"201",
                     "mediaType":"VIDEO","canBind":true}],
                   "bindings":[
                     {"ref":"font_body","kind":"FONT","allowedSlots":["fontId"],"reuseOnly":false,
                      "value":{"fontId":"LXGW_WENKAI","reference":{"fontVersion":"body-version"}}},
                     {"ref":"font_title","kind":"FONT","allowedSlots":["fontId"],"reuseOnly":false,
                      "value":{"fontId":"SOURCE_HAN_SERIF_SC","reference":{"fontVersion":"title-version"}}}]}}
                """);
        request.put("contractVersion", AiPortfolioProtocol.CONTRACT_VERSION).put("promptVersion", AiPortfolioProtocol.PROMPT_VERSION);
        request.withObject("input").put("ownerType", owner).put("mode", mode);
        if ("TEAM".equals(owner)) { request.withObject("facts").withObject("baseConfig").put("schemaVersion", "standard-team-v1"); }
        return request;
    }

    /** 同一背景包含独立标题字体、共享正文字体、内置字体和留白，覆盖混合选择。 */
    private ObjectNode proposal() {
        return node("""
                {"type":"STRUCTURED_TEXT_SECTION","sourceNos":[],
                 "params":{"backgroundEnabled":true,"backgroundTreatment":"DARK_MASK","blocks":[
                   {"type":"TITLE","content":"相逢有声","fontSizeRpx":44,
                    "bindings":[{"slot":"fontId","ref":"font_title"}]},
                   {"type":"PARAGRAPH","content":"让温柔落在每一次相逢里。","color":"#FFFFFF",
                    "bindings":[{"slot":"fontId","ref":"font_body"}]},
                   {"type":"PARAGRAPH","content":"把此刻的心动，留给往后的岁月。","lineHeight":1.6,
                    "bindings":[{"slot":"fontId","ref":"font_body"}]},
                   {"type":"HINT","content":"珍藏每一次相遇","fontFamily":"SYSTEM","bindings":[]},
                   {"type":"SPACER","heightRpx":32}]},
                 "bindings":[{"slot":"backgroundWorkId","sourceNo":2}]}
                """);
    }

    /** 使用同一提案构造首次创建或页面末尾插入，原有节点不参与重建。 */
    private ObjectNode output(ObjectNode request) {
        String mode = request.path("input").path("mode").asText();
        ObjectNode output = node("{\"status\":\"CANDIDATE\",\"page\":null,\"operations\":[],\"questions\":[],\"notes\":[]}");
        output.put("schemaVersion", AiPortfolioProtocol.CONTRACT_VERSION).put("promptVersion", AiPortfolioProtocol.PROMPT_VERSION).put("mode", mode);
        if ("CREATE".equals(mode)) {
            ObjectNode page = output.putObject("page").put("menuKey", "menu_current");
            page.putObject("settings"); page.putArray("components").add(proposal());
        } else {
            ObjectNode operation = output.withArray("operations").addObject().put("op", "INSERT_COMPONENT").put("reason", "添加多段文艺描述");
            operation.putObject("target").put("menuKey", "menu_current");
            operation.put("expectedHash", AiPortfolioProtocol.expectedHash(AiPortfolioProtocol.currentComponents(request)));
            operation.putObject("payload").put("position", "END").putNull("anchorComponentKey").set("component", proposal());
        }
        return output;
    }

    /** 个人和团队在创建及插入时都恢复逐块字体、冻结版本和同一视频背景，不残留 AI 字段。 */
    @ParameterizedTest
    @CsvSource({"USER,CREATE", "TEAM,CREATE", "USER,MODIFY_PAGE", "TEAM,MODIFY_PAGE"})
    void compilesStructuredFontsAndVideoBackground(String owner, String mode) {
        ObjectNode request = request(owner, mode), output = output(request);
        ObjectNode before = request.deepCopy(), originalOutput = output.deepCopy();
        ObjectNode candidate = PortfolioAiCompiler.compile(901L, request, output);
        int position = "CREATE".equals(mode) ? 0 : 1;
        assertThat(candidate.path("components")).hasSize(position + 1);
        JsonNode component = candidate.path("components").get(position), config = component.path("config"), blocks = config.path("blocks");
        assertThat(component.path("componentType").asText()).isEqualTo("STRUCTURED_TEXT_SECTION");
        assertThat(config.path("backgroundWorkId").asLong()).isEqualTo(102L);
        assertThat(config.path("backgroundMemberUserId").asLong()).isEqualTo("TEAM".equals(owner) ? 201L : 0L);
        assertThat(blocks.get(0).path("fontId").asText()).isEqualTo("SOURCE_HAN_SERIF_SC");
        for (int index : new int[]{1, 2}) { assertThat(blocks.get(index).path("fontId").asText()).isEqualTo("LXGW_WENKAI"); }
        assertThat(blocks.get(3).has("fontId")).isFalse();
        assertThat(blocks.get(4).has("fontId")).isFalse();
        for (JsonNode block : blocks) {
            assertThat(block.path("blockKey").asText()).startsWith("ai_901_1_");
            assertThat(block.has("bindings")).isFalse();
        }
        assertThat(candidate.path("fonts")).hasSize(2);
        assertThat(candidate.path("fonts").path("LXGW_WENKAI").path("fontVersion").asText()).isEqualTo("body-version");
        assertThat(candidate.path("fonts").path("SOURCE_HAN_SERIF_SC").path("fontVersion").asText()).isEqualTo("title-version");
        AdvancedPortfolioConfigGuard.validate(candidate, "TEAM".equals(owner));
        JsonNode normalized = MAPPER.valueToTree(PortfolioStructuredTextConfigSupport.normalize(MAPPER.convertValue(config, CONFIG_TYPE), "TEAM".equals(owner)));
        PortfolioAiConfigService.requireExplicitValuesPreserved(config, normalized, "");
        if (position == 1) {
            assertThat(candidate.path("components").get(0)).isEqualTo(AiPortfolioProtocol.baseConfig(request).path("components").get(0));
        }
        assertThat(request).isEqualTo(before);
        assertThat(output).isEqualTo(originalOutput);
        assertThat(PortfolioAiCompiler.compile(901L, request, output)).isEqualTo(candidate);
    }

    /** 新插入文字不能把未改动文字使用的固定字体版本替换成新版本。 */
    @Test
    void nestedFontCannotReplaceAnExistingPinnedVersion() {
        ObjectNode request = request("USER", "MODIFY_PAGE");
        ObjectNode base = AiPortfolioProtocol.baseConfig(request);
        ((ObjectNode) base.path("components").get(0).path("config")).put("fontId", "LXGW_WENKAI");
        base.withObject("fonts").putObject("LXGW_WENKAI").put("fontVersion", "original-version");
        ObjectNode original = base.deepCopy();
        assertThatThrownBy(() -> PortfolioAiCompiler.compile(902L, request, output(request))).isInstanceOf(RuntimeException.class);
        assertThat(base).isEqualTo(original);
    }

    /** 使用与生产一致的严格 JSON 解析器构造夹具。 */
    private ObjectNode node(String json) { return StrictAiJson.parseObject(json, StrictAiJson.RESPONSE_MAX_BYTES, StrictAiJson.MAX_DEPTH); }
}
