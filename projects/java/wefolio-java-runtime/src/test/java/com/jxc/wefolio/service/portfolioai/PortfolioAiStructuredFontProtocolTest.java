package com.jxc.wefolio.service.portfolioai;

import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** 两工程共享的结构化文字块字体协议测试，覆盖可信资源和原位复用边界。 */
class PortfolioAiStructuredFontProtocolTest {
    /** 首次生成和页面修改使用不同输出信封。 */
    private static final String CREATE_MODE = "CREATE", MODIFY_MODE = "MODIFY_PAGE";
    /** 本次开放字体绑定的组件类型。 */
    private static final String STRUCTURED_TYPE = "STRUCTURED_TEXT_SECTION";
    /** 文字块与留白块遵循独立字段约束。 */
    private static final String PARAGRAPH_TYPE = "PARAGRAPH", LIST_TYPE = "LIST", SPACER_TYPE = "SPACER";
    /** 模型只能引用字体句柄，不能填写真实字体身份。 */
    private static final String FONT_REF = "font_1", FONT_ID = "trusted-font-id", FONT_KIND = "FONT", FONT_SLOT = "fontId";
    /** 绑定信封字段保持与生产契约一致。 */
    private static final String BINDINGS_FIELD = "bindings", SLOT_FIELD = "slot", REF_FIELD = "ref";
    /** 测试中定位结构化参数和可信事实的字段。 */
    private static final String FACTS_FIELD = "facts", PARAMS_FIELD = "params", BLOCKS_FIELD = "blocks", TYPE_FIELD = "type";
    /** 首次生成资源错误须精确定位到文字块。 */
    private static final String CREATE_BINDING_PATH = "page.components[0].params.blocks[0].bindings";
    /** 新建组件操作使用独立的资源错误路径。 */
    private static final String INSERT_BINDING_PATH = "operations[0].payload.component.params.blocks[0].bindings";
    /** 原位修改使用真实稳定标识恢复字体复用范围。 */
    private static final String COMPONENT_KEY = "existing-structured", BLOCK_KEY = "existing-block";

    /** 所有真实标识仅保留在冻结事实，模型提案只包含字体句柄。 */
    private ObjectNode request(String mode) {
        ObjectNode request = StrictAiJson.parseObject("""
                {"input":{"ownerType":"USER","mode":"CREATE","targetMenuKey":"__root__","prompt":"文艺的多段文字"},
                 "facts":{"safePrompt":"文艺的多段文字","baseConfig":{"schemaVersion":"standard-personal-v1",
                   "components":[],"style":{"backgroundColor":"#000000"},"share":{"title":"作品"}},
                   "sources":[],"bindings":[]}}
                """, StrictAiJson.RESPONSE_MAX_BYTES, StrictAiJson.MAX_DEPTH);
        request.put("contractVersion", AiPortfolioProtocol.CONTRACT_VERSION).put("promptVersion", AiPortfolioProtocol.PROMPT_VERSION);
        request.withObject("input").put("mode", mode);
        ObjectNode font = request.withObject(FACTS_FIELD).withArray(BINDINGS_FIELD).addObject()
                .put(REF_FIELD, FONT_REF).put("kind", FONT_KIND).put("value", FONT_ID).put("reuseOnly", false);
        font.putArray("allowedSlots").add(FONT_SLOT);
        return request;
    }

    /** 构造包含一个文字块和可信字体句柄的新组件。 */
    private ObjectNode proposal(String blockType) {
        ObjectNode proposal = StrictAiJson.mapper().createObjectNode().put(TYPE_FIELD, STRUCTURED_TYPE);
        proposal.putArray("sourceNos"); proposal.putArray(BINDINGS_FIELD);
        ObjectNode block = proposal.putObject(PARAMS_FIELD).putArray(BLOCKS_FIELD).addObject().put(TYPE_FIELD, blockType);
        if (LIST_TYPE.equals(blockType)) { block.putArray("items").add("将相遇写成诗").add("将时光留给你"); }
        else { block.put("content", "将相遇写成诗，将时光留给你"); }
        block.putArray(BINDINGS_FIELD).addObject().put(SLOT_FIELD, FONT_SLOT).put(REF_FIELD, FONT_REF);
        return proposal;
    }

    /** 构造 CREATE 或 INSERT_COMPONENT 信封，共用实际生产校验入口。 */
    private ObjectNode output(ObjectNode request, ObjectNode proposal) {
        String mode = request.path("input").path("mode").asText();
        ObjectNode output = StrictAiJson.mapper().createObjectNode()
                .put("schemaVersion", AiPortfolioProtocol.CONTRACT_VERSION).put("promptVersion", AiPortfolioProtocol.PROMPT_VERSION)
                .put("status", "CANDIDATE").put("mode", mode).putNull("page");
        output.putArray("questions"); output.putArray("notes"); output.putArray("operations");
        if (CREATE_MODE.equals(mode)) {
            ObjectNode page = output.putObject("page").put("menuKey", AiPortfolioProtocol.MENU_ALIAS);
            page.putObject("settings"); page.putArray("components").add(proposal);
        } else {
            ObjectNode insert = output.withArray("operations").addObject().put("op", "INSERT_COMPONENT")
                    .put("reason", "添加多段文艺描述")
                    .put("expectedHash", AiPortfolioProtocol.expectedHash(AiPortfolioProtocol.currentComponents(request)));
            insert.putObject("target").put("menuKey", AiPortfolioProtocol.MENU_ALIAS);
            insert.putObject("payload").put("position", "END").putNull("anchorComponentKey").set("component", proposal);
        }
        return output;
    }

    /** 取出提案中的首个文字块，便于对单一边界进行变更。 */
    private ObjectNode block(ObjectNode proposal) { return (ObjectNode) proposal.path(PARAMS_FIELD).path(BLOCKS_FIELD).get(0); }

    /** 取出冻结字体定义，避免测试直接写入模型可见业务身份。 */
    private ObjectNode font(ObjectNode request) { return (ObjectNode) request.path(FACTS_FIELD).path(BINDINGS_FIELD).get(0); }

    /** 所有实际文字块都能在新建时绑定可信字体，包括使用 items 的列表。 */
    @ParameterizedTest
    @ValueSource(strings = {"TITLE", PARAGRAPH_TYPE, LIST_TYPE, "HINT"})
    void acceptsTrustedFontForEveryTextBlockType(String type) {
        ObjectNode request = request(CREATE_MODE), output = output(request, proposal(type));
        assertThat(AiPortfolioProtocol.validate(request, output.toString())).isEqualTo(output);
    }

    /** 不要求字体的旧提案保持有效，可选空绑定数组也不改变文字语义。 */
    @Test
    void acceptsBlocksWithoutFontBindingAndWithEmptyBindings() {
        ObjectNode request = request(CREATE_MODE), proposal = proposal(PARAGRAPH_TYPE);
        block(proposal).remove(BINDINGS_FIELD);
        ObjectNode output = output(request, proposal);
        assertThat(AiPortfolioProtocol.validate(request, output.toString())).isEqualTo(output);
        block(proposal).putArray(BINDINGS_FIELD);
        assertThat(AiPortfolioProtocol.validate(request, output.toString())).isEqualTo(output);
    }

    /** 多段文字可复用同一新字体，视频背景仍使用组件级独立槽位。 */
    @Test
    void acceptsSharedFontAcrossBlocksWithIndependentVideoBackground() {
        ObjectNode request = request(CREATE_MODE), proposal = proposal(PARAGRAPH_TYPE);
        request.withObject(FACTS_FIELD).withArray("sources").addObject().put("sourceNo", 2)
                .put("kind", "WORK").put("sourceId", "202").put("mediaType", "VIDEO").put("canBind", true);
        proposal.withObject(PARAMS_FIELD).put("backgroundEnabled", true);
        proposal.withArray(BINDINGS_FIELD).addObject().put(SLOT_FIELD, "backgroundWorkId").put("sourceNo", 2);
        proposal.withObject(PARAMS_FIELD).withArray(BLOCKS_FIELD).add(block(proposal(PARAGRAPH_TYPE)));
        ObjectNode output = output(request, proposal);
        assertThat(AiPortfolioProtocol.validate(request, output.toString())).isEqualTo(output);
    }

    /** 未知句柄、错误资源类型和缺少字体槽位权限都必须定位到块内绑定。 */
    @ParameterizedTest
    @ValueSource(strings = {"UNKNOWN_REF", "WRONG_KIND", "MISSING_SLOT", "REUSE_ONLY"})
    void rejectsUntrustedFontResourcesAtBlockBinding(String failure) {
        ObjectNode request = request(CREATE_MODE), proposal = proposal(PARAGRAPH_TYPE);
        switch (failure) {
            case "UNKNOWN_REF" -> ((ObjectNode) block(proposal).path(BINDINGS_FIELD).get(0)).put(REF_FIELD, "unknown_private_ref");
            case "WRONG_KIND" -> font(request).put("kind", "WORK");
            case "MISSING_SLOT" -> font(request).putArray("allowedSlots");
            case "REUSE_ONLY" -> font(request).put("reuseOnly", true).put("componentKey", COMPONENT_KEY);
            default -> throw new AssertionError(failure);
        }
        ObjectNode output = output(request, proposal);
        assertThatThrownBy(() -> AiPortfolioProtocol.validate(request, output.toString()))
                .isInstanceOf(AiProtocolException.class).hasMessageContaining(CREATE_BINDING_PATH)
                .hasMessageContaining(FONT_SLOT).hasMessageNotContaining("unknown_private_ref").hasMessageNotContaining(FONT_ID);
    }

    /** 封闭绑定结构禁止来源编号、重复槽位、直接字体身份和其它节点槽位。 */
    @ParameterizedTest
    @ValueSource(strings = {"SOURCE_NO", "DUPLICATE", "RAW_FONT_ID", "WRONG_SLOT", "EXTRA_FIELD"})
    void rejectsInvalidBlockFontEnvelope(String failure) {
        ObjectNode request = request(CREATE_MODE), proposal = proposal(PARAGRAPH_TYPE);
        ObjectNode block = block(proposal), binding = (ObjectNode) block.path(BINDINGS_FIELD).get(0);
        switch (failure) {
            case "SOURCE_NO" -> { binding.remove(REF_FIELD); binding.put("sourceNo", 1); }
            case "DUPLICATE" -> block.withArray(BINDINGS_FIELD).add(binding.deepCopy());
            case "RAW_FONT_ID" -> { block.remove(BINDINGS_FIELD); block.put(FONT_SLOT, FONT_ID); }
            case "WRONG_SLOT" -> binding.put(SLOT_FIELD, "backgroundWorkId");
            case "EXTRA_FIELD" -> binding.put("value", FONT_ID);
            default -> throw new AssertionError(failure);
        }
        ObjectNode output = output(request, proposal);
        assertThatThrownBy(() -> AiPortfolioProtocol.validate(request, output.toString())).isInstanceOf(AiProtocolException.class);
    }

    /** 留白块没有文字，连空绑定数组也不能携带。 */
    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void rejectsSpacerBindingsEvenWhenEmpty(boolean empty) {
        ObjectNode request = request(CREATE_MODE), proposal = proposal(PARAGRAPH_TYPE);
        ObjectNode spacer = proposal.withObject(PARAMS_FIELD).withArray(BLOCKS_FIELD).addObject()
                .put(TYPE_FIELD, SPACER_TYPE).put("heightRpx", 32);
        ArrayNode bindings = spacer.putArray(BINDINGS_FIELD);
        if (!empty) { bindings.addObject().put(SLOT_FIELD, FONT_SLOT).put(REF_FIELD, FONT_REF); }
        ObjectNode output = output(request, proposal);
        assertThatThrownBy(() -> AiPortfolioProtocol.validate(request, output.toString())).isInstanceOf(AiProtocolException.class);
        spacer.remove(BINDINGS_FIELD);
        assertThat(AiPortfolioProtocol.validate(request, output.toString())).isEqualTo(output);
    }

    /** 页面修改中新插入的文字组件具有与首次生成一致的字体能力。 */
    @Test
    void acceptsTrustedFontWhenInsertingStructuredComponent() {
        ObjectNode request = request(MODIFY_MODE), output = output(request, proposal(PARAGRAPH_TYPE));
        assertThat(AiPortfolioProtocol.validate(request, output.toString())).isEqualTo(output);
    }

    /** 原位保留字体不能借新建组件操作复制到另一文字位置。 */
    @Test
    void rejectsReuseOnlyFontWhenInsertingStructuredComponent() {
        ObjectNode request = request(MODIFY_MODE);
        font(request).put("reuseOnly", true).put("componentKey", COMPONENT_KEY).put("nodeKey", BLOCK_KEY);
        ObjectNode output = output(request, proposal(PARAGRAPH_TYPE));
        assertThatThrownBy(() -> AiPortfolioProtocol.validate(request, output.toString()))
                .isInstanceOf(AiProtocolException.class).hasMessageContaining(INSERT_BINDING_PATH);
    }

    /** 本次能力只覆盖结构化文字，不向新建文字网格开放片段字体绑定。 */
    @Test
    void keepsNewTextGridFontBindingsOutsideContract() {
        ObjectNode request = request(CREATE_MODE);
        ObjectNode proposal = StrictAiJson.parseObject("""
                {"type":"TEXT_GRID","sourceNos":[],"bindings":[],"params":{"rows":1,"columns":1,
                 "cells":[{"row":0,"column":0,"rowSpan":1,"columnSpan":1,
                   "blocks":[{"runs":[{"text":"文字网格","bindings":[{"slot":"fontId","ref":"font_1"}]}]}]}]}}
                """, StrictAiJson.RESPONSE_MAX_BYTES, StrictAiJson.MAX_DEPTH);
        ObjectNode output = output(request, proposal);
        assertThatThrownBy(() -> AiPortfolioProtocol.validate(request, output.toString())).isInstanceOf(AiProtocolException.class);
    }

    /** 已有结构化文字块继续允许在原位置保留 reuseOnly 字体。 */
    @Test
    void preservesExistingStructuredBlockFontRebinding() {
        ObjectNode request = request(MODIFY_MODE);
        ObjectNode component = request.withObject(FACTS_FIELD).withObject("baseConfig").withArray("components").addObject()
                .put("componentKey", COMPONENT_KEY).put("componentType", STRUCTURED_TYPE).put("sortOrder", 1000).put("enabled", true);
        ObjectNode existingBlock = component.putObject("config").putArray(BLOCKS_FIELD).addObject()
                .put("blockKey", BLOCK_KEY).put(TYPE_FIELD, PARAGRAPH_TYPE).put("content", "已有文字").put(FONT_SLOT, FONT_ID);
        font(request).put("reuseOnly", true).put("componentKey", COMPONENT_KEY).put("nodeKey", BLOCK_KEY);
        ObjectNode output = output(request, proposal(PARAGRAPH_TYPE));
        ObjectNode operation = output.withArray("operations").removeAll().addObject()
                .put("op", "BIND_RESOURCES").put("reason", "保留当前字体").put("expectedHash", AiPortfolioProtocol.expectedHash(existingBlock));
        operation.putObject("target").put("menuKey", AiPortfolioProtocol.MENU_ALIAS).put("componentKey", "cmp_1").put("blockKey", "block_1");
        ObjectNode payload = operation.putObject("payload"); payload.putArray("sourceNos");
        payload.putArray(BINDINGS_FIELD).addObject().put(SLOT_FIELD, FONT_SLOT).put(REF_FIELD, FONT_REF);
        assertThat(AiPortfolioProtocol.validate(request, output.toString())).isEqualTo(output);
    }
}
