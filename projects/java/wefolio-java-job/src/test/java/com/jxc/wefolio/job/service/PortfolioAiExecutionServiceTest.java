package com.jxc.wefolio.job.service;

import com.fasterxml.jackson.databind.node.ObjectNode;
import com.jxc.wefolio.job.config.PortfolioAiProperties;
import com.jxc.wefolio.job.entity.AiGenerationTaskEntity;
import com.jxc.wefolio.job.repo.AiGenerationTaskRepository;
import com.jxc.wefolio.service.portfolioai.StrictAiJson;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Clock;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.concurrent.Executors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** 统一预算、关闭开关、澄清、迟到回写与脱敏错误的执行测试。 */
class PortfolioAiExecutionServiceTest {
    /** 任务条件更新边界。 */ private final AiGenerationTaskRepository repository = mock(AiGenerationTaskRepository.class);
    /** 单次模型调用边界。 */ private final PortfolioAiModelClient client = mock(PortfolioAiModelClient.class);
    /** 开关默认关闭。 */ private final PortfolioAiProperties properties = new PortfolioAiProperties();
    /** 测试冻结时点。 */ private final LocalDateTime now = LocalDateTime.now();
    /** 被测服务。 */ private final PortfolioAiExecutionService service = new PortfolioAiExecutionService(repository, client, properties,
            new JobExecutionLifecycle(), Clock.fixed(now.atZone(ZoneId.systemDefault()).toInstant(), ZoneId.systemDefault()), Executors.newSingleThreadExecutor());

    @AfterEach void stop() { service.close(); }

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
        AiGenerationTaskEntity interrupted = task(1); interrupted.setStartedAt(now.minusSeconds(76));
        AiGenerationTaskEntity exhausted = task(3); exhausted.setStartedAt(now.minusSeconds(76));
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
