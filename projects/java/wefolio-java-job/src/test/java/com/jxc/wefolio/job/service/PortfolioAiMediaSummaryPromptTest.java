package com.jxc.wefolio.job.service;

import com.jxc.wefolio.service.portfolioai.PortfolioAiMediaContract;
import com.jxc.wefolio.service.portfolioai.StrictAiJson;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** 实际摘要系统资源、用户提示和请求时刻模板共同决定可持久缓存身份。 */
class PortfolioAiMediaSummaryPromptTest {
    /** 正文从真实生产资源读取，身份包含完整摘要结构前缀及 SHA-256。 */
    @Test void productionResourceAndSchemaVersionAreIncluded() throws Exception {
        var definition = PortfolioAiMediaSummaryPrompt.current();
        try (InputStream input = getClass().getResourceAsStream("/portfolioai/media-summary.txt")) {
            assertThat(input).isNotNull();
            assertThat(definition.system()).isEqualTo(new String(input.readAllBytes(), StandardCharsets.UTF_8));
        }
        assertThat(PortfolioAiMediaSummaryPrompt.version()).isEqualTo(definition.version())
                .matches(PortfolioAiMediaContract.SUMMARY_VERSION + ":[0-9a-f]{64}");
    }

    /** 任一真实提示词段发生变化均主动隔离历史摘要，无需手工修改结构版本常量。 */
    @Test void systemUserHintAndTimestampTemplateEachInvalidateTheIdentity() {
        var current = PortfolioAiMediaSummaryPrompt.current();
        var changedSystem = new PortfolioAiMediaSummaryPrompt.Definition(current.system() + "补充系统规则", current.hint(), current.frameTimeHint());
        var changedHint = new PortfolioAiMediaSummaryPrompt.Definition(current.system(), current.hint() + "补充摘要规则", current.frameTimeHint());
        var changedTime = new PortfolioAiMediaSummaryPrompt.Definition(current.system(), current.hint(), current.frameTimeHint() + "真实时刻");
        assertThat(List.of(changedSystem.version(), changedHint.version(), changedTime.version()))
                .doesNotContain(current.version()).doesNotHaveDuplicates();
    }

    /** 请求构造器保留时刻原格式；模型参数及真实媒体不会反向污染稳定提示词定义。 */
    @Test void concreteMediaAndOtherModelParametersDoNotChangePromptIdentity() {
        var definition = PortfolioAiMediaSummaryPrompt.current();
        String version = definition.version();
        var body = StrictAiJson.mapper().createObjectNode().put("model", "glm-5.3-flash").put("temperature", 1.0);
        definition.apply(body, List.of(new PortfolioAiModelClient.MediaInput(
                "image_url", "https://private.example/frame?signature=fake", 12345L)));
        assertThat(body.path("messages").get(1).path("content").get(1).path("text").asText()).isEqualTo("采样时刻毫秒：12345");
        assertThat(body.path("model").asText()).isEqualTo("glm-5.3-flash");
        assertThat(body.path("temperature").asDouble()).isEqualTo(1.0);
        assertThat(body.path("max_tokens").asInt()).isEqualTo(4096);
        assertThat(definition.version()).isEqualTo(version);
    }
}
