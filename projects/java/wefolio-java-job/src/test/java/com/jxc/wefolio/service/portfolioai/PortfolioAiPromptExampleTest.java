package com.jxc.wefolio.service.portfolioai;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** 使用真实协议校验提示词中的完整绑定示例，防止示例教给模型不可采用的结构。 */
class PortfolioAiPromptExampleTest {
    /** 实际向模型发送的系统提示词资源。 */
    private static final String SYSTEM_RESOURCE = "/portfolioai/system.txt";
    /** 完整示例信封的开始标记，不包括前置场景说明。 */
    private static final String EXAMPLE_START = "完整 CREATE 示例（开始）";
    /** 完整示例信封的结束标记。 */
    private static final String EXAMPLE_END = "完整 CREATE 示例（结束）";
    /** 示例场景中已经冻结的素材与字体，均不依赖真实数据库或远端资源。 */
    private static final String CREATE_FACTS = """
            {"input":{"ownerType":"USER","mode":"CREATE","targetMenuKey":"__root__","prompt":"黑底作品集","baseConfig":null},
             "facts":{"safePrompt":"1号作为作品集封面，2号视频作为多段文艺文字的同一背景，文字使用文艺字体，3号单独展示，4号作为背景音乐，追加档期查询和联系表单",
               "baseConfig":{"schemaVersion":"standard-personal-v1","share":{},"style":{"backgroundColor":"#FFFFFF"},"components":[]},
               "sources":[
                 {"sourceNo":1,"kind":"WORK","sourceId":"101","mediaType":"IMAGE","canBind":true},
                 {"sourceNo":2,"kind":"WORK","sourceId":"102","mediaType":"VIDEO","canBind":true},
                 {"sourceNo":3,"kind":"WORK","sourceId":"103","mediaType":"VIDEO","canBind":true},
                 {"sourceNo":4,"kind":"WORK","sourceId":"104","mediaType":"AUDIO","canBind":true}],
               "bindings":[{"ref":"source_cover_1","kind":"IMAGE","sourceNo":1,"label":"1号作品的封面图片","allowedSlots":["coverUrl"],"reuseOnly":false,
                 "value":"https://cdn.example.test/work/image/one.jpg"},
                 {"ref":"font_1","kind":"FONT","label":"霞鹜文楷","allowedSlots":["fontId"],"reuseOnly":false,
                 "value":{"fontId":"example-font","reference":{"fontVersion":"example-version"}}}]}}
            """;

    /** 读取提示词内唯一的完整信封，直接参与协议校验。 */
    private ObjectNode example() throws IOException {
        try (InputStream stream = getClass().getResourceAsStream(SYSTEM_RESOURCE)) {
            assertThat(stream).isNotNull();
            String prompt = new String(stream.readAllBytes(), StandardCharsets.UTF_8);
            int start = prompt.indexOf(EXAMPLE_START);
            int end = prompt.indexOf(EXAMPLE_END);
            assertThat(start).as("系统提示词必须包含可执行的完整 CREATE 示例").isGreaterThanOrEqualTo(0);
            assertThat(end).isGreaterThan(start);
            assertThat(prompt.indexOf(EXAMPLE_START, start + EXAMPLE_START.length())).isEqualTo(-1);
            String envelope = prompt.substring(start + EXAMPLE_START.length(), end).strip();
            return StrictAiJson.parseObject(envelope, StrictAiJson.RESPONSE_MAX_BYTES, StrictAiJson.MAX_DEPTH);
        }
    }

    /** 为示例提供与当前协议版本一致的冻结任务。 */
    private ObjectNode request() {
        ObjectNode request = StrictAiJson.parseObject(CREATE_FACTS, StrictAiJson.RESPONSE_MAX_BYTES, StrictAiJson.MAX_DEPTH);
        request.put("contractVersion", AiPortfolioProtocol.CONTRACT_VERSION).put("promptVersion", AiPortfolioProtocol.PROMPT_VERSION);
        return request;
    }

    /** 作品集封面必须写入全局分享配置，不擅自增加正文首图。 */
    @Test
    void completeExampleValidatesAndBindsEachPartOfTheReportedScenario() throws IOException {
        ObjectNode example = example();
        assertThat(AiPortfolioProtocol.validate(request(), example.toString())).isEqualTo(example);
        JsonNode page = example.path("page");
        JsonNode components = page.path("components");
        assertThat(page.path("settings").path("share").path("bindings").toString())
                .isEqualTo("[{\"slot\":\"coverUrl\",\"ref\":\"source_cover_1\"}]");
        assertThat(components).hasSize(4);
        assertThat(components.get(0).path("type").asText()).isEqualTo("STRUCTURED_TEXT_SECTION");
        assertThat(components.get(0).path("sourceNos")).isEmpty();
        assertThat(components.get(0).path("bindings").toString()).isEqualTo(
                "[{\"slot\":\"backgroundWorkId\",\"sourceNo\":2}]");
        assertThat(components.get(0).path("params").path("backgroundEnabled").asBoolean()).isTrue();
        JsonNode blocks = components.get(0).path("params").path("blocks");
        assertThat(blocks).hasSizeGreaterThanOrEqualTo(2);
        blocks.forEach(block -> {
            assertThat(block.path("type").asText()).isEqualTo("PARAGRAPH");
            assertThat(block.path("content").asText()).isNotBlank();
            assertThat(block.path("bindings").toString()).isEqualTo("[{\"slot\":\"fontId\",\"ref\":\"font_1\"}]");
        });
        assertThat(components.get(1).path("type").asText()).isEqualTo("SINGLE_WORK");
        assertThat(components.get(1).path("sourceNos").toString()).isEqualTo("[3]");
        assertThat(components.get(2).path("type").asText()).isEqualTo("SCHEDULE_QUERY");
        assertThat(components.get(3).path("type").asText()).isEqualTo("CONTACT_FORM");
        for (int index : new int[]{2, 3}) {
            assertThat(components.get(index).path("sourceNos")).isEmpty();
            assertThat(components.get(index).path("bindings")).isEmpty();
        }
        assertThat(page.path("settings").path("backgroundAudio").path("bindings").toString())
                .isEqualTo("[{\"slot\":\"workId\",\"sourceNo\":4}]");
        assertThat(page.path("settings").path("style").path("params").path("backgroundColor").asText())
                .isEqualTo("#000000");
    }

    /** 示例中块字体与全局音频必须分别使用可绑定的 FONT 与 AUDIO 来源。 */
    @Test
    void completeExampleRequiresTheDeclaredAvailableFontAndAudioMediaType() throws IOException {
        ObjectNode example = example();
        ObjectNode request = request();
        ((ArrayNode) request.path("facts").path("bindings")).remove(1);
        assertThatThrownBy(() -> AiPortfolioProtocol.validate(request, example.toString()))
                .isInstanceOf(AiProtocolException.class);
        request.withObject("facts").set("bindings", request().path("facts").path("bindings"));
        ((ObjectNode) request.path("facts").path("sources").get(3)).put("mediaType", "IMAGE");
        assertThatThrownBy(() -> AiPortfolioProtocol.validate(request, example.toString()))
                .isInstanceOf(AiProtocolException.class);
    }
}
