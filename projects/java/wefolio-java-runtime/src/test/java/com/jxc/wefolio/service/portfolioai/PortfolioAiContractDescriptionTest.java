package com.jxc.wefolio.service.portfolioai;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** 核对模型实际收到的参数语义，同时保证说明元数据不扩大普通配置和协议的合法值域。 */
class PortfolioAiContractDescriptionTest {
    /** 两工程共同维护的可信目录资源。 */
    private static final String CONTRACT_RESOURCE = "/portfolioai/contract-v1.json";
    /** 参数用途说明元数据键；业务参数中的同名对象不能被删除。 */
    private static final String DESCRIPTION = "description";
    /** 补充说明前的完整约束摘要，不包含独立演进的提示词版本。 */
    private static final String ORIGINAL_CONSTRAINTS_HASH = "3122f6ef33e9bac8d6efb9ccd8aeaaf05af53a24997a722af606b4d4cd66a2c5";
    /** 模型模式与归属组合，须同时覆盖个人和团队的生成、修改。 */
    private static final List<String> MODES = List.of("CREATE", "MODIFY_PAGE");
    /** 合法冻结来源只使用测试素材，不读取数据库或真实联系方式。 */
    private static final String FACTS = """
            {"input":{"ownerType":"USER","mode":"CREATE","targetMenuKey":"__root__","baseConfig":null},
             "facts":{"baseConfig":{"share":{"title":"原作品集"},"style":{"backgroundColor":"#FFFFFF"},"components":[]},
               "sources":[{"sourceNo":1,"kind":"WORK","sourceId":"101","mediaType":"IMAGE","canBind":true},
                 {"sourceNo":2,"kind":"WORK","sourceId":"102","mediaType":"AUDIO","canBind":true}],
               "bindings":[{"ref":"test_cover","kind":"IMAGE","allowedSlots":["coverUrl"],"reuseOnly":false,
                 "value":"https://cdn.example.test/cover.jpg"}]}}
            """;

    /** 从实际类路径读取目录，避免测试一份与运行时无关的复制契约。 */
    private ObjectNode rules() throws IOException {
        try (InputStream stream = getClass().getResourceAsStream(CONTRACT_RESOURCE)) {
            assertThat(stream).isNotNull();
            return StrictAiJson.parseObject(new String(stream.readAllBytes(), StandardCharsets.UTF_8),
                    StrictAiJson.CONFIG_MAX_BYTES, StrictAiJson.MAX_DEPTH);
        }
    }

    /** 用当前协议版本构造四种实际契约入口。 */
    private ObjectNode request(String ownerType, String mode) {
        ObjectNode request = StrictAiJson.parseObject(FACTS, StrictAiJson.RESPONSE_MAX_BYTES, StrictAiJson.MAX_DEPTH);
        request.put("contractVersion", AiPortfolioProtocol.CONTRACT_VERSION)
                .put("promptVersion", AiPortfolioProtocol.PROMPT_VERSION);
        request.withObject("input").put("ownerType", ownerType).put("mode", mode);
        return request;
    }

    /** 每种现有组件、参数、资源槽位和内部文字节点都应向模型提供具体用途。 */
    @Test
    void everyExistingComponentAndSettingHasBusinessDescriptions() throws IOException {
        ObjectNode rules = rules();
        for (String section : List.of("componentCatalog", "settings")) {
            rules.path(section).fields().forEachRemaining(entry -> {
                JsonNode definition = entry.getValue();
                assertThat(definition.path(DESCRIPTION).asText()).as(entry.getKey()).isNotBlank();
                for (String fields : List.of("params", "bindingSlots", "textFields")) {
                    definition.path(fields).fields().forEachRemaining(field ->
                            assertDescription(field.getValue(), entry.getKey() + "." + field.getKey()));
                }
                if (definition.path("primarySource").isObject()) {
                    assertThat(definition.path("primarySource").path(DESCRIPTION).asText()).contains("sourceNos");
                }
            });
        }
        rules.path("nodeParams").fields().forEachRemaining(node -> node.getValue().fields().forEachRemaining(field ->
                assertDescription(field.getValue(), node.getKey() + "." + field.getKey())));
    }

    /** 递归检查嵌套查询范围、表单字段、文字块和网格片段，不遗漏枚举及单位说明。 */
    private void assertDescription(JsonNode schema, String path) {
        String description = schema.path(DESCRIPTION).asText();
        assertThat(description).as(path).isNotBlank();
        schema.path("enum").forEach(value -> assertThat(description).as(path).contains(value.asText()));
        if (path.endsWith("Rpx")) { assertThat(description).as(path).contains("rpx"); }
        if (path.endsWith("heightPx")) { assertThat(description).as(path).contains("px"); }
        schema.path("properties").fields().forEachRemaining(field ->
                assertDescription(field.getValue(), path + "." + field.getKey()));
        if (schema.path("items").isObject()) { assertDescription(schema.get("items"), path + "[]"); }
    }

    /** 语义必须实际进入个人和团队的生成、修改契约，并准确保留两类配置能力差异。 */
    @Test
    void modelContractsDescribeGlobalSettingsAndKeepOwnerSpecificFields() {
        for (String ownerType : List.of("USER", "TEAM")) {
            for (String mode : MODES) {
                ObjectNode contract = AiPortfolioProtocol.contract(request(ownerType, mode));
                String section = "CREATE".equals(mode) ? "createSettings" : "globalFields";
                JsonNode settings = contract.path(section);
                assertThat(names(settings)).containsExactlyInAnyOrder("share", "style", "backgroundAudio");
                assertThat(settings.path("share").path("bindingSlots").path("coverUrl").path(DESCRIPTION).asText())
                        .contains("作品集封面", "IMAGE ref", "不新增正文");
                assertThat(settings.path("style").path("params").path("backgroundColor").path(DESCRIPTION).asText())
                        .contains("整个作品集", "#000000");
                assertThat(settings.path("style").path(DESCRIPTION).asText()).contains("独立开场", "全局字体", "底部导航");
                assertThat(settings.path("backgroundAudio").path("bindingSlots").path("workId").path(DESCRIPTION).asText())
                        .contains("AUDIO", "sourceNo");
                if ("TEAM".equals(ownerType)) {
                    assertThat(names(settings.path("share").path("params"))).containsExactlyInAnyOrder("title", "description");
                    assertThat(names(settings.path("share").path("bindingSlots"))).containsExactly("coverUrl");
                    assertThat(names(settings.path("style").path("params"))).containsExactly("backgroundColor");
                } else {
                    assertThat(names(settings.path("share").path("params"))).containsExactly("title");
                    assertThat(names(settings.path("share").path("bindingSlots"))).containsExactlyInAnyOrder("coverUrl", "avatarUrl");
                    assertThat(names(settings.path("style").path("params"))).containsExactlyInAnyOrder("backgroundColor", "componentSpacingRpx");
                }
                JsonNode components = contract.path("componentCatalog");
                assertThat(components.path("SINGLE_WORK").path(DESCRIPTION).asText()).contains("正文", "share.coverUrl");
                assertThat(components.path("CONTACT_FORM").path(DESCRIPTION).asText()).contains("访客", "留资");
                assertThat(components.path("CONTACT_INFO").path(DESCRIPTION).asText()).contains("主持人", "可信联系方式");
                assertThat(components.path("QR_CONTACT").path("params").path("qrUrlSource").path(DESCRIPTION).asText())
                        .contains("PROFILE 仅个人可用", "团队只支持 CUSTOM", "团队自有上传二维码");
                assertThat(components.path("TEXT_SECTION").path("bindingSlots").path("backgroundWorkId").path(DESCRIPTION).asText())
                        .contains("局部背景", "backgroundEnabled=true");
                assertThat(components.path("TEXT_SECTION").path("params").path("fontFamily").path(DESCRIPTION).asText())
                        .contains("SYSTEM", "WECHAT_SANS_SS", "可信 FONT ref");
            }
        }
    }

    /** 模型实际收到的说明须包含跨节点总量、团队资料模式边界及成员入口的固定布局。 */
    @Test
    void modelContractsDescribeAggregateLimitsAndTeamCapabilities() {
        for (String ownerType : List.of("USER", "TEAM")) {
            for (String mode : MODES) {
                JsonNode catalog = AiPortfolioProtocol.contract(request(ownerType, mode)).path("componentCatalog");
                for (String type : List.of("STRUCTURED_TEXT_SECTION", "TEXT_GRID")) {
                    assertThat(catalog.path(type).path(DESCRIPTION).asText())
                            .contains("整个组件", "合计最多 2000 个 Unicode 码点", "未修改的内容");
                }
                assertThat(catalog.path("STRUCTURED_TEXT_SECTION").path(DESCRIPTION).asText())
                        .contains("至少包含一个非 SPACER", "不能全部为留白块");
                if ("TEAM".equals(ownerType)) {
                    JsonNode profile = catalog.path("TEAM_PROFILE");
                    assertThat(profile.path(DESCRIPTION).asText()).contains("CREATE", "INSERT_COMPONENT", "params 必须为 {}",
                            "MODIFY_PAGE", "UPDATE_TEXT", "visibleFields", "手动调整");
                    assertThat(profile.path("params")).isEmpty();
                    assertThat(profile.path("textFields").path("intro").path(DESCRIPTION).asText())
                            .contains("仅 MODIFY_PAGE", "已有 TEAM_PROFILE");
                    assertThat(catalog.path("MEMBER_PORTFOLIO_GRID").path(DESCRIPTION).asText())
                            .contains("固定双列", "不能配置其它列数", "点击进入");
                    assertThat(catalog.path("MEMBER_PORTFOLIO_LIST").path(DESCRIPTION).asText())
                            .contains("固定单列", "不能配置其它列数", "点击进入");
                }
            }
        }
    }

    /** 用真实协议验证整个组件共享字数预算；边界按 Unicode 码点计数，并禁止纯留白结构。 */
    @Test
    void aggregateTextLimitsMatchDescriptions() {
        ObjectNode request = request("TEAM", "CREATE");
        for (String type : List.of("STRUCTURED_TEXT_SECTION", "TEXT_GRID")) {
            ObjectNode output = candidate("CREATE");
            ObjectNode page = output.putObject("page").put("menuKey", AiPortfolioProtocol.MENU_ALIAS);
            page.putObject("settings");
            ObjectNode component = page.putArray("components").addObject().put("type", type);
            component.putArray("sourceNos"); component.putArray("bindings");
            ObjectNode params = component.putObject("params");
            ObjectNode first;
            ObjectNode second;
            String field;
            if ("STRUCTURED_TEXT_SECTION".equals(type)) {
                var blocks = params.putArray("blocks");
                first = blocks.addObject().put("type", "PARAGRAPH");
                second = blocks.addObject().put("type", "PARAGRAPH");
                field = "content";
            } else {
                params.put("rows", 1).put("columns", 1);
                var runs = params.putArray("cells").addObject().put("row", 0).put("column", 0)
                        .put("rowSpan", 1).put("columnSpan", 1).putArray("blocks").addObject().putArray("runs");
                first = runs.addObject(); second = runs.addObject(); field = "text";
            }
            first.put(field, "🌸".repeat(1000)); second.put(field, "文".repeat(1000));
            assertThat(AiPortfolioProtocol.validate(request, output.toString())).isEqualTo(output);
            second.put(field, "文".repeat(1001));
            assertThatThrownBy(() -> AiPortfolioProtocol.validate(request, output.toString()))
                    .isInstanceOf(AiProtocolException.class).hasMessageContaining("总长度");
            if ("STRUCTURED_TEXT_SECTION".equals(type)) {
                params.putArray("blocks").addObject().put("type", "SPACER").put("heightRpx", 32);
                assertThatThrownBy(() -> AiPortfolioProtocol.validate(request, output.toString()))
                        .isInstanceOf(AiProtocolException.class).hasMessageContaining("正文不合法");
            }
        }
    }

    /** 新建资料不能写入介绍或显示开关，已有团队资料仍允许通过文字操作修改介绍。 */
    @Test
    void teamProfileCreationAndModificationMatchDescriptions() {
        ObjectNode request = request("TEAM", "CREATE");
        ObjectNode output = candidate("CREATE");
        ObjectNode page = output.putObject("page").put("menuKey", AiPortfolioProtocol.MENU_ALIAS);
        page.putObject("settings");
        ObjectNode proposal = page.putArray("components").addObject().put("type", "TEAM_PROFILE");
        proposal.putArray("sourceNos"); proposal.putArray("bindings");
        ObjectNode params = proposal.putObject("params");
        assertThat(AiPortfolioProtocol.validate(request, output.toString())).isEqualTo(output);
        params.put("intro", "作品集内的新介绍");
        assertThatThrownBy(() -> AiPortfolioProtocol.validate(request, output.toString()))
                .isInstanceOf(AiProtocolException.class);
        params.removeAll(); params.putObject("visibleFields").put("avatar", false);
        assertThatThrownBy(() -> AiPortfolioProtocol.validate(request, output.toString()))
                .isInstanceOf(AiProtocolException.class);

        request.withObject("input").put("mode", "MODIFY_PAGE");
        ObjectNode component = request.withObject("facts").withObject("baseConfig").putArray("components")
                .addObject().put("componentKey", "existing-team").put("componentType", "TEAM_PROFILE").put("enabled", true);
        component.putObject("config").putObject("team").put("intro", "原介绍");
        ObjectNode modification = candidate("MODIFY_PAGE");
        ObjectNode operation = modification.withArray("operations").addObject().put("op", "UPDATE_TEXT")
                .put("expectedHash", AiPortfolioProtocol.expectedHash(component)).put("reason", "更新团队介绍");
        operation.putObject("target").put("menuKey", AiPortfolioProtocol.MENU_ALIAS).put("componentKey", "cmp_1");
        operation.putObject("payload").put("field", "intro").put("value", "作品集内的新介绍");
        assertThat(AiPortfolioProtocol.validate(request, modification.toString())).isEqualTo(modification);
    }

    /** 结构化文字新建块可绑定字体，网格片段仍需准确区分生成与现有节点修改。 */
    @Test
    void nestedFontDescriptionsFollowActualCreationAndModificationCapabilities() {
        ObjectNode contract = AiPortfolioProtocol.contract(request("USER", "CREATE"));
        JsonNode catalog = contract.path("componentCatalog");
        JsonNode blockProperties = catalog.path("STRUCTURED_TEXT_SECTION").path("params").path("blocks")
                .path("items").path("properties");
        JsonNode runProperties = catalog.path("TEXT_GRID").path("params").path("cells").path("items")
                .path("properties").path("blocks").path("items").path("properties")
                .path("runs").path("items").path("properties");
        assertThat(blockProperties.has("fontId")).isFalse();
        assertThat(blockProperties.path("fontFamily").path(DESCRIPTION).asText())
                .contains("SYSTEM", "WECHAT_SANS_SS", "bindings", "可信 FONT ref");
        JsonNode blockBindings = blockProperties.path("bindings");
        assertThat(blockBindings.path("type").asText()).isEqualTo("array");
        assertThat(blockBindings.path("minItems").asInt()).isZero();
        assertThat(blockBindings.path("maxItems").asInt()).isEqualTo(1);
        assertThat(names(blockBindings.path("items").path("properties"))).containsExactlyInAnyOrder("slot", "ref");
        assertThat(blockBindings.path("items").path("required").toString()).isEqualTo("[\"slot\",\"ref\"]");
        assertThat(blockBindings.path("items").path("properties").path("slot").path("enum").toString())
                .isEqualTo("[\"fontId\"]");
        assertThat(blockBindings.path(DESCRIPTION).asText()).contains("CREATE", "INSERT_COMPONENT", "SPACER", "reuseOnly=false");
        assertThat(catalog.path("STRUCTURED_TEXT_SECTION").path(DESCRIPTION).asText())
                .contains("多段", "同一背景", "字体");
        assertThat(runProperties.has("fontId")).isFalse();
        assertThat(runProperties.has("bindings")).isFalse();
        assertThat(runProperties.path("fontFamily").path(DESCRIPTION).asText())
                .contains("CREATE", "只能选内置字体", "MODIFY_PAGE", "现有", "BIND_RESOURCES", "TEXT_SECTION");
        for (String type : List.of("STRUCTURED_TEXT_SECTION", "TEXT_GRID")) {
            assertThat(catalog.path(type).path("bindingSlots").has("fontId")).isFalse();
        }
        for (String node : List.of("STRUCTURED_BLOCK", "GRID_RUN")) {
            assertThat(contract.path("nodeParams").path(node).path("fontFamily").path(DESCRIPTION).asText())
                    .contains("MODIFY_PAGE", "现有文字节点", "允许 fontId", "BIND_RESOURCES");
        }
        JsonNode fields = catalog.path("CONTACT_FORM").path("params").path("fields");
        assertThat(fields.path(DESCRIPTION).asText()).contains("留资表单字段配置").doesNotContain("按数组顺序展示");
        assertThat(fields.path("items").path(DESCRIPTION).asText()).doesNotContain("按数组顺序展示");
        assertThat(contract.path("createSettings").path("share").path("bindingSlots").path("avatarUrl")
                .path(DESCRIPTION).asText()).contains("个人资料头像 ref", "已选作品对应图片 ref");
    }

    /** 使用实际协议证明新建结构化块接受字体句柄，网格仍拒绝嵌套绑定，现有节点保留绑定能力。 */
    @Test
    void structuredBlockFontsAreAcceptedDuringCreationAndExistingNodeBindingsRemainAvailable() {
        for (String type : List.of("STRUCTURED_TEXT_SECTION", "TEXT_GRID")) {
            ObjectNode request = request("USER", "CREATE");
            ObjectNode proposal = StrictAiJson.parseObject("STRUCTURED_TEXT_SECTION".equals(type) ? """
                    {"type":"STRUCTURED_TEXT_SECTION","sourceNos":[],"bindings":[],
                     "params":{"blocks":[{"type":"PARAGRAPH","content":"文艺主持描述","fontFamily":"SYSTEM"}]}}
                    """ : """
                    {"type":"TEXT_GRID","sourceNos":[],"bindings":[],"params":{"rows":1,"columns":1,
                     "cells":[{"row":0,"column":0,"rowSpan":1,"columnSpan":1,
                       "blocks":[{"runs":[{"text":"文艺主持描述","fontFamily":"SYSTEM"}]}]}]}}
                    """, StrictAiJson.RESPONSE_MAX_BYTES, StrictAiJson.MAX_DEPTH);
            ObjectNode font = request.withObject("facts").withArray("bindings").addObject().put("ref", "available-font")
                    .put("kind", "FONT").put("reuseOnly", false);
            font.putArray("allowedSlots").add("fontId");
            font.putObject("value").put("fontId", "test-font").putObject("reference").put("fontVersion", "test-version");
            ObjectNode output = candidate("CREATE");
            ObjectNode page = output.putObject("page").put("menuKey", AiPortfolioProtocol.MENU_ALIAS);
            page.putObject("settings"); page.putArray("components").add(proposal);
            assertThat(AiPortfolioProtocol.validate(request, output.toString())).isEqualTo(output);
            ObjectNode node = "STRUCTURED_TEXT_SECTION".equals(type)
                    ? (ObjectNode) proposal.path("params").path("blocks").get(0)
                    : (ObjectNode) proposal.path("params").path("cells").get(0).path("blocks").get(0).path("runs").get(0);
            node.put("fontId", "unavailable-font");
            assertThatThrownBy(() -> AiPortfolioProtocol.validate(request, output.toString())).isInstanceOf(AiProtocolException.class);
            node.remove("fontId");
            node.putArray("bindings").addObject().put("slot", "fontId").put("ref", "available-font");
            if ("STRUCTURED_TEXT_SECTION".equals(type)) {
                assertThat(AiPortfolioProtocol.validate(request, output.toString())).isEqualTo(output);
            } else {
                assertThatThrownBy(() -> AiPortfolioProtocol.validate(request, output.toString())).isInstanceOf(AiProtocolException.class);
            }
            node.remove("bindings");

            request.withObject("input").put("mode", "MODIFY_PAGE");
            ObjectNode component = request.withObject("facts").withObject("baseConfig").putArray("components")
                    .addObject().put("componentKey", "existing-text").put("componentType", type).put("enabled", true);
            component.set("config", proposal.path("params"));
            ObjectNode target = StrictAiJson.mapper().createObjectNode().put("menuKey", AiPortfolioProtocol.MENU_ALIAS)
                    .put("componentKey", "cmp_1").put("blockKey", "block_1");
            if ("STRUCTURED_TEXT_SECTION".equals(type)) { node.put("blockKey", "existing-block"); }
            else {
                ((ObjectNode) component.path("config").path("cells").get(0)).put("cellKey", "existing-cell");
                ((ObjectNode) component.path("config").path("cells").get(0).path("blocks").get(0)).put("blockKey", "existing-block");
                node.put("runKey", "existing-run"); target.put("cellKey", "cell_1").put("runKey", "run_1");
            }
            ObjectNode modification = candidate("MODIFY_PAGE");
            ObjectNode operation = modification.withArray("operations").addObject().put("op", "BIND_RESOURCES")
                    .put("reason", "为已有文字选择文艺字体");
            operation.set("target", target);
            operation.put("expectedHash", AiPortfolioProtocol.expectedHash(AiPortfolioProtocol.resolveNode(request, target)));
            ObjectNode payload = operation.putObject("payload"); payload.putArray("sourceNos");
            payload.putArray("bindings").addObject().put("slot", "fontId").put("ref", "available-font");
            assertThat(AiPortfolioProtocol.validate(request, modification.toString())).isEqualTo(modification);
        }
    }

    /** 为生成和修改字体能力校验构造完整候选信封。 */
    private ObjectNode candidate(String mode) {
        ObjectNode output = StrictAiJson.mapper().createObjectNode();
        output.put("schemaVersion", AiPortfolioProtocol.CONTRACT_VERSION).put("promptVersion", AiPortfolioProtocol.PROMPT_VERSION)
                .put("status", "CANDIDATE").put("mode", mode).putNull("page");
        output.putArray("operations"); output.putArray("questions"); output.putArray("notes");
        return output;
    }

    /** 除明确新增的结构化块字体绑定外，保留其余字段、枚举、范围、必填性与素材限制。 */
    @Test
    void blockFontBindingsLeaveAllOtherValidationConstraintsUnchanged() throws IOException {
        ObjectNode constraints = rules();
        removeDescriptions(constraints);
        constraints.remove("promptVersion");
        ((ObjectNode) constraints.path("componentCatalog").path("STRUCTURED_TEXT_SECTION").path("params")
                .path("blocks").path("items").path("properties")).remove("bindings");
        assertThat(StrictAiJson.canonicalHash(constraints)).isEqualTo(ORIGINAL_CONSTRAINTS_HASH);
    }

    /** 只删除文本型的说明元数据，保留名为 description 的真实参数 schema。 */
    private void removeDescriptions(JsonNode node) {
        if (node.isObject() && node.path(DESCRIPTION).isTextual()) { ((ObjectNode) node).remove(DESCRIPTION); }
        node.forEach(this::removeDescriptions);
    }

    /** 从模型实际契约读取字段集合，字段顺序不影响能力边界。 */
    private List<String> names(JsonNode node) {
        List<String> names = new ArrayList<>();
        node.fieldNames().forEachRemaining(names::add);
        return names;
    }

    /** 新说明不参与候选数据；合法全局设置仍通过，未开放主题和非法颜色继续被拒绝。 */
    @Test
    void candidateValidationAcceptsExistingValuesAndRejectsUndeclaredSettings() {
        for (String ownerType : List.of("USER", "TEAM")) {
            ObjectNode request = request(ownerType, "CREATE");
            ObjectNode output = StrictAiJson.mapper().createObjectNode();
            output.put("schemaVersion", AiPortfolioProtocol.CONTRACT_VERSION).put("promptVersion", AiPortfolioProtocol.PROMPT_VERSION)
                    .put("status", "CANDIDATE").put("mode", "CREATE");
            output.putArray("operations"); output.putArray("questions"); output.putArray("notes");
            ObjectNode page = output.putObject("page").put("menuKey", AiPortfolioProtocol.MENU_ALIAS);
            ObjectNode text = page.putArray("components").addObject().put("type", "TEXT_SECTION");
            text.putArray("sourceNos"); text.putArray("bindings");
            text.putObject("params").put("content", "愿每次相逢都有温柔的回响。").put("fontFamily", "SYSTEM");
            ObjectNode settings = page.putObject("settings");
            ObjectNode share = settings.putObject("share"); share.putObject("params").put("title", "我的作品集");
            share.putArray("bindings").addObject().put("slot", "coverUrl").put("ref", "test_cover");
            ObjectNode style = settings.putObject("style"); style.putArray("bindings");
            style.putObject("params").put("backgroundColor", "#000000");
            ObjectNode audio = settings.putObject("backgroundAudio");
            audio.putObject("params").put("enabled", true).put("displayStyle", "MINI_PLAYER");
            audio.putArray("bindings").addObject().put("slot", "workId").put("sourceNo", 2);
            assertThat(AiPortfolioProtocol.validate(request, output.toString())).isEqualTo(output);
            style.withObject("params").put("theme", "文艺黑色");
            assertThatThrownBy(() -> AiPortfolioProtocol.validate(request, output.toString())).isInstanceOf(AiProtocolException.class);
            style.withObject("params").remove("theme");
            style.withObject("params").put("backgroundColor", "black");
            assertThatThrownBy(() -> AiPortfolioProtocol.validate(request, output.toString())).isInstanceOf(AiProtocolException.class);
            style.withObject("params").put("backgroundColor", "#000000");
            text.withObject("params").put("fontFamily", "霞鹜文楷");
            assertThatThrownBy(() -> AiPortfolioProtocol.validate(request, output.toString())).isInstanceOf(AiProtocolException.class);
        }
    }
}
