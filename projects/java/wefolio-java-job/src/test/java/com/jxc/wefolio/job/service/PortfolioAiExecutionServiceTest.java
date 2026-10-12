package com.jxc.wefolio.job.service;

import com.fasterxml.jackson.databind.node.ObjectNode;
import com.jxc.wefolio.job.config.PortfolioAiProperties;
import com.jxc.wefolio.job.entity.AiGenerationTaskEntity;
import com.jxc.wefolio.job.repo.AiGenerationTaskRepository;
import com.jxc.wefolio.job.repo.PortfolioAiMediaRepository;
import com.jxc.wefolio.service.portfolioai.StrictAiJson;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.concurrent.Executors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** 统一预算、关闭开关、澄清、迟到回写与脱敏错误的执行测试。 */
class PortfolioAiExecutionServiceTest {
    /** 任务条件更新边界。 */ private final AiGenerationTaskRepository repository = mock(AiGenerationTaskRepository.class);
    /** 单次模型调用边界。 */ private final PortfolioAiModelClient client = mock(PortfolioAiModelClient.class);
    /** 媒体执行替身，文字测试也保持依赖完整。 */ private final PortfolioAiMediaExecutionService mediaExecution = mock(PortfolioAiMediaExecutionService.class);
    /** 摘要清理替身，不访问数据库。 */ private final PortfolioAiMediaRepository mediaRepository = mock(PortfolioAiMediaRepository.class);
    /** 开关默认关闭。 */ private final PortfolioAiProperties properties = new PortfolioAiProperties();
    /** 测试冻结时点使用上海口径，不受 Maven JVM 默认时区影响。 */ private final LocalDateTime now = LocalDateTime.now(ZoneId.of("Asia/Shanghai"));
    /** 被测服务。 */ private final PortfolioAiExecutionService service = new PortfolioAiExecutionService(repository, client, properties,
            new JobExecutionLifecycle(), Clock.fixed(now.atZone(ZoneId.of("Asia/Shanghai")).toInstant(), ZoneId.of("Asia/Shanghai")), Executors.newSingleThreadExecutor(), mediaExecution, mediaRepository);

    @AfterEach void stop() { service.close(); }

    /** UTC 时钟的同一瞬间仍按上海午夜计算恢复截止与清理日期。 */
    @Test void utcClockKeepsShanghaiMidnightDeadlineAndCleanupDay() {
        Instant instant = Instant.parse("2026-10-09T16:00:00Z");
        LocalDateTime midnight = LocalDateTime.of(2026, 10, 10, 0, 0);
        var utcService = new PortfolioAiExecutionService(repository, client, properties,
                new JobExecutionLifecycle(), Clock.fixed(instant, ZoneOffset.UTC), Executors.newSingleThreadExecutor(), mediaExecution, mediaRepository);
        try {
            AiGenerationTaskEntity expired = task(1); expired.setCreatedAt(midnight.minusSeconds(180));
            when(repository.findRecoverable(any(), anyInt(), anyInt(), anyInt())).thenReturn(List.of(expired));
            utcService.maintain(); utcService.cleanup();
            verify(repository).fail(eq(expired), eq("TIMEOUT"), anyString(), eq(midnight));
            verify(repository).findRecoverable(eq(midnight), eq(180), eq(90), eq(100));
            verify(mediaRepository).cleanup(eq(midnight), eq(100));
            verify(repository).deleteExpired(eq(midnight.minusDays(30)), eq(100));
            verifyNoInteractions(client, mediaExecution);
        } finally { utcService.close(); }
    }

    /** 缺失媒体依赖在构造时失败，不能等到付费分支才抛 NPE。 */
    @Test void incompleteMediaDependenciesFailBeforeTaskExecution() {
        var executor = Executors.newSingleThreadExecutor();
        try {
            Clock clock = Clock.systemUTC();
            assertThatThrownBy(() -> new PortfolioAiExecutionService(repository, client, properties,
                    new JobExecutionLifecycle(), clock, executor, null, mediaRepository)).hasMessage("媒体执行器不能为空");
            assertThatThrownBy(() -> new PortfolioAiExecutionService(repository, client, properties,
                    new JobExecutionLifecycle(), clock, executor, mediaExecution, null)).hasMessage("媒体仓储不能为空");
        } finally { executor.shutdownNow(); }
        verifyNoInteractions(repository, client, mediaExecution, mediaRepository);
    }

    /** 媒体摘要过后中断，只在总期限准确边界写 TIMEOUT，不增加重试和预算调用。 */
    @Test void mediaTaskInterruptedAfterSummaryUsesTimeoutAtExactAbsoluteDeadline() {
        AiGenerationTaskEntity running = task(1); running.setCreatedAt(now.minusSeconds(180));
        running.setStartedAt(now.minusSeconds(100));
        ObjectNode request = request(running); request.withObject("facts").putObject("mediaPlan");
        running.setRequestJson(request.toString());
        when(repository.findRecoverable(any(), anyInt(), anyInt(), anyInt())).thenReturn(List.of(running));
        service.maintain();
        verify(repository).fail(eq(running), eq("TIMEOUT"), anyString(), eq(now));
        verify(repository, never()).recover(any(), any(), anyInt());
        verifyNoInteractions(client, mediaExecution, mediaRepository);
    }

    /** 开启媒体后到期摘要清理必须调用已注入仓储，不能因测试构造缺依赖而静默跳过。 */
    @Test void enabledMediaCleanupUsesTheInjectedRepository() {
        properties.setMediaEnabled(true);
        service.cleanup();
        verify(mediaRepository).cleanup(eq(now), eq(properties.getBatchSize()));
    }

    /** 关闭新任务及媒体开关后，已保存摘要仍须按原 TTL 清理。 */
    @Test void disabledMediaStillCleansExpiredSummaries() {
        properties.setEnabled(false); properties.setMediaEnabled(false);
        service.cleanup();
        verify(mediaRepository).cleanup(eq(now), eq(properties.getBatchSize()));
        verifyNoInteractions(client, mediaExecution);
    }

    /** 媒体租约陈旧直接失败、不再重发付费调用；总期限仍使用原 TIMEOUT。 */
    @Test void staleMediaLeaseFailsWithoutRequeueOrAnotherPaidCall() {
        AiGenerationTaskEntity running = task(1); running.setCreatedAt(now.minusSeconds(100)); running.setStartedAt(now.minusSeconds(95));
        running.setRequestJson(running.getRequestJson().replace("\"facts\":{", "\"facts\":{\"mediaPlan\":{\"version\":\"portfolio-media-v1\",\"entries\":[]},"));
        AiGenerationTaskEntity expired = task(1); expired.setCreatedAt(now.minusSeconds(181)); expired.setRequestJson(running.getRequestJson());
        when(repository.findRecoverable(any(), anyInt(), anyInt(), anyInt())).thenReturn(List.of(running, expired));
        service.maintain();
        verify(repository).failInterruptedMedia(eq(running), eq(now), eq(90), eq(180));
        verify(repository, never()).recover(eq(running), any(), anyInt());
        verify(repository).fail(eq(expired), eq("TIMEOUT"), anyString(), eq(now));
        verifyNoInteractions(client, mediaExecution, mediaRepository);
    }

    /** 领取后失去版本或租约的媒体不能进入媒体阶段，更不能发出模型请求。 */
    @Test void lostMediaLeaseStopsBeforeCallingAnyMediaBoundary() {
        AiGenerationTaskEntity task = mediaTask();
        service.executeClaimed(task, request(task));
        verify(repository).fail(eq(task), eq("EXECUTION_INTERRUPTED"), anyString(), eq(now));
        verify(repository, never()).retry(any(), anyString(), anyString(), any(), anyInt());
        verifyNoInteractions(client, mediaExecution, mediaRepository);
    }

    /** 媒体调用晚到结果在同步检查失权后不能成功或回到队列。 */
    @Test void lostLeaseAfterMediaReturnCannotPublishLateResult() {
        AiGenerationTaskEntity task = mediaTask();
        when(repository.renewMediaLease(eq(task), eq(90), eq(180))).thenReturn(true, false);
        when(mediaExecution.generate(eq(task), any(), any())).thenReturn("late result");
        service.executeClaimed(task, request(task));
        verify(repository).fail(eq(task), eq("EXECUTION_INTERRUPTED"), anyString(), eq(now));
        verify(repository, never()).succeed(any(), anyString(), any(), anyInt());
        verify(repository, never()).retry(any(), anyString(), anyString(), any(), anyInt());
        verifyNoInteractions(client);
    }

    /** 已失权后晚到的网络异常不能沿普通可重试错误重发模型。 */
    @Test void retryableMediaErrorAfterLeaseLossEndsWithoutRetry() {
        AiGenerationTaskEntity task = mediaTask();
        when(repository.renewMediaLease(eq(task), eq(90), eq(180))).thenReturn(true, false);
        when(mediaExecution.generate(eq(task), any(), any())).thenThrow(new PortfolioAiCallException("REMOTE_ERROR", "网络失败", true));
        service.executeClaimed(task, request(task));
        verify(repository).fail(eq(task), eq("EXECUTION_INTERRUPTED"), anyString(), eq(now));
        verify(repository, never()).retry(any(), anyString(), anyString(), any(), anyInt());
    }

    /** SDK 中断异常即使遗漏线程中断标志，媒体也不能回队列重复付费。 */
    @Test void interruptedMediaCallNeverRetriesEvenWithFreshLeaseAndNoThreadFlag() {
        AiGenerationTaskEntity task = mediaTask();
        when(repository.renewMediaLease(eq(task), eq(90), eq(180))).thenReturn(true);
        when(mediaExecution.generate(eq(task), any(), any())).thenThrow(new PortfolioAiCallException("EXECUTION_INTERRUPTED", "模型调用中断", true));
        service.executeClaimed(task, request(task));
        verify(repository).fail(eq(task), eq("EXECUTION_INTERRUPTED"), anyString(), eq(now));
        verify(repository, never()).retryMedia(any(), anyString(), anyString(), any(), anyInt(), anyInt());
        verify(repository, never()).retry(any(), anyString(), anyString(), any(), anyInt());
    }

    /** 停机发生在 HTTP 中断时，独立心跳停止立即使凭证失效，不能因新鲜租约自动重试。 */
    @Test void shutdownDuringMediaCallCannotRequeueTheInterruptedPaidStage() {
        AiGenerationTaskEntity task = mediaTask();
        when(repository.renewMediaLease(eq(task), eq(90), eq(180))).thenReturn(true);
        when(mediaExecution.generate(eq(task), any(), any())).thenAnswer(invocation -> {
            service.close(); throw new PortfolioAiCallException("EXECUTION_INTERRUPTED", "模型调用中断", true);
        });
        service.executeClaimed(task, request(task));
        verify(repository).fail(eq(task), eq("EXECUTION_INTERRUPTED"), anyString(), eq(now));
        verify(repository, never()).retryMedia(any(), anyString(), anyString(), any(), anyInt(), anyInt());
    }

    /** 在原文字合同上增加空媒体计划，用于执行入口所有权边界测试。 */
    private AiGenerationTaskEntity mediaTask() {
        AiGenerationTaskEntity task = task(1);
        ObjectNode request = request(task); request.withObject("facts").putObject("mediaPlan");
        task.setRequestJson(request.toString()); return task;
    }

    /** 旧文字facts中的同名字段不属于媒体计划，仍按90秒恢复。 */
    @Test void legacyTextContainingMediaPlanWordRetainsOriginalRecovery() {
        AiGenerationTaskEntity running = task(1); running.setStartedAt(now.minusSeconds(95));
        running.setRequestJson(task(1).getRequestJson().replace("\"components\":[]", "\"components\":[],\"mediaPlan\":\"普通文字\""));
        when(repository.findRecoverable(any(), anyInt(), anyInt(), anyInt())).thenReturn(List.of(running));
        service.maintain(); verify(repository).recover(eq(running), any(), eq(180));
    }

    /** 简单合法无改动任务请求。 */
    private AiGenerationTaskEntity task(int attempts) {
        AiGenerationTaskEntity task = new AiGenerationTaskEntity();
        task.setId(1L); task.setRequestedBy(2L); task.setStatus("RUNNING"); task.setVersion(1); task.setAttemptCount(attempts);
        task.setCreatedAt(now.minusSeconds(10)); task.setStartedAt(now);
        task.setRequestJson("""
                {"contractVersion":"ai-portfolio-v1","promptVersion":"portfolio-ai-2026-10-10.2",
                 "input":{"ownerType":"USER","mode":"MODIFY_PAGE","targetMenuKey":"__root__","prompt":"秘密描述"},
                 "facts":{"safePrompt":"修改文字","baseConfig":{"components":[]},"sources":[],"bindings":[]},
                 "modelConfig":{"provider":"TOKENHUB","model":"glm-5.3-flash","baseUrl":"https://tokenhub.tencentmaas.com/v1"}}
                """); return task;
    }

    /** 读取请求通过生产严格入口。 */
    private ObjectNode request(AiGenerationTaskEntity task) {
        return StrictAiJson.parseObject(task.getRequestJson(), 10000, 16);
    }

    @Test
    void disabledSwitchSkipsClaimingButContinuesRecoveryAndCleanup() {
        AiGenerationTaskEntity expired = task(1); expired.setCreatedAt(now.minusSeconds(181));
        AiGenerationTaskEntity interrupted = task(1); interrupted.setStartedAt(now.minusSeconds(91));
        AiGenerationTaskEntity exhausted = task(3); exhausted.setStartedAt(now.minusSeconds(91));
        when(repository.findRecoverable(any(), anyInt(), anyInt(), anyInt())).thenReturn(List.of(expired, interrupted, exhausted));
        service.maintain(); service.dispatch(); service.cleanup();
        verify(repository).fail(eq(expired), eq("TIMEOUT"), anyString(), eq(now));
        verify(repository).recover(eq(interrupted), eq(now), eq(180));
        verify(repository).fail(eq(exhausted), eq("ATTEMPTS_EXHAUSTED"), anyString(), eq(now));
        verify(repository).deleteExpired(eq(now.minusDays(30)), eq(100));
        verify(repository, never()).findPending(any(), anyInt()); verifyNoInteractions(client);
    }

    @Test
    void malformedOutputAndNetworkErrorsShareTheSameThreeAttemptBudget() {
        AiGenerationTaskEntity first = task(1);
        when(client.generate(eq(first), any(), any())).thenReturn("{\"notes\":\"非法原文 secret\"}");
        service.executeClaimed(first, request(first));
        ArgumentCaptor<String> summary = ArgumentCaptor.forClass(String.class);
        verify(repository).retry(eq(first), eq("INVALID_OUTPUT"), summary.capture(), eq(now), eq(180));
        assertThat(summary.getValue()).doesNotContain("secret", "非法原文");
        AiGenerationTaskEntity third = task(3);
        when(client.generate(eq(third), any(), any())).thenThrow(new PortfolioAiCallException("REMOTE_ERROR", "网络失败", true));
        service.executeClaimed(third, request(third));
        verify(repository).fail(eq(third), eq("REMOTE_ERROR"), eq("网络失败"), eq(now));
    }

    @Test
    void validClarificationCompletesSuccessfullyWithoutRetryAndWithoutSavingPortfolio() {
        AiGenerationTaskEntity task = task(1);
        when(client.generate(eq(task), any(), any())).thenReturn("""
                {"schemaVersion":"ai-portfolio-v1","promptVersion":"portfolio-ai-2026-10-10.2","status":"NEEDS_CLARIFICATION",
                 "mode":"MODIFY_PAGE","page":null,"operations":[],"questions":[{"id":"q1","prompt":"请选择修改范围","options":[]}],"notes":[]}
                """);
        when(repository.succeed(any(), anyString(), any(), anyInt())).thenReturn(true);
        service.executeClaimed(task, request(task));
        ArgumentCaptor<String> result = ArgumentCaptor.forClass(String.class);
        verify(repository).succeed(eq(task), result.capture(), eq(now), eq(180));
        assertThat(StrictAiJson.parseObject(result.getValue(), 10000, 16).path("kind").asText()).isEqualTo("CLARIFICATION");
        verify(repository, never()).retry(any(), anyString(), anyString(), any(), anyInt());
    }

    @Test
    void unsupportedContractNeverCallsModelAndIsNotRetried() {
        properties.setEnabled(true); AiGenerationTaskEntity task = task(0); task.setStatus("PENDING");
        task.setRequestJson(task.getRequestJson().replace("ai-portfolio-v1", "ai-portfolio-v99"));
        when(repository.findPending(any(), anyInt())).thenReturn(List.of(task));
        service.dispatch();
        verify(repository).fail(eq(task), eq("CONTRACT_UNAVAILABLE"), anyString(), eq(now));
        verify(repository, never()).claim(any(), any(), anyInt(), anyInt()); verifyNoInteractions(client);
    }

    /** 模型切换不重新解释旧供应商任务，领取前写安全终态。 */
    @Test
    void legacyFrozenProviderEndsBeforeClaimWithoutModelCallOrRetry() {
        properties.setEnabled(true); AiGenerationTaskEntity task = task(0); task.setStatus("PENDING");
        ObjectNode request = request(task);
        request.withObject("modelConfig").put("provider", "DEEPSEEK").put("model", "deepseek-flash")
                .put("baseUrl", "https://api.deepseek.com");
        task.setRequestJson(request.toString());
        when(repository.findPending(any(), anyInt())).thenReturn(List.of(task));
        service.dispatch();
        verify(repository).fail(eq(task), eq("CONTRACT_UNAVAILABLE"), anyString(), eq(now));
        verify(repository, never()).claim(any(), any(), anyInt(), anyInt());
        verify(repository, never()).retry(any(), anyString(), anyString(), any(), anyInt());
        verifyNoInteractions(client);
    }

    /** 已领取或恢复的任务也要复核完整冻结模型，不补当前端点后执行。 */
    @Test
    void claimedRequestWithoutFrozenEndpointCannotCallModelOrRetry() {
        AiGenerationTaskEntity task = task(1); ObjectNode request = request(task);
        request.withObject("modelConfig").remove("baseUrl");
        service.executeClaimed(task, request);
        verify(repository).fail(eq(task), eq("CONTRACT_UNAVAILABLE"), anyString(), eq(now));
        verify(repository, never()).retry(any(), anyString(), anyString(), any(), anyInt());
        verifyNoInteractions(client);
    }

    /** 已移除的两种模式在领取前结束，不启动模型调用或重试。 */
    @Test
    void removedModesNeverClaimTasksOrCallModel() {
        properties.setEnabled(true);
        AiGenerationTaskEntity componentTask = task(0); componentTask.setStatus("PENDING");
        ObjectNode componentRequest = request(componentTask);
        componentRequest.withObject("input").put("mode", "MODIFY_COMPONENT");
        componentTask.setRequestJson(componentRequest.toString());
        AiGenerationTaskEntity regenerateTask = task(0); regenerateTask.setStatus("PENDING");
        ObjectNode regenerateRequest = request(regenerateTask);
        regenerateRequest.withObject("input").put("mode", "REGENERATE_PAGE");
        regenerateTask.setRequestJson(regenerateRequest.toString());
        when(repository.findPending(any(), anyInt())).thenReturn(List.of(componentTask, regenerateTask));
        service.dispatch();
        verify(repository).fail(eq(componentTask), eq("INVALID_OUTPUT"), eq("任务模式不支持"), eq(now));
        verify(repository).fail(eq(regenerateTask), eq("INVALID_OUTPUT"), eq("任务模式不支持"), eq(now));
        verify(repository, never()).claim(any(), any(), anyInt(), anyInt());
        verify(repository, never()).retry(any(), anyString(), anyString(), any(), anyInt());
        verifyNoInteractions(client);
    }

    @Test
    void expiredTaskNeverCallsModelAndUsesFailedTimeout() {
        AiGenerationTaskEntity task = task(1); task.setCreatedAt(now.minusSeconds(180));
        service.executeClaimed(task, request(task));
        verify(repository).fail(eq(task), eq("TIMEOUT"), anyString(), eq(now)); verifyNoInteractions(client);
    }
}
