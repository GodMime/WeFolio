package com.jxc.wefolio.service.advancedportfolio;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.jxc.wefolio.entity.PortfolioEntity;
import com.jxc.wefolio.dto.PortfolioConfigDto;
import com.jxc.wefolio.mapper.WorkEntityMapper;
import com.jxc.wefolio.service.PortfolioConfigValidator;
import com.jxc.wefolio.service.portfoliofont.PortfolioFontConfigSupport;
import com.jxc.wefolio.exception.BusinessException;
import org.junit.jupiter.api.Test;
import java.io.InputStream;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.mock;

/** 高级原始保存边界测试，真实小程序字段兼容和编号高水位负例均为确定性验证。 */
class AdvancedPortfolioBoundaryTest {
    /** 严格树构造器，生产解析另由同源 StrictAiJson 单元测试覆盖。 */
    private final ObjectMapper mapper = new ObjectMapper();

    /** 当前普通个人编辑器输出全部组件字段能够通过高级结构受理。 */
    @Test
    void acceptsCurrentPersonalEditorFixture() throws Exception {
        AdvancedPortfolioConfigGuard.validate(fixture("personal-editor-config.json"), false);
    }
    /** 当前普通团队编辑器输出全部组件字段能够通过高级结构受理。 */
    @Test
    void acceptsCurrentTeamEditorFixture() throws Exception {
        AdvancedPortfolioConfigGuard.validate(fixture("team-editor-config.json"), true);
    }
    /** 未知组件私有字段不由 DTO 忽略后进入保存。 */
    @Test
    void rejectsUnknownNestedComponentField() throws Exception {
        ObjectNode config = fixture("personal-editor-config.json");
        ((ObjectNode) config.withArray("components").get(0).get("config")).put("ownerId", 99);
        assertThatThrownBy(() -> AdvancedPortfolioConfigGuard.validate(config, false)).isInstanceOf(BusinessException.class);
    }
    /** 字符串 false 不能被普通开关规范化回默认 true。 */
    @Test
    void rejectsStringInsteadOfBoolean() throws Exception {
        ObjectNode config = fixture("personal-editor-config.json");
        ((ObjectNode) config.withArray("components").get(2).get("config")).put("showTitle", "false");
        assertThatThrownBy(() -> AdvancedPortfolioConfigGuard.validate(config, false)).isInstanceOf(BusinessException.class);
    }
    /** 网格片段中的未知字段整份拒绝。 */
    @Test
    void rejectsUnknownTextRunField() throws Exception {
        ObjectNode config = fixture("personal-editor-config.json");
        for (var component : config.withArray("components")) {
            if ("TEXT_GRID".equals(component.path("componentType").asText())) {
                ((ObjectNode) component.path("config").path("cells").get(0).path("blocks").get(0).path("runs").get(0)).put("html", "<script>");
            }
        }
        assertThatThrownBy(() -> AdvancedPortfolioConfigGuard.validate(config, false)).isInstanceOf(BusinessException.class);
    }
    /** 已公开个人资料快照和标签数组保留为普通受理字段。 */
    @Test
    void acceptsProfileSnapshotAndTags() throws Exception {
        ObjectNode config = (ObjectNode) mapper.readTree("""
                {"schemaVersion":"standard-personal-v1","components":[{"componentKey":"p","componentType":"PROFILE","enabled":true,"sortOrder":1,
                "config":{"profile":{"avatarUrl":"","displayName":"婚礼主持人","profession":"主持人","city":"上海","bio":"简介","wechatQrUrl":"","tags":[{"name":"婚礼","color":"BLUE"}]},
                "visibleFields":{"avatar":true,"tags":true,"wechatQr":false}}}]}
                """);
        AdvancedPortfolioConfigGuard.validate(config, false);
    }
    /** 普通规范化显式非法值回默认时，高级入口必须拒绝。 */
    @Test
    void rejectsLossyNormalizationOfExplicitValue() throws Exception {
        ObjectNode input = (ObjectNode) mapper.readTree("{\"style\":{\"backgroundColor\":\"bad-color\"}}");
        ObjectNode output = (ObjectNode) mapper.readTree("{\"style\":{\"backgroundColor\":\"#FFFFFF\"}}");
        assertThatThrownBy(() -> AdvancedPortfolioConfigGuard.assertNormalized(input, output)).isInstanceOf(BusinessException.class);
    }
    /** 合法颜色大小写与派生排序允许，文字内容不得静默改变。 */
    @Test
    void acceptsOnlyRegisteredDerivedNormalization() throws Exception {
        ObjectNode input = (ObjectNode) mapper.readTree("{\"style\":{\"backgroundColor\":\"#ffffff\"},\"components\":[]}");
        ObjectNode output = (ObjectNode) mapper.readTree("{\"style\":{\"backgroundColor\":\"#FFFFFF\"},\"components\":[]}");
        AdvancedPortfolioConfigGuard.assertNormalized(input, output);
        input.put("text", "原内容"); output.put("text", "其他内容");
        assertThatThrownBy(() -> AdvancedPortfolioConfigGuard.assertNormalized(input, output)).isInstanceOf(BusinessException.class);
    }
    /** 真实普通校验与字体合并后，仅裁剪未引用字体并保留实际版本。 */
    @Test
    void supportsRealTextFontAndDisabledBackgroundNormalization() throws Exception {
        ObjectNode incoming = (ObjectNode) mapper.readTree("""
                {"schemaVersion":"standard-personal-v1","editorSchemaRevision":14,"share":{"title":"字体作品集"},"components":[
                  {"componentKey":"text","componentType":"TEXT_SECTION","enabled":true,"sortOrder":10,"config":{
                    "content":"正文内容","alignment":"LEFT","fontFamily":"WECHAT_SANS_SS","fontId":"ALLURA","fontSizeRpx":28,
                    "backgroundEnabled":false,"backgroundWorkId":10,"backgroundTreatment":"GRADIENT","verticalAlignment":"CENTER"}}],
                  "fonts":{"ALLURA":{"fontVersion":"v1"},"UNUSED":{"fontVersion":"v2"}}}
                """);
        AdvancedPortfolioConfigGuard.validate(incoming, false);
        PortfolioConfigDto input = JSON.parseObject(incoming.toString(), PortfolioConfigDto.class);
        PortfolioConfigDto normalized = new PortfolioConfigValidator(mock(WorkEntityMapper.class)).normalizeForDraft(7L, input, null);
        PortfolioFontConfigSupport.merge(normalized, input, null, true);
        ObjectNode saved = (ObjectNode) mapper.readTree(JSON.toJSONString(normalized));
        AdvancedPortfolioConfigGuard.assertNormalized(incoming, saved);
        assertThat(saved.path("fonts").path("ALLURA").path("fontVersion").asText()).isEqualTo("v1");
        assertThat(saved.path("fonts").has("UNUSED")).isFalse();
        ((ObjectNode) saved.path("fonts").path("ALLURA")).put("fontVersion", "other");
        assertThatThrownBy(() -> AdvancedPortfolioConfigGuard.assertNormalized(incoming, saved)).isInstanceOf(BusinessException.class);
    }
    /** 无字体引用时，普通保存把空版本表收束为 null；不能借此丢失非空版本表。 */
    @Test
    void acceptsOnlyEmptyFontTablePruning() throws Exception {
        ObjectNode incoming = (ObjectNode) mapper.readTree("{\"components\":[],\"fonts\":{}}");
        ObjectNode normalized = (ObjectNode) mapper.readTree("{\"components\":[]}");
        AdvancedPortfolioConfigGuard.assertNormalized(incoming, normalized);
        incoming = (ObjectNode) mapper.readTree("{\"components\":[{\"componentKey\":\"t\",\"componentType\":\"TEXT_SECTION\",\"enabled\":true,\"sortOrder\":1000,\"config\":{\"fontId\":\"ALLURA\"}}],\"fonts\":{\"ALLURA\":{\"fontVersion\":\"v1\"}}}");
        normalized = incoming.deepCopy(); normalized.remove("fonts");
        ObjectNode before = incoming; ObjectNode after = normalized;
        assertThatThrownBy(() -> AdvancedPortfolioConfigGuard.assertNormalized(before, after)).isInstanceOf(BusinessException.class);
    }
    /** DTO 的 Integer 字段不能溢出后隐式转换成负数或另一个值。 */
    @Test
    void rejectsOverflowIntegerDtoField() throws Exception {
        ObjectNode config = (ObjectNode) mapper.readTree("{\"schemaVersion\":\"standard-personal-v1\",\"editorSchemaRevision\":2147483648,\"components\":[]}");
        assertThatThrownBy(() -> AdvancedPortfolioConfigGuard.validate(config, false)).isInstanceOf(BusinessException.class);
    }
    /** 移除号无需墓碑，后续新增必须从高水位继续。 */
    @Test
    void acceptsRemovedNumbersAndMonotonicNewSource() {
        AdvancedPortfolioSaveMetadata.validateContext(context(6, "{\"sourceNo\":1,\"kind\":\"WORK\",\"sourceId\":\"10\"},{\"sourceNo\":5,\"kind\":\"WORK\",\"sourceId\":\"20\"}"), context(5, "{\"sourceNo\":1,\"kind\":\"WORK\",\"sourceId\":\"10\"}").toJSONString());
    }
    /** 旧号不允许重新绑定，即使旧来源已移除也不能回收。 */
    @Test
    void rejectsRebindingOldNumber() {
        String old = context(5, "{\"sourceNo\":1,\"kind\":\"WORK\",\"sourceId\":\"10\"}").toJSONString();
        assertThatThrownBy(() -> AdvancedPortfolioSaveMetadata.validateContext(context(5,"{\"sourceNo\":1,\"kind\":\"WORK\",\"sourceId\":\"11\"}"),old)).isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> AdvancedPortfolioSaveMetadata.validateContext(context(5,"{\"sourceNo\":2,\"kind\":\"WORK\",\"sourceId\":\"11\"}"),old)).isInstanceOf(BusinessException.class);
    }
    /** 高水位回退、重复号码和同来源重复均拒绝。 */
    @Test
    void rejectsContextWatermarkAndDuplicates() {
        String old = context(5, "").toJSONString();
        assertThatThrownBy(() -> AdvancedPortfolioSaveMetadata.validateContext(context(4,""), old)).isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> AdvancedPortfolioSaveMetadata.validateContext(context(3,"{\"sourceNo\":1,\"kind\":\"WORK\",\"sourceId\":\"10\"},{\"sourceNo\":2,\"kind\":\"WORK\",\"sourceId\":\"10\"}"), null)).isInstanceOf(BusinessException.class);
    }
    /** 未参与展示的来源条目也必须处于数据库 Long 正整数范围。 */
    @Test
    void rejectsOverflowContextSourceId() {
        assertThatThrownBy(() -> AdvancedPortfolioSaveMetadata.validateContext(
                context(2, "{\"sourceNo\":1,\"kind\":\"WORK\",\"sourceId\":\"9223372036854775808\"}"), null))
                .isInstanceOf(BusinessException.class);
        AdvancedPortfolioSaveMetadata.validateContext(
                context(2, "{\"sourceNo\":1,\"kind\":\"WORK\",\"sourceId\":\"9223372036854775807\"}"), null);
    }
    /** 可选字段未传时保存元数据必须保留旧值。 */
    @Test
    void omissionKeepsSavedContextAndPrompt() {
        PortfolioEntity portfolio = new PortfolioEntity();
        portfolio.setAiContextJson(context(5, "").toJSONString()); portfolio.setAiPrompt("旧描述");
        new AdvancedPortfolioSaveMetadata(null, false, null, false, "{}").apply(portfolio);
        assertThat(portfolio.getAiPrompt()).isEqualTo("旧描述"); assertThat(JSON.parseObject(portfolio.getAiContextJson()).getInteger("nextSourceNo")).isEqualTo(5);
    }
    /** 从真正编辑器输出读取完整普通字段样本。 */
    private ObjectNode fixture(String name) throws Exception {
        try (InputStream input = getClass().getResourceAsStream("/advanced-portfolio/" + name)) { return (ObjectNode) mapper.readTree(input); }
    }
    /** 构造版本化来源上下文。 */
    private JSONObject context(int next, String entries) { return JSON.parseObject("{\"version\":1,\"nextSourceNo\":" + next + ",\"sources\":[" + entries + "]}"); }
}
