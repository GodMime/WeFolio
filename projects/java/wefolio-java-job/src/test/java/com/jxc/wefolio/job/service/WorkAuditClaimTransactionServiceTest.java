package com.jxc.wefolio.job.service;

import com.jxc.wefolio.job.dict.AuditResultDict;
import com.jxc.wefolio.job.dict.WorkAuditStatusDict;
import com.jxc.wefolio.job.dict.WorkAuditReasonCodeDict;
import com.jxc.wefolio.job.entity.WorkAuditTaskEntity;
import com.jxc.wefolio.job.repo.WorkAuditTaskRepository;
import com.jxc.wefolio.job.repo.WorkAuditWorkRepository;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import org.springframework.transaction.annotation.Transactional;

import java.lang.reflect.Method;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 作品审核 claim 短事务服务测试。
 */
class WorkAuditClaimTransactionServiceTest {

    @Test
    void claimAndCreateSubmittingTaskShouldUseSpringTransaction() throws NoSuchMethodException {
        Method method = WorkAuditClaimTransactionService.class.getDeclaredMethod(
                "claimAndCreateSubmittingTask", Long.class, WorkAuditTaskEntity.class);

        Transactional transactional = method.getAnnotation(Transactional.class);

        assertThat(transactional).isNotNull();
        assertThat(transactional.rollbackFor()).contains(Exception.class);
    }

    @Test
    void claimAndCreatePendingAnimationTaskShouldUseSpringTransaction() throws NoSuchMethodException {
        assertTransactional("claimAndCreatePendingAnimationTask",
                Long.class, WorkAuditTaskEntity.class, List.class);
    }

    @Test
    void resultUpdateMethodsShouldUseSpringTransaction() throws NoSuchMethodException {
        assertTransactional("markTaskFailedAndUpdateWorkFailed",
                Long.class, Long.class, String.class, String.class, String.class);
        assertTransactional("markTaskSuccessAndUpdateWork",
                Long.class, Long.class, AuditResultDict.class, String.class, Integer.class, String.class,
                Integer.class, List.class, String.class, WorkAuditStatusDict.class, String.class);
        assertTransactional("markVideoSubmittedAndUpdateWorkAuditing",
                Long.class, Long.class, String.class, String.class);
        assertTransactional("markVideoRunningAndKeepWorkAuditing",
                Long.class, Long.class, String.class, String.class);
        assertTransactional("markQueryFailureForNextRunAndKeepWorkAuditing",
                Long.class, Long.class, String.class, String.class);
    }

    @Test
    void claimAndCreateSubmittingTaskShouldInsertOnlyAfterClaimSucceeded() {
        WorkAuditWorkRepository workRepository = mock(WorkAuditWorkRepository.class);
        WorkAuditTaskRepository taskRepository = mock(WorkAuditTaskRepository.class);
        WorkAuditClaimTransactionService service =
                service(workRepository, taskRepository);
        WorkAuditTaskEntity task = new WorkAuditTaskEntity();
        when(workRepository.claimPendingWork(11L)).thenReturn(true);
        when(taskRepository.insertTask(task)).thenReturn(task);

        WorkAuditTaskEntity result = service.claimAndCreateSubmittingTask(11L, task);

        assertThat(result).isSameAs(task);
        InOrder inOrder = inOrder(workRepository, taskRepository);
        inOrder.verify(workRepository).claimPendingWork(11L);
        inOrder.verify(taskRepository).insertTask(task);
    }

    @Test
    void claimAndCreateSubmittingTaskShouldSkipInsertWhenClaimFailed() {
        WorkAuditWorkRepository workRepository = mock(WorkAuditWorkRepository.class);
        WorkAuditTaskRepository taskRepository = mock(WorkAuditTaskRepository.class);
        WorkAuditClaimTransactionService service =
                service(workRepository, taskRepository);
        WorkAuditTaskEntity task = new WorkAuditTaskEntity();
        when(workRepository.claimPendingWork(11L)).thenReturn(false);

        WorkAuditTaskEntity result = service.claimAndCreateSubmittingTask(11L, task);

        assertThat(result).isNull();
        verify(workRepository).claimPendingWork(11L);
        verify(taskRepository, never()).insertTask(task);
    }

    @Test
    void claimAndCreatePendingAnimationTaskShouldPersistStableIntegerArray() {
        WorkAuditWorkRepository workRepository = mock(WorkAuditWorkRepository.class);
        WorkAuditTaskRepository taskRepository = mock(WorkAuditTaskRepository.class);
        WorkAuditClaimTransactionService service = service(workRepository, taskRepository);
        WorkAuditTaskEntity task = new WorkAuditTaskEntity();
        when(workRepository.claimPendingWork(11L)).thenReturn(true);
        when(taskRepository.insertTask(task)).thenReturn(task);

        WorkAuditTaskEntity result = service.claimAndCreatePendingAnimationTask(
                11L, task, List.of(5, 23));

        assertThat(result).isSameAs(task);
        assertThat(task.getSampledFrameNumbers()).isEqualTo("[5,23]");
        assertThat(task.getTaskStatus()).isEqualTo("PENDING");
        assertThat(task.getAttemptCount()).isZero();
        InOrder inOrder = inOrder(workRepository, taskRepository);
        inOrder.verify(workRepository).claimPendingWork(11L);
        inOrder.verify(taskRepository).insertTask(task);
    }

    @Test
    void retryOrFailAnimationTaskShouldReturnPendingBeforeAttemptLimit() {
        WorkAuditWorkRepository workRepository = mock(WorkAuditWorkRepository.class);
        WorkAuditTaskRepository taskRepository = mock(WorkAuditTaskRepository.class);
        WorkAuditClaimTransactionService service = service(workRepository, taskRepository);
        when(taskRepository.findAttemptCountById(101L)).thenReturn(2);
        when(taskRepository.markAnimationRetryPending(101L, "claim-token", "失败", "{}")).thenReturn(true);
        when(workRepository.updateAuditStatusAndReasonsForRound(
                11L, 2, WorkAuditStatusDict.AUDITING, null, null, null)).thenReturn(true);

        boolean updated = service.retryOrFailAnimationTask(
                101L, 11L, 2, "claim-token", "失败", "{}", 3);

        assertThat(updated).isTrue();
        verify(taskRepository).markAnimationRetryPending(101L, "claim-token", "失败", "{}");
        verify(workRepository).updateAuditStatusAndReasonsForRound(
                11L, 2, WorkAuditStatusDict.AUDITING, null, null, null);
        verify(taskRepository, never()).markAnimationFailed(101L, "claim-token", "失败", "{}");
    }

    @Test
    void retryOrFailAnimationTaskShouldFailAtAttemptLimit() {
        WorkAuditWorkRepository workRepository = mock(WorkAuditWorkRepository.class);
        WorkAuditTaskRepository taskRepository = mock(WorkAuditTaskRepository.class);
        WorkAuditClaimTransactionService service = service(workRepository, taskRepository);
        when(taskRepository.findAttemptCountById(101L)).thenReturn(3);
        when(taskRepository.markAnimationFailed(101L, "claim-token", "失败", "{}")).thenReturn(true);
        when(workRepository.updateAuditStatusAndReasonsForRound(
                11L,
                2,
                WorkAuditStatusDict.FAILED,
                WorkAuditReasonCodeDict.AUDIT_SERVICE_ERROR.getCode(),
                "[\"AUDIT_SERVICE_ERROR\"]",
                "动图审核失败且已达到最大重试次数")).thenReturn(true);

        boolean updated = service.retryOrFailAnimationTask(
                101L, 11L, 2, "claim-token", "失败", "{}", 3);

        assertThat(updated).isTrue();
        verify(taskRepository).markAnimationFailed(101L, "claim-token", "失败", "{}");
        verify(workRepository).updateAuditStatusAndReasonsForRound(
                11L,
                2,
                WorkAuditStatusDict.FAILED,
                WorkAuditReasonCodeDict.AUDIT_SERVICE_ERROR.getCode(),
                "[\"AUDIT_SERVICE_ERROR\"]",
                "动图审核失败且已达到最大重试次数");
        verify(taskRepository, never()).markAnimationRetryPending(101L, "claim-token", "失败", "{}");
    }

    @Test
    void staleAnimationWorkerShouldNotUpdateWorkAfterClaimTokenWasLost() {
        WorkAuditWorkRepository workRepository = mock(WorkAuditWorkRepository.class);
        WorkAuditTaskRepository taskRepository = mock(WorkAuditTaskRepository.class);
        WorkAuditClaimTransactionService service = service(workRepository, taskRepository);
        when(taskRepository.findAttemptCountById(101L)).thenReturn(2);
        when(taskRepository.markAnimationRetryPending(
                101L, "stale-token", "失败", "{}")).thenReturn(false);

        boolean updated = service.retryOrFailAnimationTask(
                101L, 11L, 2, "stale-token", "失败", "{}", 3);

        assertThat(updated).isFalse();
        verify(workRepository, never()).updateAuditStatusAndReasonsForRound(
                any(), any(), any(), any(), any(), any());
    }

    @Test
    void staleAnimationSuccessShouldNotUpdateWorkAfterClaimTokenWasLost() {
        WorkAuditWorkRepository workRepository = mock(WorkAuditWorkRepository.class);
        WorkAuditTaskRepository taskRepository = mock(WorkAuditTaskRepository.class);
        WorkAuditClaimTransactionService service = service(workRepository, taskRepository);
        when(taskRepository.markAnimationSuccess(
                101L, "stale-token", AuditResultDict.PASS,
                "Success", 0, "Normal", 0, "{}")).thenReturn(false);

        boolean updated = service.markAnimationTaskSuccessAndUpdateWork(
                101L, 11L, 2, "stale-token",
                AuditResultDict.PASS, "Success", 0, "Normal", 0,
                List.of(), "{}", WorkAuditStatusDict.PASSED, null);

        assertThat(updated).isFalse();
        verify(workRepository, never()).updateAuditStatusAndReasonsForRound(
                any(), any(), any(), any(), any(), any());
    }

    @Test
    void animationTaskShouldCommitTerminalStateWhenWorkAuditRoundChanged() {
        WorkAuditWorkRepository workRepository = mock(WorkAuditWorkRepository.class);
        WorkAuditTaskRepository taskRepository = mock(WorkAuditTaskRepository.class);
        WorkAuditClaimTransactionService service = service(workRepository, taskRepository);
        when(taskRepository.markAnimationSuccess(
                101L, "claim-token", AuditResultDict.PASS,
                "Success", 0, "Normal", 0, "{}")).thenReturn(true);
        when(workRepository.updateAuditStatusAndReasonsForRound(
                11L, 2, WorkAuditStatusDict.PASSED, null, null, null)).thenReturn(false);

        boolean updated = service.markAnimationTaskSuccessAndUpdateWork(
                101L, 11L, 2, "claim-token",
                AuditResultDict.PASS, "Success", 0, "Normal", 0,
                List.of(), "{}", WorkAuditStatusDict.PASSED, null);

        assertThat(updated).isFalse();
        verify(taskRepository).markAnimationSuccess(
                101L, "claim-token", AuditResultDict.PASS,
                "Success", 0, "Normal", 0, "{}");
        verify(workRepository).updateAuditStatusAndReasonsForRound(
                11L, 2, WorkAuditStatusDict.PASSED, null, null, null);
    }

    @Test
    void animationRetryShouldBecomeFailedWhenWorkAuditRoundChanged() {
        WorkAuditWorkRepository workRepository = mock(WorkAuditWorkRepository.class);
        WorkAuditTaskRepository taskRepository = mock(WorkAuditTaskRepository.class);
        WorkAuditClaimTransactionService service = service(workRepository, taskRepository);
        when(taskRepository.findAttemptCountById(101L)).thenReturn(2);
        when(taskRepository.markAnimationRetryPending(
                101L, "claim-token", "失败", "{}")).thenReturn(true);
        when(workRepository.updateAuditStatusAndReasonsForRound(
                11L, 2, WorkAuditStatusDict.AUDITING, null, null, null)).thenReturn(false);

        boolean updated = service.retryOrFailAnimationTask(
                101L, 11L, 2, "claim-token", "失败", "{}", 3);

        assertThat(updated).isFalse();
        verify(taskRepository).markPendingAnimationFailed(
                101L, "作品审核轮次已变化", "{}");
    }

    @Test
    void markTaskFailedAndUpdateWorkFailedShouldUpdateTaskBeforeWorkReason() {
        WorkAuditWorkRepository workRepository = mock(WorkAuditWorkRepository.class);
        WorkAuditTaskRepository taskRepository = mock(WorkAuditTaskRepository.class);
        WorkAuditClaimTransactionService service =
                service(workRepository, taskRepository);

        service.markTaskFailedAndUpdateWorkFailed(101L, 11L, "远端调用失败", "{}", "提交腾讯云视频审核失败：远端调用失败");

        InOrder inOrder = inOrder(taskRepository, workRepository);
        inOrder.verify(taskRepository).markFailed(101L, "远端调用失败", "{}");
        inOrder.verify(workRepository).updateAuditStatusAndReasons(
                11L,
                WorkAuditStatusDict.FAILED,
                WorkAuditReasonCodeDict.AUDIT_SERVICE_ERROR.getCode(),
                "[\"AUDIT_SERVICE_ERROR\"]",
                "提交腾讯云视频审核失败：远端调用失败");
    }

    @Test
    void markVideoSubmittedAndUpdateWorkAuditingShouldClearReason() {
        WorkAuditWorkRepository workRepository = mock(WorkAuditWorkRepository.class);
        WorkAuditTaskRepository taskRepository = mock(WorkAuditTaskRepository.class);
        WorkAuditClaimTransactionService service =
                service(workRepository, taskRepository);

        service.markVideoSubmittedAndUpdateWorkAuditing(101L, 11L, "video-job-id", "{}");

        InOrder inOrder = inOrder(taskRepository, workRepository);
        inOrder.verify(taskRepository).markVideoSubmitted(101L, "video-job-id", "{}");
        inOrder.verify(workRepository).updateAuditStatusAndReasons(
                11L, WorkAuditStatusDict.AUDITING, null, null, null);
    }

    @Test
    void markTaskSuccessAndUpdateWorkShouldClearReasonWhenPassed() {
        WorkAuditWorkRepository workRepository = mock(WorkAuditWorkRepository.class);
        WorkAuditTaskRepository taskRepository = mock(WorkAuditTaskRepository.class);
        WorkAuditClaimTransactionService service =
                service(workRepository, taskRepository);

        service.markTaskSuccessAndUpdateWork(
                101L, 11L, AuditResultDict.PASS, "Success", 0, "Normal", 0, List.of(), "{}",
                WorkAuditStatusDict.PASSED, null);

        InOrder inOrder = inOrder(taskRepository, workRepository);
        inOrder.verify(taskRepository).markSuccess(101L, AuditResultDict.PASS, "Success", 0, "Normal", 0, "{}");
        inOrder.verify(workRepository).updateAuditStatusAndReasons(
                11L, WorkAuditStatusDict.PASSED, null, null, null);
    }

    @Test
    void markTaskSuccessAndUpdateWorkShouldPersistStableRiskCodeForUnsafeResult() {
        WorkAuditWorkRepository workRepository = mock(WorkAuditWorkRepository.class);
        WorkAuditTaskRepository taskRepository = mock(WorkAuditTaskRepository.class);
        WorkAuditClaimTransactionService service =
                service(workRepository, taskRepository);

        service.markTaskSuccessAndUpdateWork(
                101L, 11L, AuditResultDict.REVIEW, "Success", 2, " Ads ", 88,
                List.of(new TencentCiAuditRisk("Porn", AuditResultDict.BLOCK, 95)), "{}",
                WorkAuditStatusDict.REVIEW_REQUIRED, "腾讯云判定疑似违规");

        InOrder inOrder = inOrder(taskRepository, workRepository);
        inOrder.verify(taskRepository).markSuccess(101L, AuditResultDict.REVIEW, "Success", 2, " Ads ", 88, "{}");
        inOrder.verify(workRepository).updateAuditStatusAndReasons(
                11L,
                WorkAuditStatusDict.REVIEW_REQUIRED,
                WorkAuditReasonCodeDict.ADVERTISING_CONTENT.getCode(),
                "[\"ADVERTISING_CONTENT\",\"PORN_CONTENT\"]",
                "腾讯云判定疑似违规");
    }

    private WorkAuditClaimTransactionService service(WorkAuditWorkRepository workRepository,
                                                       WorkAuditTaskRepository taskRepository) {
        WorkAuditRiskTypeResolver riskTypeResolver = new WorkAuditRiskTypeResolver();
        return new WorkAuditClaimTransactionService(
                workRepository,
                taskRepository,
                riskTypeResolver,
                new WorkAuditRiskCollectionResolver(riskTypeResolver));
    }

    private void assertTransactional(String methodName, Class<?>... parameterTypes) throws NoSuchMethodException {
        Method method = WorkAuditClaimTransactionService.class.getDeclaredMethod(methodName, parameterTypes);

        Transactional transactional = method.getAnnotation(Transactional.class);

        assertThat(transactional).isNotNull();
        assertThat(transactional.rollbackFor()).contains(Exception.class);
    }
}
