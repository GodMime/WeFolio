package com.jxc.wefolio.job.service;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;

import java.util.List;

import static com.jxc.wefolio.job.service.PortfolioAiReferenceTestSupport.*;
import static org.assertj.core.api.Assertions.*;

/** 严格协议、数量格式边界及完整摘要验证，避免媒体静默降级。 */
class PortfolioAiReferenceContractTest {
    /** 各类坏响应保留既有客户端语义，诊断只包含固定分类和数值。 */
    @Test void summaryDiagnosticsIdentifyFailuresWithoutReturningModelContent() {
        var items = List.<JsonNode>of(item(1, false));
        var cases = new java.util.LinkedHashMap<String, String>();
        cases.put("private-invalid-json", "JSON_PARSE");
        cases.put(" ".repeat(65_537), "JSON_BYTES_LIMIT");
        cases.put("{\"version\":1,\"items\":[],\"private-field\":true}", "ROOT_FIELDS");
        cases.put("{\"version\":2,\"items\":[]}", "VERSION_INVALID");
        cases.put("{\"version\":\"private-version\",\"items\":[]}", "VERSION_STRING");
        cases.put("{\"version\":null,\"items\":[]}", "VERSION_NULL");
        cases.put("{\"version\":1.0,\"items\":[]}", "VERSION_NON_INTEGER");
        cases.put("{\"version\":2147483648,\"items\":[]}", "VERSION_OUT_OF_RANGE");
        cases.put("{\"version\":{},\"items\":[]}", "VERSION_CONTAINER_OR_BOOLEAN");
        cases.put("{\"version\":1,\"items\":{}}", "ITEMS_TYPE");
        cases.put("{\"version\":1,\"items\":[]}", "ITEM_COUNT");
        cases.put("{\"version\":1,\"items\":[{}]}", "ITEM_FIELDS");
        cases.put("{\"version\":1,\"items\":[{\"referenceNo\":1,\"summary\":\"文字\"}]}", "NUMBER_TYPE");
        cases.put("{\"version\":1,\"items\":[{\"referenceNo\":\"private-number\",\"summary\":\"文字\"}]}", "NUMBER_UNKNOWN_OR_DUPLICATE");
        cases.put("{\"version\":1,\"items\":[{\"referenceNo\":\"A1\",\"summary\":{}}]}", "SUMMARY_TYPE");
        for (var entry : java.util.Map.of(" ", "SUMMARY_BLANK", "中".repeat(2001), "SUMMARY_TOO_LONG",
                "https://private.example/secret", "RESOURCE_ADDRESS", items.getFirst().path("attachmentId").asText(), "PRIVATE_IDENTITY").entrySet()) {
            cases.put("{\"version\":1,\"items\":[{\"referenceNo\":\"A1\",\"summary\":\"" + entry.getKey() + "\"}]}", entry.getValue());
        }
        cases.forEach((raw, reason) -> assertThatThrownBy(() -> PortfolioAiReferenceContract.summaries(raw, items))
                .isInstanceOfSatisfying(PortfolioAiReferenceContract.SummaryValidationException.class, exception -> {
                    assertThat(exception.getCode()).isEqualTo("REFERENCE_ANALYSIS_FAILED");
                    assertThat(exception.getMessage()).isEqualTo("参考附件分析结果不完整，请重试或更换附件");
                    assertThat(exception.isRetryable()).isFalse();
                    assertThat(exception.getCause()).isNull();
                    assertThat(exception.diagnostic()).contains("reason=" + reason)
                            .doesNotContain("private", "https://", items.getFirst().path("attachmentId").asText());
                }));
        assertThatThrownBy(() -> PortfolioAiReferenceContract.summaries(null, items))
                .isInstanceOfSatisfying(PortfolioAiReferenceContract.SummaryValidationException.class,
                        exception -> assertThat(exception.diagnostic()).contains("EMPTY_RESPONSE"));
    }

    @Test void acceptsSixMixedItemsAndSeparatesImagesFromWholeVideo() {
        var items = PortfolioAiReferenceContract.items(snapshot(false, item(1, false), item(2, false), item(3, false),
                item(4, false), item(5, false), item(6, true)), false);
        assertThat(items).hasSize(6);
        assertThat(PortfolioAiReferenceContract.groups(items)).extracting(List::size).containsExactly(5, 1);
        assertThat(PortfolioAiReferencePrompt.estimatedInput(List.of(items.getLast()))).isEqualTo(843_000);
    }
    /** 当前合法输入留在模型上下文以内，异常扩大的文字仍能触发发送前上限防线。 */
    @Test void conservativeTextByteBoundDoesNotPreventTheInputGuardFromRejectingOversizedText() {
        var oversized = item(1, false).put("note", "中".repeat(400_000));
        assertThat(PortfolioAiReferencePrompt.estimatedInput(List.of(oversized))).isGreaterThan(PortfolioAiReferenceContract.INPUT_TOKENS);
        assertThatThrownBy(() -> PortfolioAiReferencePrompt.apply(
                com.jxc.wefolio.service.portfolioai.StrictAiJson.mapper().createObjectNode(),
                List.of(new PortfolioAiModelClient.MediaInput("image_url", "https://private.example/image", null)), List.of(oversized)))
                .isInstanceOf(PortfolioAiCallException.class).hasMessage("参考附件输入超过分析上限");
    }

    @Test void rejectsSeventhItemDuplicateNumbersAndSecondVideo() {
        JsonNode[] seven = java.util.stream.IntStream.rangeClosed(1, 7).mapToObj(i -> item(i, false)).toArray(JsonNode[]::new);
        assertThatThrownBy(() -> PortfolioAiReferenceContract.items(snapshot(false, seven), false)).isInstanceOf(PortfolioAiCallException.class);
        assertThatThrownBy(() -> PortfolioAiReferenceContract.items(snapshot(false, item(1, false), item(1, false)), false)).isInstanceOf(PortfolioAiCallException.class);
        assertThatThrownBy(() -> PortfolioAiReferenceContract.items(snapshot(false, item(1, true), item(2, true)), false)).isInstanceOf(PortfolioAiCallException.class);
    }
    @Test void rejectsChangedIdentityUnsupportedVersionAndOversizedMedia() {
        var item = item(1, true); item.put("sizeBytes", 100_000_001);
        assertThatThrownBy(() -> PortfolioAiReferenceContract.items(snapshot(false, item), false)).isInstanceOf(PortfolioAiCallException.class);
        item.put("sizeBytes", 100_000_000); item.put("durationMs", 300_001);
        assertThatThrownBy(() -> PortfolioAiReferenceContract.items(snapshot(false, item), false)).isInstanceOf(PortfolioAiCallException.class);
        item.put("durationMs", 300_000); item.put("attachmentId", "f".repeat(32));
        assertThatThrownBy(() -> PortfolioAiReferenceContract.items(snapshot(false, item), false)).isInstanceOf(PortfolioAiCallException.class);
        assertThatThrownBy(() -> PortfolioAiReferenceContract.items("{\"version\":2,\"items\":[]}", false)).isInstanceOf(PortfolioAiCallException.class);
    }
    @Test void summariesRequireEveryKnownNumberAndRejectAddressesWithoutTruncation() {
        var items = List.<JsonNode>of(item(1, false));
        assertThat(PortfolioAiReferenceContract.summaries("{\"version\":1,\"items\":[{\"referenceNo\":\"A1\",\"summary\":\"暖色，局部遮挡无法确认\"}]}", items)).hasSize(1);
        for (String summary : List.of("https://private.example/file", "x".repeat(2001), items.getFirst().path("objectKey").asText())) {
            String raw = "{\"version\":1,\"items\":[{\"referenceNo\":\"A1\",\"summary\":\"" + summary + "\"}]}";
            assertThatThrownBy(() -> PortfolioAiReferenceContract.summaries(raw, items)).isInstanceOf(PortfolioAiCallException.class);
        }
        assertThatThrownBy(() -> PortfolioAiReferenceContract.summaries("{\"version\":1,\"items\":[]}", items)).isInstanceOf(PortfolioAiCallException.class);
        assertThatThrownBy(() -> PortfolioAiReferenceContract.summaries("{\"version\":1,\"items\":[{\"referenceNo\":\"A2\",\"summary\":\"暖色\"}]}", items)).isInstanceOf(PortfolioAiCallException.class);
    }
}
