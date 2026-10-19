package com.jxc.wefolio.job.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.jxc.wefolio.job.config.PortfolioAiProperties;
import com.jxc.wefolio.job.repo.PortfolioAiMediaRepository;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;

import static com.jxc.wefolio.job.service.PortfolioAiReferenceTestSupport.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** 附件和最终布局共用费用与期限，任何坏摘要、权限变化或预算不足均停止后续付费。 */
class PortfolioAiReferenceExecutionTest {
    private final PortfolioAiMediaRepository fees = mock(PortfolioAiMediaRepository.class);
    private final PortfolioAiMediaAccessService works = mock(PortfolioAiMediaAccessService.class);
    private final PortfolioAiReferenceAccessService access = mock(PortfolioAiReferenceAccessService.class);
    private final PortfolioAiModelClient model = mock(PortfolioAiModelClient.class);
    private final PortfolioAiProperties properties = new PortfolioAiProperties();
    private final PortfolioAiMediaExecutionService service = new PortfolioAiMediaExecutionService(fees, works, model, properties, access);

    @Test void disabledAdmissionStillExecutesImagesThenFullVideoAndProjectsOnlySummaries() {
        var task = task(item(1, false), item(2, true));
        when(access.signedAfterVerification(anyList())).thenAnswer(call -> {
            List<JsonNode> items = call.getArgument(0);
            return List.of(new PortfolioAiModelClient.MediaInput(items.getFirst().path("mediaType").asText().equals("VIDEO") ? "video_url" : "image_url", "https://private.example/signed", null));
        });
        when(model.summarizeReferences(eq(task), any(), anyList(), anyList(), any())).thenAnswer(call -> {
            List<JsonNode> items = call.getArgument(3);
            return new PortfolioAiModelClient.Completion("{\"version\":1,\"items\":[{\"referenceNo\":\"" + items.getFirst().path("referenceNo").asText()
                    + "\",\"summary\":\"暖色与留白，部分内容无法确认\"}]}", 400_000L, 200L);
        });
        when(model.textRequestBytes(eq(task), any())).thenReturn(1000);
        when(model.generateWithUsage(eq(task), any(), any())).thenReturn(new PortfolioAiModelClient.Completion("final", 1000L, 50L));
        when(fees.reserve(eq(task), anyInt(), any())).thenReturn(1, 2, 3);
        assertThat(service.generate(task, request(), () -> { })).isEqualTo("final");
        verify(fees).checkCapacity(eq(task), eq(165), eq(3), any());
        verify(fees, times(2)).reserve(eq(task), eq(81), any());
        verify(fees, times(3)).settle(eq(task), anyInt(), anyLong(), anyLong(), isNull(), any(), any());
        ArgumentCaptor<ObjectNode> sent = ArgumentCaptor.forClass(ObjectNode.class);
        verify(model).generateWithUsage(eq(task), sent.capture(), any());
        String layout = sent.getValue().toString();
        assertThat(layout).contains("referenceSummaries", "A1", "A2").doesNotContain("objectKey", "signed", "attachmentId");
        verify(fees, never()).cache(anyLong(), any(), anyString(), any());
        verify(model, never()).summarize(any(), any(), anyList(), any(JsonNode.class), any());
    }

    /** 缓存键必须绑定实际系统提示词，阶段名称不随提示词版本改变。 */
    @Test void referenceCacheKeyUsesActualSystemTemplateAndStableStage() throws Exception {
        properties.setApiKey("test-only-token");
        var client = new TokenHubPortfolioAiClient(properties);
        var payload = client.referencePayload(task(item(1, false)), request(),
                List.of(new PortfolioAiModelClient.MediaInput("image_url", "https://private.example/image", null)), List.of(item(1, false)));
        String scope = "wefolio-portfolio-ai-cache-v1\n" + payload.path("model").asText()
                + "\nreference-summary\n" + payload.path("messages").get(0).path("content").asText();
        String expected = java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256")
                .digest(scope.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        assertThat(payload.path("prompt_cache_key").asText()).isEqualTo(expected);
    }

    /** 同一已验证调用不重复 HEAD，付费调用之间及最终返回仍各自复核。 */
    @Test void twoReferenceGroupsHaveOneSourceVerificationPerCallBoundary() {
        var task = task(item(1, false), item(2, true));
        when(model.summarizeReferences(eq(task), any(), anyList(), anyList(), any())).thenAnswer(call -> {
            List<JsonNode> items = call.getArgument(3);
            return new PortfolioAiModelClient.Completion("{\"version\":1,\"items\":[{\"referenceNo\":\""
                    + items.getFirst().path("referenceNo").asText() + "\",\"summary\":\"留白\"}]}", 100L, 20L);
        });
        when(model.generateWithUsage(eq(task), any(), any())).thenReturn(new PortfolioAiModelClient.Completion("final", 100L, 20L));
        assertThat(service.generate(task, request(), () -> { })).isEqualTo("final");
        verify(access, times(5)).verify(eq(task), argThat(items -> items.size() == 2));
    }

    /** 真实访问服务覆盖签名调用，两个媒体请求仍在每个付费边界及最终发布前复核全部原件。 */
    @Test void actualSigningDoesNotRepeatCosChecksInsideTheSameCallBoundary() {
        var first = item(1, false); var second = item(2, true); var task = task(first, second);
        var sources = mock(com.jxc.wefolio.job.repo.PortfolioAiReferenceRepository.class);
        var storage = mock(PortfolioAiReferenceStorage.class);
        var realAccess = new PortfolioAiReferenceAccessService(works, sources, storage, properties);
        var realService = new PortfolioAiMediaExecutionService(fees, works, model, properties, realAccess);
        when(sources.currentOwner(task)).thenReturn(new com.jxc.wefolio.job.repo.PortfolioAiReferenceRepository.Owner("USER", 2, "WFTEST"));
        for (var item : List.of(first, second)) {
            var head = new com.qcloud.cos.model.ObjectMetadata();
            head.setContentLength(item.path("sizeBytes").asLong()); head.setContentType(item.path("mimeType").asText());
            head.setLastModified(new java.util.Date(item.path("contentVersion").path("lastModifiedEpochMs").asLong()));
            head.setHeader("ETag", item.path("contentVersion").path("etag").asText());
            when(storage.head(item.path("objectKey").asText())).thenReturn(head);
        }
        when(storage.sign(anyString(), any())).thenReturn("https://private.example/signed");
        when(model.summarizeReferences(eq(task), any(), anyList(), anyList(), any())).thenAnswer(call -> {
            List<JsonNode> items = call.getArgument(3);
            return new PortfolioAiModelClient.Completion("{\"version\":1,\"items\":[{\"referenceNo\":\""
                    + items.getFirst().path("referenceNo").asText() + "\",\"summary\":\"留白\"}]}", 100L, 20L);
        });
        when(model.generateWithUsage(eq(task), any(), any())).thenReturn(new PortfolioAiModelClient.Completion("final", 100L, 20L));
        assertThat(realService.generate(task, request(), () -> { })).isEqualTo("final");
        verify(storage, times(5)).requireVersioningOff();
        verify(storage, times(10)).head(anyString());
        verify(storage, times(2)).sign(anyString(), any());
    }

    /** 第一组付费结束后撤权，在第二组发送前仍会拦截，不能因减少重复检查而漏检。 */
    @Test void revokedReferencesAfterFirstGroupStopTheNextPaidCall() {
        var task = task(item(1, false), item(2, true));
        doNothing().doNothing().doThrow(new PortfolioAiCallException("REFERENCE_UNAVAILABLE", "失效", false))
                .when(access).verify(eq(task), anyList());
        when(model.summarizeReferences(eq(task), any(), anyList(), anyList(), any())).thenReturn(
                new PortfolioAiModelClient.Completion("{\"version\":1,\"items\":[{\"referenceNo\":\"A1\",\"summary\":\"留白\"}]}", 100L, 20L));
        assertThatThrownBy(() -> service.generate(task, request(), () -> { })).isInstanceOf(PortfolioAiCallException.class);
        verify(model, times(1)).summarizeReferences(eq(task), any(), anyList(), anyList(), any());
        verify(model, never()).generateWithUsage(any(), any(), any());
        verify(fees, times(1)).reserve(eq(task), anyInt(), any());
    }

    @Test void invalidOrMissingSummaryNeverGeneratesPartialLayout() {
        var task = task(item(1, false));
        when(access.signedAfterVerification(anyList())).thenReturn(List.of(new PortfolioAiModelClient.MediaInput("image_url", "https://private.example/i", null)));
        when(model.summarizeReferences(eq(task), any(), anyList(), anyList(), any()))
                .thenReturn(new PortfolioAiModelClient.Completion("{\"version\":1,\"items\":[]}", 10L, 10L));
        assertThatThrownBy(() -> service.generate(task, request(), () -> { })).isInstanceOf(PortfolioAiCallException.class);
        verify(fees).settle(eq(task), anyInt(), eq(10L), eq(10L), isNull(), any(), any());
        verify(model, never()).generateWithUsage(any(), any(), any());
    }

    /** 实际执行失败日志须可定位调用和长度上限，不能泄漏模型原文或异常。 */
    @Test void invalidSummaryLogsSafeDiagnosticAndKeepsSettlement() {
        var logger = (ch.qos.logback.classic.Logger) org.slf4j.LoggerFactory.getLogger(PortfolioAiMediaExecutionService.class);
        var events = new ch.qos.logback.core.read.ListAppender<ch.qos.logback.classic.spi.ILoggingEvent>();
        var originalLevel = logger.getLevel();
        events.start(); logger.addAppender(events); logger.setLevel(ch.qos.logback.classic.Level.WARN);
        try {
            var task = task(item(1, true));
            task.setRequestedByUniqueCode("WFREQUEST1");
            String text = "private-content https://private.example/?signature=secret 13800138000 " + "中".repeat(2001);
            when(fees.reserve(eq(task), anyInt(), any())).thenReturn(7);
            when(model.summarizeReferences(eq(task), any(), anyList(), anyList(), any())).thenReturn(
                    new PortfolioAiModelClient.Completion("{\"version\":1,\"items\":[{\"referenceNo\":\"A1\",\"summary\":\"" + text + "\"}]}", 93000L, 45L));
            assertThatThrownBy(() -> service.generate(task, request(), () -> { }))
                    .isInstanceOf(PortfolioAiReferenceContract.SummaryValidationException.class);
            var logs = events.list.stream().filter(event -> event.getFormattedMessage().startsWith("高级 AI 参考附件摘要校验失败")).toList();
            assertThat(logs).hasSize(1);
            assertThat(logs.getFirst().getFormattedMessage()).contains("taskId=1", "requestedByUniqueCode=WFREQUEST1", "attempt=1", "callNo=7", "itemCount=1",
                    "promptTokens=93000", "completionTokens=45", "reason=SUMMARY_TOO_LONG", "expected=2000")
                    .doesNotContain("private-content", "signature", "13800138000", "https://");
            assertThat(logs.getFirst().getThrowableProxy()).isNull();
            verify(fees).settle(eq(task), eq(7), eq(93000L), eq(45L), isNull(), any(), any());
            verify(model, never()).generateWithUsage(any(), any(), any());
        } finally {
            logger.detachAppender(events); events.stop(); logger.setLevel(originalLevel);
        }
    }

    @Test void upperBoundIsCheckedBeforeAnyPaidAnalysisAndFullTaskCapacityIncludesBothModalities() {
        var task = task(item(1, false), item(2, true));
        when(model.textRequestBytes(eq(task), any())).thenReturn(950_001);
        assertThatThrownBy(() -> service.generate(task, request(), () -> { })).isInstanceOf(PortfolioAiCallException.class);
        verify(fees, never()).reserve(any(), anyInt(), any());
        verify(model, never()).summarizeReferences(any(), any(), anyList(), anyList(), any());
    }

    @Test void privateMediaPayloadNeverMixesImageAndVideoOrLeaksKeysInFinalText() {
        properties.setApiKey("test-only-token");
        TokenHubPortfolioAiClient client = new TokenHubPortfolioAiClient(properties);
        var task = task(item(1, true));
        var payload = client.referencePayload(task, request(), List.of(new PortfolioAiModelClient.MediaInput("video_url", "https://private.example/video", null)), List.of(item(1, true)));
        assertThat(payload.path("max_tokens").asInt()).isEqualTo(16384);
        assertThat(payload.toString()).contains("video_url", "A1", "参考留白").doesNotContain("image_url", "objectKey", "WFTEST");
        assertThatThrownBy(() -> client.referencePayload(task, request(), List.of(
                new PortfolioAiModelClient.MediaInput("video_url", "https://private.example/video", null),
                new PortfolioAiModelClient.MediaInput("image_url", "https://private.example/image", null)), List.of(item(1, true), item(2, false))))
                .isInstanceOf(PortfolioAiCallException.class);
    }
}
