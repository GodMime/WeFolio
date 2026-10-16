package com.jxc.wefolio.job.service;

import com.jxc.wefolio.job.config.PortfolioAiProperties;
import org.junit.jupiter.api.Test;

import java.util.List;

import static com.jxc.wefolio.job.service.PortfolioAiReferenceTestSupport.*;
import static org.assertj.core.api.Assertions.assertThat;

/** 在实际模型请求边界验证参考结构提取和组件映射规则，不能替代模型视觉效果验收。 */
class PortfolioAiReferenceLayoutPromptTest {
    /** 模板携带当前实际编号及整数版本，不依赖特定附件编号或业务描述。 */
    @Test void referenceOutputTemplateUsesIntegerVersionAndActualNumbers() throws Exception {
        var properties = new PortfolioAiProperties();
        properties.setApiKey("test-only-token");
        var client = new TokenHubPortfolioAiClient(properties);
        var inputs = List.<com.fasterxml.jackson.databind.JsonNode>of(item(3, false), item(8, false));
        var payload = client.referencePayload(task(), request(), List.of(
                new PortfolioAiModelClient.MediaInput("image_url", "https://private.example/one", null),
                new PortfolioAiModelClient.MediaInput("image_url", "https://private.example/two", null)), inputs);
        String templateText = payload.path("messages").get(1).path("content").get(1).path("text").asText();
        var template = new com.fasterxml.jackson.databind.ObjectMapper().readTree(templateText.substring(templateText.indexOf('{')));
        assertThat(template.path("version").isIntegralNumber()).isTrue();
        assertThat(template.path("version").asInt()).isEqualTo(1);
        assertThat(template.path("items")).hasSize(2);
        assertThat(template.path("items").get(0).path("referenceNo").asText()).isEqualTo("A3");
        assertThat(template.path("items").get(1).path("referenceNo").asText()).isEqualTo("A8");
        template.path("items").forEach(item -> ((com.fasterxml.jackson.databind.node.ObjectNode) item).put("summary", "可见页面结构"));
        assertThat(PortfolioAiReferenceContract.summaries(template.toString(), inputs)).hasSize(2);
    }

    /** 录屏摘要须保留分段与网格信息，并排除跳转到的管理页面。 */
    @Test void referenceVideoAnalysisPreservesLayoutInsteadOfOnlyStyle() {
        var properties = new PortfolioAiProperties();
        properties.setApiKey("test-only-token");
        var client = new TokenHubPortfolioAiClient(properties);
        var payload = client.referencePayload(task(item(1, true)), request(),
                List.of(new PortfolioAiModelClient.MediaInput("video_url", "https://private.example/video", null)),
                List.of(item(1, true)));
        assertThat(payload.path("messages").get(0).path("content").asText())
                .contains("从上到下", "独立段落", "行数、列数", "管理页面", "滚动重复",
                        "逐个文字块", "居左、居中或居右", "对齐未知");
    }

    /** 最终请求须把参考布局授权与明确素材约束结合，同时避免移植参考人物事实。 */
    @Test void layoutRequestMapsParagraphsAndGridOnlyWhenReferenceIsPresent() {
        var properties = new PortfolioAiProperties();
        properties.setApiKey("test-only-token");
        var client = new TokenHubPortfolioAiClient(properties);
        var input = request();
        ((com.fasterxml.jackson.databind.node.ObjectNode) input.path("facts"))
                .putArray("referenceSummaries").addObject().put("referenceNo", "A1")
                .put("summary", "首屏多段文字共享背景；下方两行两列数据卡片；跳转详情后返回。");
        var payload = client.payload(task(item(1, true)), input);
        assertThat(payload.path("messages").get(0).path("content").asText())
                .contains("参考布局授权", "多个 PARAGRAPH", "TEXT_GRID", "不能拼成一个", "不能照搬参考中的数字",
                        "share.coverUrl", "用户允许用于该用途", "不要求固定句式", "不预设固定格数",
                        "居左为 LEFT、居中为 CENTER、居右为 RIGHT", "显式填写各块 alignment", "混合对齐");
        assertThat(PortfolioAiReferencePrompt.LAYOUT_RULES)
                .doesNotContain("A1", "1号", "两行两列");
        assertThat(payload.path("messages").get(1).path("content").asText()).contains("两行两列数据卡片");
        assertThat(client.payload(task(), request()).path("messages").get(0).path("content").asText())
                .doesNotContain("参考布局授权")
                .contains("LEFT（居左）", "CENTER（居中）", "RIGHT（居右）", "仅改文案时不改变已有 alignment");
    }
}
