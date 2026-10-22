package com.jxc.wefolio.service.portfolioai;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.jxc.wefolio.config.PortfolioAiProperties;
import com.jxc.wefolio.mapper.WorkEntityMapper;
import com.jxc.wefolio.service.PortfolioConfigValidator;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

/** 验证整体封面与正文、背景和音乐的实际编译及模型投影，防止只修正文案而无法采用。 */
class PortfolioAiGlobalSettingsTest {
    /** 来自授权作品的固定图片值，模型只看到对应句柄。 */
    private static final String COVER_URL = "https://cdn.example.test/work/image/one.jpg";

    /** 使用生产严格解析器构建测试输入。 */
    private ObjectNode node(String json) {
        return StrictAiJson.parseObject(json, StrictAiJson.RESPONSE_MAX_BYTES, StrictAiJson.MAX_DEPTH);
    }

    /** 冻结包含图片、音频、文艺字体与手动全局配置的任务。 */
    private ObjectNode request(String mode) {
        ObjectNode request = node("""
                {"input":{"ownerType":"USER","ownerId":"101","mode":"CREATE","targetMenuKey":"__root__","prompt":"1号作品作为作品集封面"},
                 "facts":{"baseConfig":{"schemaVersion":"standard-personal-v1","editorSchemaRevision":14,"fonts":{},
                   "share":{"title":"手动名称","avatarUrl":"手动头像"},"style":{"backgroundColor":"#FFFFFF","componentSpacingRpx":32},
                   "components":[]},"sources":[
                   {"sourceNo":1,"kind":"WORK","sourceId":"101","mediaType":"IMAGE","canBind":true},
                   {"sourceNo":2,"kind":"WORK","sourceId":"102","mediaType":"IMAGE","canBind":true},
                   {"sourceNo":3,"kind":"WORK","sourceId":"103","mediaType":"VIDEO","canBind":true},
                   {"sourceNo":4,"kind":"WORK","sourceId":"104","mediaType":"AUDIO","canBind":true}],
                  "bindings":[{"ref":"source_cover_1","kind":"IMAGE","sourceNo":1,"allowedSlots":["coverUrl"],"reuseOnly":false},
                   {"ref":"font_1","kind":"FONT","label":"霞鹜文楷","allowedSlots":["fontId"],"reuseOnly":false,
                    "value":{"fontId":"example-font","reference":{"fontVersion":"v1"}}}]}}
                """);
        request.put("contractVersion", AiPortfolioProtocol.CONTRACT_VERSION).put("promptVersion", AiPortfolioProtocol.PROMPT_VERSION);
        request.withObject("input").put("mode", mode);
        ((ObjectNode) request.path("facts").path("bindings").get(0)).put("value", COVER_URL);
        return request;
    }

    /** 构建合法的两种模式信封。 */
    private ObjectNode output(ObjectNode request) {
        ObjectNode output = node("{\"status\":\"CANDIDATE\",\"page\":null,\"operations\":[],\"questions\":[],\"notes\":[]}");
        output.put("schemaVersion", AiPortfolioProtocol.CONTRACT_VERSION).put("promptVersion", AiPortfolioProtocol.PROMPT_VERSION)
                .put("mode", request.path("input").path("mode").asText());
        return output;
    }

    /** 生成名称通过既有 share.title 编译，个人与团队都不需要新增客户端字段或正文标题组件。 */
    @ParameterizedTest
    @CsvSource({"USER,《相逢》", "USER,让相逢有回响", "TEAM,Together · 婚礼团队", "TEAM,共赴一场美好"})
    void generatedTitleReplacesOnlyTheShareTitle(String ownerType, String title) {
        ObjectNode request = request("CREATE"); request.withObject("input").put("ownerType", ownerType);
        ObjectNode base = AiPortfolioProtocol.baseConfig(request).deepCopy();
        ObjectNode output = output(request);
        ObjectNode page = output.putObject("page").put("menuKey", AiPortfolioProtocol.MENU_ALIAS);
        page.putArray("components");
        ObjectNode share = page.putObject("settings").putObject("share");
        share.putObject("params").put("title", title); share.putArray("bindings");
        ObjectNode expected = base.deepCopy(); expected.withObject("share").put("title", title);
        assertThat(PortfolioAiCompiler.compile(706L, request, output)).isEqualTo(expected);
        assertThat(AiPortfolioProtocol.baseConfig(request)).isEqualTo(base);
    }

    /** 新生成长度限制不重新解释升级前已保存的候选，旧客户端仍可采用原名称。 */
    @ParameterizedTest
    @ValueSource(strings = {"USER", "TEAM"})
    void previouslySavedLongTitleCandidateRemainsAdoptable(String ownerType) {
        generatedTitleReplacesOnlyTheShareTitle(ownerType, "花".repeat(51));
    }

    /** 原始用户场景最终生成独立作品集封面和四项正文，所有真实引用由服务端恢复。 */
    @Test
    void reportedScenarioCompilesCoverWithoutAddingItToThePage() {
        ObjectNode request = request("CREATE"), output = output(request);
        output.set("page", node("""
                {"menuKey":"menu_current","components":[
                  {"type":"TEXT_SECTION","sourceNos":[],"params":{"content":"让相逢有回响。","backgroundEnabled":true},
                   "bindings":[{"slot":"backgroundWorkId","sourceNo":2},{"slot":"fontId","ref":"font_1"}]},
                  {"type":"SINGLE_WORK","sourceNos":[3],"params":{},"bindings":[]},
                  {"type":"SCHEDULE_QUERY","sourceNos":[],"params":{},"bindings":[]},
                  {"type":"CONTACT_FORM","sourceNos":[],"params":{},"bindings":[]}],
                 "settings":{"share":{"params":{},"bindings":[{"slot":"coverUrl","ref":"source_cover_1"}]},
                   "style":{"params":{"backgroundColor":"#000000"},"bindings":[]},
                   "backgroundAudio":{"params":{"enabled":true},"bindings":[{"slot":"workId","sourceNo":4}]}}}
                """));
        ObjectNode candidate = PortfolioAiCompiler.compile(701L, request, output);
        assertThat(candidate.path("share").path("coverUrl").asText()).isEqualTo(COVER_URL);
        assertThat(candidate.path("share").path("title").asText()).isEqualTo("手动名称");
        assertThat(candidate.path("share").path("avatarUrl").asText()).isEqualTo("手动头像");
        assertThat(candidate.path("components")).hasSize(4);
        JsonNode text = candidate.path("components").get(0).path("config");
        assertThat(text.path("backgroundWorkId").longValue()).isEqualTo(102);
        assertThat(text.path("fontId").asText()).isEqualTo("example-font");
        assertThat(candidate.path("fonts").path("example-font").path("fontVersion").asText()).isEqualTo("v1");
        assertThat(candidate.path("components").get(1).path("config").path("workId").longValue()).isEqualTo(103);
        assertThat(candidate.path("backgroundAudio").path("workId").longValue()).isEqualTo(104);
        assertThat(candidate.path("backgroundAudio").path("enabled").asBoolean()).isTrue();
        assertThat(candidate.path("style").path("backgroundColor").asText()).isEqualTo("#000000");
        assertThat(candidate.path("style").path("componentSpacingRpx").intValue()).isEqualTo(32);
    }

    /** 修改封面只写对应全局字段，页面及其它手动设置保持原值。 */
    @Test
    void modifyingCoverUsesGlobalHashAndPreservesThePage() {
        ObjectNode request = request("MODIFY_PAGE"), output = output(request);
        ObjectNode base = AiPortfolioProtocol.baseConfig(request);
        ObjectNode operation = ((ArrayNode) output.path("operations")).addObject().put("op", "UPDATE_GLOBAL").put("reason", "调整作品集封面");
        operation.putObject("target").put("menuKey", "menu_current");
        operation.put("expectedHash", AiPortfolioProtocol.project(request).path("globalState").path("share").path("expectedHash").asText());
        operation.set("payload", node("{\"section\":\"share\",\"params\":{},\"bindings\":[{\"slot\":\"coverUrl\",\"ref\":\"source_cover_1\"}]}"));
        ObjectNode expected = base.deepCopy();
        expected.withObject("share").put("coverUrl", COVER_URL);
        // 编译器按最终正文依赖维护空字体表，不新增任何正文节点。
        assertThat(PortfolioAiCompiler.compile(702L, request, output)).isEqualTo(expected);
        assertThat(base.path("share").has("coverUrl")).isFalse();
        operation.put("expectedHash", AiPortfolioProtocol.expectedHash(base.path("components")));
        assertThatThrownBy(() -> PortfolioAiCompiler.compile(702L, request, output)).isInstanceOf(AiProtocolException.class);
    }

    /** 手动基准尚未配置音乐时，仍可按准确的缺失值基准首次添加，且不改其它设置。 */
    @Test
    void addingAnAbsentGlobalSectionPreservesOtherSettings() {
        ObjectNode request = request("MODIFY_PAGE"), output = output(request);
        ObjectNode base = AiPortfolioProtocol.baseConfig(request);
        ObjectNode operation = ((ArrayNode) output.path("operations")).addObject().put("op", "UPDATE_GLOBAL").put("reason", "添加背景音乐");
        operation.putObject("target").put("menuKey", "menu_current");
        operation.put("expectedHash", AiPortfolioProtocol.project(request).path("globalState").path("backgroundAudio").path("expectedHash").asText());
        operation.set("payload", node("{\"section\":\"backgroundAudio\",\"params\":{\"enabled\":true},\"bindings\":[{\"slot\":\"workId\",\"sourceNo\":4}]}"));
        ObjectNode expected = base.deepCopy();
        expected.putObject("backgroundAudio").put("enabled", true).put("workId", 104L);
        assertThat(PortfolioAiCompiler.compile(703L, request, output)).isEqualTo(expected);
        assertThat(base.has("backgroundAudio")).isFalse();
    }

    /** 作品编号不等于图片句柄，禁止将业务 ID 或模型写入的地址当作作品集封面。 */
    @Test
    void coverBindingRejectsWorkNumberAndRawUrl() {
        ObjectNode request = request("CREATE"), output = output(request);
        output.set("page", node("{\"menuKey\":\"menu_current\",\"components\":[],\"settings\":{\"share\":{\"params\":{},\"bindings\":[{\"slot\":\"coverUrl\",\"sourceNo\":1}]}}}"));
        assertThatThrownBy(() -> PortfolioAiCompiler.compile(704L, request, output)).isInstanceOf(AiProtocolException.class);
        ObjectNode share = (ObjectNode) output.path("page").path("settings").path("share");
        share.putArray("bindings");
        share.withObject("params").put("coverUrl", COVER_URL);
        assertThatThrownBy(() -> PortfolioAiCompiler.compile(704L, request, output)).isInstanceOf(AiProtocolException.class);
    }

    /** 编译后的封面通过真实普通草稿规范化，旧小程序可直接读取既有 share.coverUrl 字段。 */
    @Test
    void trustedCoverSurvivesRealOrdinaryDraftNormalization() {
        ObjectNode request = request("CREATE"), output = output(request);
        output.set("page", node("{\"menuKey\":\"menu_current\",\"components\":[{\"type\":\"TEXT_SECTION\",\"sourceNos\":[],\"params\":{\"content\":\"让相逢有回响。\"},\"bindings\":[]}],\"settings\":{\"share\":{\"params\":{},\"bindings\":[{\"slot\":\"coverUrl\",\"ref\":\"source_cover_1\"}]}}}"));
        output.withObject("page").withObject("settings").withObject("share").withObject("params")
                .put("title", "让相逢有回响");
        ObjectNode candidate = PortfolioAiCompiler.compile(705L, request, output);
        PortfolioAiConfigService validation = new PortfolioAiConfigService(new PortfolioAiProperties(),
                new PortfolioConfigValidator(mock(WorkEntityMapper.class)), null, null, null, null);
        ObjectNode normalized = validation.validate((ObjectNode) request.path("input"), null, candidate);
        assertThat(normalized.path("share").path("coverUrl").asText()).isEqualTo(COVER_URL);
        assertThat(normalized.path("share").path("title").asText()).isEqualTo("让相逢有回响");
        assertThat(normalized.path("components")).hasSize(1);
        assertThat(normalized.path("components").get(0).path("componentType").asText()).isEqualTo("TEXT_SECTION");
    }

    /** 模型能够把来源图片句柄与作品号关联，但不能读取隐藏值和业务身份。 */
    @Test
    void imageResourceProjectionExposesSourceNumberButHidesValue() {
        JsonNode image = AiPortfolioProtocol.project(request("CREATE")).path("resources").get(0);
        assertThat(image.path("ref").asText()).isEqualTo("source_cover_1");
        assertThat(image.path("sourceNo").intValue()).isEqualTo(1);
        assertThat(image.has("value")).isFalse();
        assertThat(image.has("sourceId")).isFalse();
    }

    /** 团队简介依当前归属契约投影，未配置音乐也提供可用于全局修改的准确基准。 */
    @Test
    void globalProjectionIncludesTeamDescriptionAndAbsentSectionHash() {
        ObjectNode request = request("MODIFY_PAGE");
        request.withObject("input").put("ownerType", "TEAM");
        ObjectNode base = AiPortfolioProtocol.baseConfig(request);
        base.withObject("share").put("description", "原有团队简介");
        JsonNode globals = AiPortfolioProtocol.project(request).path("globalState");
        assertThat(globals.path("share").path("params").path("description").asText()).isEqualTo("原有团队简介");
        assertThat(globals.path("share").path("params").has("avatarUrl")).isFalse();
        assertThat(globals.path("style").path("params").has("componentSpacingRpx")).isFalse();
        assertThat(globals.path("backgroundAudio").path("expectedHash").asText())
                .isEqualTo(AiPortfolioProtocol.expectedHash(base.path("backgroundAudio")));
    }
}
