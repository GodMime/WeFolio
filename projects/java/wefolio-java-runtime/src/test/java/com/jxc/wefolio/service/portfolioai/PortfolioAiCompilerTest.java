package com.jxc.wefolio.service.portfolioai;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.jxc.wefolio.exception.BusinessException;
import org.junit.jupiter.api.Test;
import java.util.List;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** 编译器验证真实模式作用范围、稳定身份、内部文字及来源转译。 */
class PortfolioAiCompilerTest {
    /** 使用生产解析器构建受控测试节点。 */
    private ObjectNode node(String text) { return StrictAiJson.parseObject(text, 262144, 16); }
    /** 固定完整基准，保留真实普通分享、样式、音乐和导航。 */
    private ObjectNode request(String mode) {
        ObjectNode result = node("{\"input\":{\"ownerType\":\"USER\",\"ownerId\":\"101\",\"mode\":\"" + mode + "\",\"targetMenuKey\":\"__root__\",\"prompt\":\"精简文字\"},\"facts\":{\"sources\":[],\"bindings\":[]}}");
        result.put("contractVersion", AiPortfolioProtocol.CONTRACT_VERSION).put("promptVersion", AiPortfolioProtocol.PROMPT_VERSION);
        ((ObjectNode) result.path("facts")).set("baseConfig", node("""
                {"schemaVersion":"standard-personal-v1","editorSchemaRevision":14,"fonts":{},
                 "share":{"title":"我的作品","avatarUrl":""},"style":{"backgroundColor":"#FFFFFF","componentSpacingRpx":32},
                 "backgroundAudio":{"enabled":false,"workId":null,"displayStyle":"DISC"},"bottomNav":{"enabled":false,"items":[]},
                 "components":[{"componentKey":"intro","componentType":"TEXT_SECTION","sortOrder":1000,"enabled":true,
                  "config":{"content":"原始介绍","fontSizeRpx":32,"alignment":"CENTER","fontFamily":"SYSTEM","color":"#333333"}},
                  {"componentKey":"second","componentType":"TEXT_SECTION","sortOrder":2000,"enabled":true,"config":{"content":"第二段"}}]}
                """));
        return result;
    }
    /** 标准八字段信封，不将响应派生字段夹带进协议。 */
    private ObjectNode output(ObjectNode request) {
        ObjectNode result = node("{\"status\":\"CANDIDATE\",\"page\":null,\"operations\":[],\"questions\":[],\"notes\":[]}");
        result.put("schemaVersion", AiPortfolioProtocol.CONTRACT_VERSION).put("promptVersion", AiPortfolioProtocol.PROMPT_VERSION)
                .put("mode", request.path("input").path("mode").asText());
        return result;
    }
    /** 创建目标组件操作，hash 始终取同一不可变基准。 */
    private ObjectNode operation(ObjectNode request, ObjectNode output, String op, String alias, ObjectNode payload) {
        ObjectNode result = ((ArrayNode) output.path("operations")).addObject().put("op", op).put("reason", "调整当前内容");
        ObjectNode target = result.putObject("target").put("menuKey", "menu_current");
        if (alias != null) { target.put("componentKey", alias); }
        result.put("expectedHash", AiPortfolioProtocol.expectedHash(alias == null ? AiPortfolioProtocol.currentComponents(request) : AiPortfolioProtocol.resolveComponent(request, alias)));
        result.set("payload", payload); return result;
    }

    /** 文字修改完整保留手动字号、对齐、身份、其它组件及全局设置。 */
    @Test void textOnlyPreservesManualSettingsAndOtherComponents() {
        ObjectNode request = request("MODIFY_PAGE"), output = output(request);
        operation(request, output, "UPDATE_TEXT", "cmp_1", node("{\"field\":\"content\",\"value\":\"简短介绍\"}"));
        ObjectNode candidate = PortfolioAiCompiler.compile(701L, request, output);
        ObjectNode expected = AiPortfolioProtocol.baseConfig(request).deepCopy();
        ((ObjectNode) expected.path("components").get(0).path("config")).put("content", "简短介绍");
        assertThat(candidate).isEqualTo(expected);
        assertThat(AiPortfolioProtocol.baseConfig(request).path("components").get(0).path("config").path("content").asText()).isEqualTo("原始介绍");
    }

    /** 空操作与相同文字均是合法实际无改动，不能制造新的节点。 */
    @Test void noOpAndIdenticalTextKeepExactConfig() {
        ObjectNode request = request("MODIFY_PAGE"), output = output(request);
        assertThat(PortfolioAiCompiler.compile(702L, request, output)).isEqualTo(AiPortfolioProtocol.baseConfig(request));
        operation(request, output, "UPDATE_TEXT", "cmp_1", node("{\"field\":\"content\",\"value\":\"原始介绍\"}"));
        assertThat(PortfolioAiCompiler.compile(702L, request, output)).isEqualTo(AiPortfolioProtocol.baseConfig(request));
    }

    /** 移动保留组件集合和配置，只产生明确派生顺序。 */
    @Test void movingPreservesContentAndIdentity() {
        ObjectNode request = request("MODIFY_PAGE"), output = output(request);
        operation(request, output, "MOVE_COMPONENT", "cmp_2", node("{\"position\":\"START\",\"anchorComponentKey\":null}"));
        ObjectNode candidate = PortfolioAiCompiler.compile(703L, request, output);
        assertThat(candidate.path("components").get(0).path("componentKey").asText()).isEqualTo("second");
        assertThat(candidate.path("components").get(0).path("config")).isEqualTo(AiPortfolioProtocol.baseConfig(request).path("components").get(1).path("config"));
        assertThat(candidate.path("components").get(1).path("config")).isEqualTo(AiPortfolioProtocol.baseConfig(request).path("components").get(0).path("config"));
        assertThat(candidate.path("components").size()).isEqualTo(2);
    }

    /** 整体描述修改次级菜单时，不改变首页、导航、分享、音乐或其它菜单。 */
    @Test void pageModificationTouchesOnlySelectedMenu() {
        ObjectNode request = request("MODIFY_PAGE");
        ObjectNode base = AiPortfolioProtocol.baseConfig(request);
        base.set("bottomNav", node("{\"enabled\":true,\"items\":[{\"key\":\"nav_home\",\"title\":\"首页\",\"components\":[]},{\"key\":\"nav_more\",\"title\":\"更多\",\"components\":[{\"componentKey\":\"more\",\"componentType\":\"TEXT_SECTION\",\"sortOrder\":1000,\"enabled\":true,\"config\":{\"content\":\"旧次页\"}}]}]}"));
        ((ObjectNode) request.path("input")).put("targetMenuKey", "nav_more");
        ObjectNode output = output(request);
        operation(request, output, "UPDATE_TEXT", "cmp_1", node("{\"field\":\"content\",\"value\":\"新次页\"}"));
        ObjectNode candidate = PortfolioAiCompiler.compile(704L, request, output);
        assertThat(candidate.path("components")).isEqualTo(base.path("components"));
        for (String section : List.of("share", "style", "backgroundAudio")) { assertThat(candidate.path(section)).isEqualTo(base.path(section)); }
        assertThat(candidate.path("bottomNav").path("items").get(0)).isEqualTo(base.path("bottomNav").path("items").get(0));
        assertThat(candidate.path("bottomNav").path("items").get(1).path("components").get(0).path("config").path("content").asText()).isEqualTo("新次页");
        assertThat(PortfolioAiCompiler.compile(704L, request, output)).isEqualTo(candidate);
    }

    /** 来源号允许断号，服务端恢复真实成员身份与作品 ID。 */
    @Test void teamSourceNumbersCompileRealAuthorizedIdentities() {
        ObjectNode request = request("CREATE");
        ((ObjectNode) request.path("input")).put("ownerType", "TEAM");
        ((ArrayNode) request.path("facts").path("sources")).add(node("{\"sourceNo\":4,\"kind\":\"WORK\",\"sourceId\":\"812\",\"memberUserId\":\"117\",\"mediaType\":\"IMAGE\",\"title\":\"婚礼照片\"}"));
        ObjectNode output = output(request);
        output.set("page", node("{\"menuKey\":\"menu_current\",\"settings\":{},\"components\":[{\"type\":\"SINGLE_WORK\",\"sourceNos\":[4],\"params\":{\"showTitle\":false},\"bindings\":[]}]}"));
        JsonNode config = PortfolioAiCompiler.compile(705L, request, output).path("components").get(0).path("config");
        assertThat(config.path("workId").longValue()).isEqualTo(812L);
        assertThat(config.path("memberUserId").longValue()).isEqualTo(117L);
        assertThat(config.path("showTitle").booleanValue()).isFalse();
    }

    /** 删除与内部文字修改的重叠整份拒绝。 */
    @Test void removingModifiedComponentRejectsWholeCandidate() {
        ObjectNode request = request("MODIFY_PAGE"), output = output(request);
        operation(request, output, "UPDATE_TEXT", "cmp_1", node("{\"field\":\"content\",\"value\":\"新文案\"}"));
        operation(request, output, "REMOVE_COMPONENT", "cmp_1", JsonNodeFactory.instance.objectNode());
        assertThatThrownBy(() -> PortfolioAiCompiler.compile(706L, request, output)).isInstanceOf(RuntimeException.class);
        assertThat(AiPortfolioProtocol.baseConfig(request).path("components").size()).isEqualTo(2);
    }

    /** 整体修改也不能通过伪造组件别名触及目标菜单外的配置。 */
    @Test void pageModificationCannotEscapeTargetMenu() {
        ObjectNode request = request("MODIFY_PAGE"), output = output(request);
        operation(request, output, "MOVE_COMPONENT", "cmp_1", node("{\"position\":\"END\",\"anchorComponentKey\":null}"));
        ((ObjectNode) output.path("operations").get(0).path("target")).put("componentKey", "another-menu-key");
        assertThatThrownBy(() -> PortfolioAiCompiler.compile(707L, request, output)).isInstanceOf(AiProtocolException.class);
    }

    /** 手动字段不能通过普通规范化的默认值回退掩盖非法 AI 参数。 */
    @Test void normalizationCannotDropFieldsOrCoerceTypes() {
        assertThatThrownBy(() -> PortfolioAiConfigService.requireExplicitValuesPreserved(node("{\"showTitle\":\"false\"}"), node("{\"showTitle\":true}"), ""))
                .isInstanceOf(RuntimeException.class);
        assertThatThrownBy(() -> PortfolioAiConfigService.requireExplicitValuesPreserved(node("{\"content\":\"原文\",\"alien\":true}"), node("{\"content\":\"原文\"}"), ""))
                .isInstanceOf(RuntimeException.class);
    }

    /** 规范化拒绝带受控分类，不包含未知字段名或用户值，查询可解释具体失败原因。 */
    @Test void normalizationFailureExplainsDroppedFieldChangedTypeAndChangedValueSafely() {
        assertThatThrownBy(() -> PortfolioAiConfigService.requireExplicitValuesPreserved(node("{\"内部字段\":\"敏感原文\"}"), node("{}"), ""))
                .isInstanceOfSatisfying(PortfolioAiNormalizationException.class, exception -> assertThat(exception.getCode()).isEqualTo("NORMALIZATION_FIELD_DROPPED"))
                .hasMessageNotContaining("内部字段").hasMessageNotContaining("敏感原文");
        assertThatThrownBy(() -> PortfolioAiConfigService.requireExplicitValuesPreserved(node("{\"showTitle\":\"false\"}"), node("{\"showTitle\":true}"), ""))
                .isInstanceOfSatisfying(PortfolioAiNormalizationException.class, exception -> assertThat(exception.getCode()).isEqualTo("NORMALIZATION_TYPE_CHANGED"));
        assertThatThrownBy(() -> PortfolioAiConfigService.requireExplicitValuesPreserved(node("{\"content\":\"敏感原文\"}"), node("{\"content\":\"替换原文\"}"), ""))
                .isInstanceOfSatisfying(PortfolioAiNormalizationException.class, exception -> assertThat(exception.getCode()).isEqualTo("NORMALIZATION_VALUE_CHANGED"))
                .hasMessageNotContaining("敏感原文").hasMessageNotContaining("替换原文");
        assertThatThrownBy(() -> PortfolioAiConfigService.requireExplicitValuesPreserved(node("{\"items\":[1,2]}"), node("{\"items\":[1]}"), ""))
                .isInstanceOf(BusinessException.class)
                .isInstanceOfSatisfying(PortfolioAiNormalizationException.class, exception -> assertThat(exception.getCode()).isEqualTo("NORMALIZATION_ARRAY_CHANGED"));
    }

    /** 选择同一字体的新版本不能连带替换其它菜单或组件原来的固定版本。 */
    @Test void fontCatalogCannotReplaceVersionUsedByUntouchedNodes() {
        ObjectNode request = request("MODIFY_PAGE"), output = output(request);
        ObjectNode base = AiPortfolioProtocol.baseConfig(request);
        base.set("fonts", node("{\"DEMO\":{\"fontVersion\":\"v1\"}}"));
        ((ObjectNode) base.path("components").get(1).path("config")).put("fontId", "DEMO");
        ObjectNode binding = ((ArrayNode) request.path("facts").path("bindings")).addObject()
                .put("ref", "font_1").put("kind", "FONT").put("reuseOnly", false);
        binding.putArray("allowedSlots").add("fontId");
        binding.set("value", node("{\"fontId\":\"DEMO\",\"reference\":{\"fontVersion\":\"v2\"}}"));
        operation(request, output, "BIND_RESOURCES", "cmp_1", node("{\"sourceNos\":[],\"bindings\":[{\"slot\":\"fontId\",\"ref\":\"font_1\"}]}"));
        assertThatThrownBy(() -> PortfolioAiCompiler.compile(708L, request, output)).isInstanceOf(RuntimeException.class);
        assertThat(base.path("fonts").path("DEMO").path("fontVersion").asText()).isEqualTo("v1");
    }

    /** 已有分组回传相同号应完整无改动，数量一致的重绑保持组身份和名称。 */
    @Test void groupedWorkRebindingPreservesManualGroupIdentity() {
        ObjectNode request = request("MODIFY_PAGE");
        ObjectNode component = (ObjectNode) AiPortfolioProtocol.baseConfig(request).path("components").get(0);
        component.put("componentType", "WORK_GRID");
        component.set("config", node("{\"columns\":2,\"groups\":[{\"groupKey\":\"warm\",\"name\":\"温暖仪式\",\"sortOrder\":1000,\"workIds\":[801]},{\"groupKey\":\"night\",\"name\":\"夜晚\",\"sortOrder\":2000,\"workIds\":[802]}]}"));
        for (int i = 0; i < 3; i++) {
            ((ArrayNode) request.path("facts").path("sources")).addObject().put("sourceNo", i + 1)
                    .put("kind", "WORK").put("sourceId", String.valueOf(801 + i)).put("mediaType", "IMAGE");
        }
        ObjectNode unchanged = output(request);
        operation(request, unchanged, "BIND_RESOURCES", "cmp_1", node("{\"sourceNos\":[1,2],\"bindings\":[]}"));
        assertThat(PortfolioAiCompiler.compile(709L, request, unchanged)).isEqualTo(AiPortfolioProtocol.baseConfig(request));
        ObjectNode rebound = output(request);
        operation(request, rebound, "BIND_RESOURCES", "cmp_1", node("{\"sourceNos\":[3,2],\"bindings\":[]}"));
        JsonNode groups = PortfolioAiCompiler.compile(709L, request, rebound).path("components").get(0).path("config").path("groups");
        assertThat(groups.get(0).path("groupKey").asText()).isEqualTo("warm");
        assertThat(groups.get(0).path("name").asText()).isEqualTo("温暖仪式");
        assertThat(groups.get(0).path("workIds").get(0).asLong()).isEqualTo(803);
        ObjectNode unsupported = output(request);
        operation(request, unsupported, "BIND_RESOURCES", "cmp_1", node("{\"sourceNos\":[1],\"bindings\":[]}"));
        assertThatThrownBy(() -> PortfolioAiCompiler.compile(709L, request, unsupported)).isInstanceOf(RuntimeException.class);
    }
}
