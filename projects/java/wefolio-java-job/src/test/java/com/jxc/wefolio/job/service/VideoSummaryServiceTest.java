package com.jxc.wefolio.job.service;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.jxc.wefolio.job.config.VideoSummaryProperties;
import com.jxc.wefolio.job.repo.VideoSummaryRepository;
import com.jxc.wefolio.job.repo.VideoSummaryRepository.Snapshot;
import com.jxc.wefolio.service.portfolioai.PortfolioAiStoredVideoSummary;
import com.jxc.wefolio.service.portfolioai.StrictAiJson;
import com.qcloud.cos.model.ObjectMetadata;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.slf4j.LoggerFactory;

import java.time.Instant;
import java.util.Optional;

import static com.jxc.wefolio.job.service.VideoSummaryService.Outcome.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** 离线验证原视频准入、单次收费、同源保留和重建预算复用。 */
class VideoSummaryServiceTest {
    private final VideoSummaryProperties properties = new VideoSummaryProperties();
    private final VideoSummaryRepository repository = mock(VideoSummaryRepository.class);
    private final VideoAudioWorkLock locks = mock(VideoAudioWorkLock.class);
    private final VideoAudioWorkLock.ExecutionLock lock = mock(VideoAudioWorkLock.ExecutionLock.class);
    private final VideoFrameStorageService storage = mock(VideoFrameStorageService.class);
    private final TokenHubVideoSummaryClient client = mock(TokenHubVideoSummaryClient.class);
    private static final String KEY = "OWNER/work/video/a.mp4";
    private static final String CONTENT = "{\"summary\":\"婚礼主持作品\",\"observations\":[\"舞台上有主持人\"],\"limitations\":[\"字幕不能证明原声内容\"]}";
    private VideoSummaryService service;
    private VideoSummaryService.RunBudget run;
    private Snapshot snapshot;
    /** 捕获真实用例失败日志，验证诊断可用且业务原文不泄漏。 */
    private final Logger logger = (Logger) LoggerFactory.getLogger(VideoSummaryService.class);
    private ListAppender<ILoggingEvent> events;

    /** 不读取真实密钥，不连接 COS、数据库或供应商。 */
    @BeforeEach void setup() {
        events = new ListAppender<>(); events.start(); logger.addAppender(events);
        properties.setEnabled(true);
        service = new VideoSummaryService(properties, repository, locks, storage, client);
        run = service.newRun(); snapshot = snapshot(2, 1, null);
        when(repository.find(1)).thenReturn(snapshot);
        when(repository.save(any(), anyString())).thenReturn(true);
        when(locks.tryAcquire(1)).thenReturn(Optional.of(lock));
        when(storage.head(eq(KEY), any())).thenReturn(metadata(100, "etag"));
        when(storage.headTimeoutSeconds()).thenReturn(30);
        when(storage.signedGet(eq(KEY), any())).thenReturn("https://bucket.cos.ap-guangzhou.myqcloud.com/video?signature=temporary");
        when(client.summarize(eq(1L), anyString(), any())).thenReturn(new PortfolioAiModelClient.Completion(CONTENT, 229508L, 622L));
        when(client.inputHash(anyString())).thenReturn("a".repeat(64));
        when(client.promptVersion()).thenReturn("direct-v1");
    }

    /** 移除监听，避免跨测试累计日志。 */
    @AfterEach void detachLogger() { logger.detachAppender(events); events.stop(); }

    /** 复现作品29返回12条观察：完整校验后保留前8条并沿既有合同保存，只调用一次。 */
    @Test void modelArrayOverflowIsBoundedAndSavedWithoutAnotherPaidRequest() {
        var value = StrictAiJson.parseObject(CONTENT, 16384, 8);
        value.withArray("observations").removeAll();
        value.withArray("limitations").removeAll();
        for (int index = 0; index < 12; index++) { value.withArray("observations").add("private-fact-" + index); }
        for (int index = 0; index < 5; index++) { value.withArray("limitations").add("private-limit-" + index); }
        when(client.summarize(eq(1L), anyString(), any()))
                .thenReturn(new PortfolioAiModelClient.Completion(value.toString(), 79896L, 707L));
        assertEquals(SAVED, service.summarize(1, run, future()));
        ArgumentCaptor<String> json = ArgumentCaptor.forClass(String.class);
        verify(repository).save(eq(snapshot), json.capture());
        var content = PortfolioAiStoredVideoSummary.parse(json.getValue(), source()).path("content");
        assertEquals(8, content.path("observations").size());
        assertEquals(4, content.path("limitations").size());
        for (int index = 0; index < 8; index++) { assertEquals("private-fact-" + index, content.path("observations").get(index).asText()); }
        for (int index = 0; index < 4; index++) { assertEquals("private-limit-" + index, content.path("limitations").get(index).asText()); }
        assertEquals(80603, run.knownTokens()); assertEquals(7, run.knownCostFen());
        verify(client, times(1)).summarize(eq(1L), anyString(), any());
        String logged = events.list.stream().map(ILoggingEvent::getFormattedMessage).reduce("", (left, right) -> left + right);
        assertTrue(logged.contains("observationsBefore=12") && logged.contains("observationsAfter=8"), logged);
        assertTrue(logged.contains("limitationsBefore=5") && logged.contains("limitationsAfter=4"), logged);
        assertFalse(logged.contains("private-fact-") || logged.contains("private-limit-"), logged);
    }

    /** 数量超限不能掩盖舍弃尾项的非法类型，避免以裁剪绕过完整响应校验。 */
    @Test void malformedDiscardedObservationStillFailsAndPreservesUsage() {
        var value = StrictAiJson.parseObject(CONTENT, 16384, 8);
        value.withArray("observations").removeAll();
        for (int index = 0; index < 11; index++) { value.withArray("observations").add("画面事实" + index); }
        value.withArray("observations").addObject().put("private-field-marker", "private-value-marker");
        when(client.summarize(eq(1L), anyString(), any()))
                .thenReturn(new PortfolioAiModelClient.Completion(value.toString(), 79896L, 707L));
        assertEquals(FAILED, service.summarize(1, run, future()));
        verify(repository, never()).save(any(), anyString());
        String message = failureLog();
        assertTrue(message.contains("path=$.observations[11]") && message.contains("rule=TEXT_TYPE"), message);
        assertFalse(message.contains("private-field-marker") || message.contains("private-value-marker"), message);
        assertEquals(80603, run.knownTokens());
        verify(client, times(1)).summarize(eq(1L), anyString(), any());
    }

    /** 已完整返回但字段超长时，日志必须说明本地校验原因，而不是只显示异常类。 */
    @Test void rejectedSummaryLogsValidationStageAndSafeDiagnostic() {
        String privateText = "private-content-marker" + "🎤".repeat(601);
        String content = "{\"summary\":\"" + privateText + "\",\"observations\":[],\"limitations\":[]}";
        when(client.summarize(eq(1L), anyString(), any()))
                .thenReturn(new PortfolioAiModelClient.Completion(content, 20L, 10L));
        assertEquals(FAILED, service.summarize(1, run, future()));
        String message = failureLog();
        assertTrue(message.contains("workId=1"), message);
        assertTrue(message.contains("stage=CONTENT_VALIDATE"), message);
        assertTrue(message.contains("code=INVALID_MEDIA_SUMMARY"), message);
        assertTrue(message.contains("reason=媒体摘要文本超过限制"), message);
        assertTrue(message.contains("diagnostic=") && message.contains("summary") && message.contains("600"), message);
        assertTrue(message.contains("contentCodePoints=" + content.codePointCount(0, content.length())), message);
        assertFalse(message.contains("private-content-marker"), message);
        assertFalse(message.contains("signature=temporary"), message);
        assertNull(events.list.getLast().getThrowableProxy());
        verify(repository, never()).save(any(), anyString());
        assertEquals(30, run.knownTokens());
        verify(client, times(1)).summarize(eq(1L), anyString(), any());
    }

    /** 内容已通过但持久包装非法时须单独标明，避免误判模型字段超长。 */
    @Test void invalidDocumentMetadataLogsSeparateStage() {
        when(client.promptVersion()).thenReturn("invalid\nprivate-prompt-marker");
        assertEquals(FAILED, service.summarize(1, run, future()));
        String message = failureLog();
        assertTrue(message.contains("stage=DOCUMENT_BUILD"), message);
        assertTrue(message.contains("code=INVALID_MEDIA_SUMMARY"), message);
        assertTrue(message.contains("promptVersion"), message);
        assertFalse(message.contains("private-prompt-marker"), message);
        verify(repository, never()).save(any(), anyString());
    }

    /** 数据库异常保留失败阶段和代码位置，不把可能含凭证的异常消息写入日志。 */
    @Test void persistenceFailureLogsStageWithoutRawException() {
        when(repository.save(any(), anyString())).thenThrow(new IllegalStateException("password=private-db-marker"));
        assertEquals(FAILED, service.summarize(1, run, future()));
        String message = failureLog();
        assertTrue(message.contains("stage=SAVE"), message);
        assertTrue(message.contains("exceptionType=IllegalStateException"), message);
        assertTrue(message.contains("failureLocation="), message);
        assertFalse(message.contains("private-db-marker"), message);
        assertNull(events.list.getLast().getThrowableProxy());
    }

    /** 预检故障同样带安全阶段和位置，不输出 COS 签名或原始远端异常。 */
    @Test void preflightFailureLogsHeadStageWithoutSignedUrl() {
        when(storage.head(eq(KEY), any())).thenThrow(new IllegalStateException("https://private.example?signature=secret-marker"));
        assertEquals(FAILED, service.summarize(1, run, future()));
        String message = failureLog();
        assertTrue(message.contains("stage=SOURCE_HEAD"), message);
        assertTrue(message.contains("failureLocation="), message);
        assertFalse(message.contains("secret-marker"), message);
        verifyNoInteractions(client);
    }

    /** 供应商截断须保留明确错误码及已知计量，不能与本地校验拒绝混在一起。 */
    @Test void providerFailureLogsCallStageAndUsage() {
        when(client.summarize(eq(1L), anyString(), any())).thenThrow(new PortfolioAiCallException(
                "OUTPUT_TRUNCATED", "输出已截断", false, new PortfolioAiModelClient.Completion(null, 20L, 10L)));
        assertEquals(FAILED, service.summarize(1, run, future()));
        String message = failureLog();
        assertTrue(message.contains("stage=MODEL_CALL"), message);
        assertTrue(message.contains("code=OUTPUT_TRUNCATED"), message);
        assertTrue(message.contains("promptTokens=20") && message.contains("completionTokens=10"), message);
        assertFalse(message.contains("signature=temporary"), message);
        assertEquals(30, run.knownTokens());
    }

    /** 仅取用例自身的失败事件；没有诊断日志即明确失败。 */
    private String failureLog() {
        return events.list.stream().map(ILoggingEvent::getFormattedMessage)
                .filter(value -> value.contains("失败:"))
                .findFirst().orElseThrow(() -> new AssertionError("未记录失败诊断"));
    }

    /** 原视频无需任何帧或音频即可生成，保存包装不含短期链接。 */
    @Test void storesDirectVideoSummaryAndTracksUsage() {
        assertEquals(SAVED, service.summarize(1, run, future()));
        ArgumentCaptor<String> json = ArgumentCaptor.forClass(String.class);
        verify(repository).save(eq(snapshot), json.capture());
        var document = PortfolioAiStoredVideoSummary.parse(json.getValue(), source());
        assertEquals("DIRECT_VIDEO", document.path("inputMode").asText());
        assertEquals("婚礼主持作品", document.path("content").path("summary").asText());
        assertFalse(json.getValue().contains("signature"));
        assertEquals(1, run.requests()); assertEquals(1, run.sentRequests());
        assertEquals(230130, run.knownTokens()); assertEquals(19, run.knownCostFen());
        assertEquals(19, run.reservedCostFen()); assertFalse(run.usageUnknown()); verify(lock).close();
    }

    /** 已知费用结算后释放本件多余预留，不能在总费用远低于上限时固定停在第五件。 */
    @Test void measuredResponsesAllowSixthSummaryWithinHardBudget() {
        for (int request = 0; request < 6; request++) {
            assertEquals(SAVED, service.summarize(1, run, future()), "第" + (request + 1) + "次请求");
        }
        assertEquals(6, run.sentRequests());
        assertEquals(114, run.knownCostFen());
        assertEquals(114, run.reservedCostFen());
        assertFalse(run.usageUnknown());
    }

    /** 收费请求为最终 HEAD 和 CAS 预留时间，不能消耗整个单件期限。 */
    @Test void requestDeadlineLeavesFinalHeadAndSaveMargin() {
        Instant overall = Instant.now().plusSeconds(100);
        assertEquals(SAVED, service.summarize(1, run, overall));
        ArgumentCaptor<Instant> deadline = ArgumentCaptor.forClass(Instant.class);
        verify(client).summarize(eq(1L), anyString(), deadline.capture());
        assertEquals(overall.minusSeconds(35), deadline.getValue());
    }

    /** 同源有效摘要无TTL，不依赖当前帧或转写。 */
    @Test void existingSummarySkipsWithoutLockOrFee() {
        when(repository.find(1)).thenReturn(snapshot(9, 1, document()));
        assertEquals(ALREADY_COMPLETE, service.summarize(1, run, future()));
        verifyNoInteractions(client, locks); assertEquals(0, run.requests());
    }

    /** 损坏或陈旧摘要作为缺失重新生成，不能当作有效结果跳过。 */
    @Test void corruptSummaryGetsReplaced() {
        when(repository.find(1)).thenReturn(snapshot(2, 1, "{broken"));
        assertEquals(SAVED, service.summarize(1, run, future())); verify(client).summarize(eq(1L), anyString(), any());
    }

    /** 默认文档百兆按十进制，实测样本需显式配置扩展，仍不能超过硬界。 */
    @Test void rejectsOversizeBeforeReservationButAllowsExplicitValidatedRange() {
        when(storage.head(eq(KEY), any())).thenReturn(metadata(102_381_193, "etag"));
        assertEquals(NOT_SUPPORTED, service.summarize(1, run, future()));
        assertEquals(0, run.requests()); verifyNoInteractions(client, locks);
        properties.setMaxVideoBytes(104_857_600L);
        assertEquals(SAVED, service.summarize(1, run, future()));
    }

    /** 缺失、非法或过长作品时长拒绝，不能静默截短。 */
    @Test void rejectsMissingInvalidOrExcessDuration() {
        for (Integer duration : new Integer[]{null, 0, -1, 600001}) {
            when(repository.find(1)).thenReturn(new Snapshot(1, 1, KEY, "sha", 1, 2, duration, null, "OWNER"));
            assertEquals(NOT_SUPPORTED, service.summarize(1, run, future()));
        }
        verifyNoInteractions(client); assertEquals(0, run.requests());
    }

    /** 重建预留无锁无调用，后续版本推进后只消费一次凭证。 */
    @Test void rebuildReservationHoldsNoLockAndIsConsumedOnlyOnce() {
        when(repository.find(1)).thenReturn(snapshot(2, 1, document()));
        try (var prepared = service.prepare(1, true, run, future())) {
            assertTrue(prepared.ready()); verifyNoInteractions(locks, client);
            assertEquals(1, run.requests());
            Snapshot afterClearAndAudio = snapshot(5, 1, null);
            when(repository.find(1)).thenReturn(afterClearAndAudio);
            assertEquals(SAVED, service.complete(prepared, future()));
            assertEquals(STALE, service.complete(prepared, future()));
            verify(repository).save(eq(afterClearAndAudio), anyString());
        }
        assertEquals(1, run.requests()); verify(client, times(1)).summarize(eq(1L), anyString(), any());
    }

    /** 清空前预算拒绝和关闭凭证都不能收费。 */
    @Test void budgetAndClosedPreparationPreventPayment() {
        try (var refused = service.prepare(1, true, new VideoSummaryService.RunBudget(100, reservationFen() - 1), future())) {
            assertEquals(BUDGET_EXHAUSTED, refused.outcome());
        }
        var prepared = service.prepare(1, true, run, future()); prepared.close();
        assertEquals(STALE, service.complete(prepared, future())); verifyNoInteractions(client, locks);
    }

    /** 过期审核和历史原源不能触发新作品的收费。 */
    @Test void staleAuditAndHistorySourcesFailBeforeHead() {
        assertEquals(STALE, service.summarize(1, 0, KEY, "sha", run, future()));
        assertEquals(STALE, service.summarize(1, KEY, "other", run, future(), () -> { }));
        verifyNoInteractions(storage, client, locks);
    }

    /** 预检到实际执行之间的同键内容变化拒绝，清空预算也不允许绕过源身份。 */
    @Test void headChangeAndAuditRoundChangePreventPaidCall() {
        try (var prepared = service.prepare(1, true, run, future())) {
            when(storage.head(eq(KEY), any())).thenReturn(metadata(100, "changed"));
            assertEquals(STALE, service.complete(prepared, future()));
        }
        when(storage.head(eq(KEY), any())).thenReturn(metadata(100, "etag"));
        try (var prepared = service.prepare(1, true, run, future())) {
            when(repository.find(1)).thenReturn(snapshot(3, 2, null));
            assertEquals(STALE, service.complete(prepared, future()));
        }
        verifyNoInteractions(client);
    }

    /** 付费后源变更或CAS失败只丢弃结果，不能覆盖已有新状态。 */
    @Test void paidLateResultCannotOverwriteChangedCosSource() {
        when(client.summarize(eq(1L), anyString(), any())).thenAnswer(invocation -> {
            when(storage.head(eq(KEY), any())).thenReturn(metadata(101, "etag"));
            return new PortfolioAiModelClient.Completion(CONTENT, 10L, 20L);
        });
        assertEquals(STALE, service.summarize(1, run, future()));
        verify(repository, never()).save(any(), anyString()); assertEquals(1, run.sentRequests());
    }

    /** 账户失败熔断本轮，未知响应保留费用预留。 */
    @Test void accountFailureStopsRunAndPreservesUnknownCost() {
        when(client.summarize(eq(1L), anyString(), any()))
                .thenThrow(new PortfolioAiCallException("AI_UNAVAILABLE", "账户不可用", false));
        assertEquals(FAILED, service.summarize(1, run, future()));
        assertEquals(FAILED, service.summarize(1, run, future()));
        assertTrue(run.stopped()); assertTrue(run.usageUnknown()); assertEquals(reservationFen(), run.reservedCostFen());
        verify(client, times(1)).summarize(eq(1L), anyString(), any());
    }

    /** 限流是本件失败且无隐藏重试，不熔断其它作品。 */
    @Test void transientFailureIsSingleRequestWithUnknownUsage() {
        when(client.summarize(eq(1L), anyString(), any()))
                .thenThrow(new PortfolioAiCallException("REMOTE_BUSY", "暂不可用", true));
        assertEquals(FAILED, service.summarize(1, run, future()));
        assertFalse(run.stopped()); assertEquals(1, run.unknownUsageCount());
        verify(client, times(1)).summarize(eq(1L), anyString(), any());
    }

    /** 不完整JSON和超长响应不能保存，合法usage仍记录，不把已付费失败记零。 */
    @Test void invalidContentFailsWithMeasuredUsage() {
        when(client.summarize(eq(1L), anyString(), any()))
                .thenReturn(new PortfolioAiModelClient.Completion("{\"summary\":\"缺字段\"}", 20L, 10L));
        assertEquals(FAILED, service.summarize(1, run, future()));
        verify(repository, never()).save(any(), anyString()); assertFalse(run.usageUnknown()); assertEquals(30, run.knownTokens());
        assertEquals(1, run.reservedCostFen());
    }

    /** 跨实例锁竞争不能收费；未开启或调用方取消不能做远端请求。 */
    @Test void busyDisabledAndCallerCancellationAreIsolated() {
        when(locks.tryAcquire(1)).thenReturn(Optional.empty());
        assertEquals(BUSY, service.summarize(1, run, future())); verifyNoInteractions(client);
        clearInvocations(repository, storage, locks);
        properties.setEnabled(false); assertEquals(DISABLED, service.summarize(1, run, future()));
        properties.setEnabled(true);
        assertEquals(FAILED, service.summarize(1, run, future(), () -> { throw new IllegalStateException(); }));
        verifyNoInteractions(repository, storage, locks, client);
    }

    /** 缺失用量不退款，超出Long范围不绕过统计。 */
    @Test void unknownAndOverflowUsageRemainConservative() {
        var first = run.reserve(86); run.sent(first); run.recordUsage(first, new PortfolioAiModelClient.Completion(CONTENT, null, null));
        assertEquals(1, run.unknownUsageCount()); assertEquals(86, run.reservedCostFen());
        var second = run.reserve(86); run.sent(second); run.recordUsage(second, new PortfolioAiModelClient.Completion(CONTENT, Long.MAX_VALUE, 1L));
        assertTrue(run.stopped()); assertEquals(2, run.unknownUsageCount());
        assertEquals(172, run.reservedCostFen());
    }

    /** 截断响应虽无法保存摘要，供应商已返回的可信费用仍结算且不自动重试。 */
    @Test void truncatedResponseSettlesKnownUsageWithoutRetry() {
        var completion = new PortfolioAiModelClient.Completion(null, 29963L, 4096L);
        when(client.summarize(eq(1L), anyString(), any()))
                .thenThrow(new PortfolioAiCallException("OUTPUT_TRUNCATED", "输出已截断", false, completion));
        assertEquals(FAILED, service.summarize(1, run, future()));
        assertEquals(4, run.reservedCostFen()); assertEquals(4, run.knownCostFen());
        assertFalse(run.usageUnknown()); assertFalse(run.stopped());
        verify(repository, never()).save(any(), anyString());
        verify(client, times(1)).summarize(eq(1L), anyString(), any());
    }

    /** 前序阶段失败或凭证关闭不退款，其它作品只释放自身已确认的费用差额。 */
    @Test void onlyMeasuredPreparationReleasesItsReservation() {
        long reserved = reservationFen();
        run = new VideoSummaryService.RunBudget(100, reserved * 2);
        try (var unused = service.prepare(1, true, run, future());
             var measured = service.prepare(1, true, run, future())) {
            assertTrue(unused.ready()); assertTrue(measured.ready());
            assertEquals(SAVED, service.complete(measured, future()));
            assertEquals(reserved + 19, run.reservedCostFen());
            assertEquals(BUDGET_EXHAUSTED, service.prepare(1, true, run, future()).outcome());
        }
        assertEquals(reserved + 19, run.reservedCostFen());
        assertEquals(2, run.requests()); assertEquals(1, run.sentRequests());
    }

    /** 费用结算不放宽原有请求次数硬限制。 */
    @Test void measuredSettlementKeepsRequestCountLimit() {
        run = new VideoSummaryService.RunBudget(2, 500);
        assertEquals(SAVED, service.summarize(1, run, future()));
        assertEquals(SAVED, service.summarize(1, run, future()));
        assertEquals(BUDGET_EXHAUSTED, service.summarize(1, run, future()));
        assertEquals(38, run.reservedCostFen()); assertEquals(2, run.sentRequests());
    }

    /** 单件超出自身预留必须熔断，不能借其它尚未使用的预留隐藏超额。 */
    @Test void oneResponseExceedingOwnReservationStopsAlreadyPreparedRequests() {
        long reserved = reservationFen();
        when(client.summarize(eq(1L), anyString(), any()))
                .thenReturn(new PortfolioAiModelClient.Completion(CONTENT, 2_000_000L, 0L));
        try (var first = service.prepare(1, true, run, future());
             var second = service.prepare(1, true, run, future())) {
            assertEquals(SAVED, service.complete(first, future()));
            assertTrue(run.stopped()); assertEquals(160 + reserved, run.reservedCostFen());
            assertEquals(FAILED, service.complete(second, future()));
        }
        assertEquals(1, run.sentRequests());
        verify(client, times(1)).summarize(eq(1L), anyString(), any());
    }

    private Snapshot snapshot(int version, int round, String summary) { return new Snapshot(1, 1, KEY, "sha", round, version, 84613, summary, "OWNER"); }
    private ObjectMetadata metadata(long bytes, String etag) { var metadata = new ObjectMetadata(); metadata.setContentLength(bytes); metadata.setHeader("ETag", etag); return metadata; }
    private String source() { return PortfolioAiStoredVideoSummary.sourceVersion(KEY, "etag", 100, "sha"); }
    private String document() { return PortfolioAiStoredVideoSummary.create(source(), "b".repeat(64), "old-model", "old-prompt", Instant.EPOCH, PortfolioAiStoredVideoSummary.validateContent(CONTENT)).toString(); }
    private Instant future() { return Instant.now().plusSeconds(540); }
    /** 当前规格对应的独立请求预留，配置调整时仍验证相同边界。 */
    private long reservationFen() { return TokenHubTokenPricing.costCents(VideoSummaryProperties.CONTEXT_INPUT_UPPER_BOUND, properties.getMaxOutputTokens()); }
}
