package com.jxc.wefolio.service.portfolioai;

import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static org.assertj.core.api.Assertions.assertThat;

/** 新建基准只用于服务端规范化，不能被模型误认为用户要求保留的正文。 */
class PortfolioAiCreateProjectionTest {
    /** 用户已经指定完整布局，没有提出资料组件需求。 */
    private static final String REPORTED_PROMPT = """
            1号作品作为作品集封面
            2号作品当背景，配合一段文艺的主持描述，字体要文艺
            3号作品单独展示
            4号作为背景音乐
            后面加一个档期查询
            最后再加一个预留联系信息
            整体背景使用黑色
            """;

    /** 按新建冻结请求结构构造默认资料基准，覆盖已排队任务仍含占位组件的情况。 */
    private ObjectNode request(String mode, String ownerType, String componentType) {
        ObjectNode request = StrictAiJson.parseObject("""
                {"input":{"ownerType":"USER","ownerId":"101","mode":"CREATE","targetMenuKey":"__root__"},
                 "facts":{"sources":[],"bindings":[],"profile":{"displayName":"演示主持人","bio":"记录相逢"},
                  "baseConfig":{"schemaVersion":"standard-personal-v1","editorSchemaRevision":14,"fonts":{},
                   "share":{"title":"个人作品集"},"components":[
                    {"componentKey":"initial_profile","componentType":"PROFILE","sortOrder":1000,"enabled":true,
                     "config":{"profile":{"displayName":"演示主持人","bio":"记录相逢"}}}]}}}
                """, StrictAiJson.CONFIG_MAX_BYTES, StrictAiJson.MAX_DEPTH);
        request.put("contractVersion", AiPortfolioProtocol.CONTRACT_VERSION).put("promptVersion", AiPortfolioProtocol.PROMPT_VERSION);
        request.withObject("input").put("mode", mode).put("ownerType", ownerType).put("prompt", REPORTED_PROMPT);
        ((ObjectNode) AiPortfolioProtocol.currentComponents(request).get(0)).put("componentType", componentType);
        return request;
    }

    /** 个人与团队的新建请求都不得将初始化用的资料占位组件投影为既有正文。 */
    @ParameterizedTest
    @CsvSource({"USER,PROFILE", "TEAM,TEAM_PROFILE"})
    void createDoesNotSendDefaultProfileAsExistingPage(String ownerType, String componentType) {
        ObjectNode request = request("CREATE", ownerType, componentType);
        ObjectNode projected = AiPortfolioProtocol.project(request);

        assertThat(projected.path("userIntent").asText()).isEqualTo(REPORTED_PROMPT);
        assertThat(projected.path("currentPage").path("components"))
                .as("CREATE 只应按用户描述生成，初始化资料组件不应成为既有正文")
                .isEmpty();
        assertThat(AiPortfolioProtocol.currentComponents(request)).hasSize(1);
        assertThat(projected.path("facts")).isNotEmpty();
        assertThat(projected.path("globalState").path("share").path("params").path("title").asText())
                .isEqualTo("个人作品集");
    }

    /** 隐藏初始化正文不收窄资料能力，用户明确要求时仍可合法创建资料组件。 */
    @ParameterizedTest
    @CsvSource({"USER,PROFILE,profile", "TEAM,TEAM_PROFILE,team"})
    void createStillAllowsRequestedProfile(String ownerType, String componentType, String slot) {
        ObjectNode request = request("CREATE", ownerType, componentType);
        request.withObject("facts").put("safePrompt", "顶部展示基础资料和姓名头像名片");
        ObjectNode binding = request.withObject("facts").withArray("bindings").addObject()
                .put("ref", "requested_profile").put("kind", componentType).put("reuseOnly", false);
        binding.putArray("allowedSlots").add(slot);
        binding.set("value", request.path("facts").path("profile").deepCopy());
        ObjectNode output = StrictAiJson.parseObject("""
                {"status":"CANDIDATE","mode":"CREATE","page":{"menuKey":"menu_current","settings":{},
                  "components":[{"type":"PROFILE","sourceNos":[],"params":{},"bindings":[]}]},
                 "operations":[],"questions":[],"notes":[]}
                """, StrictAiJson.RESPONSE_MAX_BYTES, StrictAiJson.MAX_DEPTH);
        output.put("schemaVersion", AiPortfolioProtocol.CONTRACT_VERSION).put("promptVersion", AiPortfolioProtocol.PROMPT_VERSION);
        ObjectNode component = (ObjectNode) output.path("page").path("components").get(0);
        component.put("type", componentType).withArray("bindings").addObject()
                .put("slot", slot).put("ref", "requested_profile");

        assertThat(AiPortfolioProtocol.project(request).path("currentPage").path("components")).isEmpty();
        assertThat(AiPortfolioProtocol.project(request).path("resources").findValuesAsText("ref"))
                .contains("requested_profile");
        assertThat(AiPortfolioProtocol.contract(request).path("componentCatalog").has(componentType)).isTrue();
        assertThat(AiPortfolioProtocol.validate(request, output.toString())).isEqualTo(output);
    }

    /** 修改模式继续提供真实既有正文、确定性别名及准确哈希，不能因新建修复丢失修改基准。 */
    @ParameterizedTest
    @CsvSource({"USER,PROFILE", "TEAM,TEAM_PROFILE"})
    void modifyKeepsExistingPageAliasesAndHashes(String ownerType, String componentType) {
        ObjectNode request = request("MODIFY_PAGE", ownerType, componentType);
        ObjectNode projected = AiPortfolioProtocol.project(request);
        ObjectNode original = (ObjectNode) AiPortfolioProtocol.currentComponents(request).get(0);

        assertThat(projected.path("currentPage").path("components")).hasSize(1);
        assertThat(projected.path("currentPage").path("components").get(0).path("componentKey").asText()).isEqualTo("cmp_1");
        assertThat(projected.path("currentPage").path("components").get(0).path("type").asText()).isEqualTo(componentType);
        assertThat(projected.path("currentPage").path("components").get(0).path("expectedHash").asText())
                .isEqualTo(AiPortfolioProtocol.expectedHash(original));
        assertThat(projected.path("currentPage").path("expectedHash").asText())
                .isEqualTo(AiPortfolioProtocol.expectedHash(AiPortfolioProtocol.currentComponents(request)));
        assertThat(AiPortfolioProtocol.resolveComponent(request, "cmp_1")).isSameAs(original);
    }
}
