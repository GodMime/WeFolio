package com.jxc.wefolio.job.service;

import com.jxc.wefolio.job.dict.AuditResultDict;
import com.jxc.wefolio.job.dict.WorkAuditStatusDict;
import com.jxc.wefolio.job.entity.WorkAuditTaskEntity;
import com.jxc.wefolio.job.repo.WorkAuditTaskRepository;
import com.jxc.wefolio.job.repo.WorkAuditWorkRepository;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import org.springframework.transaction.annotation.Transactional;

import java.lang.reflect.Method;

import static org.assertj.core.api.Assertions.assertThat;
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
    void resultUpdateMethodsShouldUseSpringTransaction() throws NoSuchMethodException {
        assertTransactional("markTaskFailedAndUpdateWorkFailed",
                Long.class, Long.class, String.class, String.class, String.class);
        assertTransactional("markTaskSuccessAndUpdateWork",
                Long.class, Long.class, AuditResultDict.class, String.class, Integer.class, String.class,
                Integer.class, String.class, WorkAuditStatusDict.class, String.class);
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
                new WorkAuditClaimTransactionService(workRepository, taskRepository);
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
                new WorkAuditClaimTransactionService(workRepository, taskRepository);
        WorkAuditTaskEntity task = new WorkAuditTaskEntity();
        when(workRepository.claimPendingWork(11L)).thenReturn(false);

        WorkAuditTaskEntity result = service.claimAndCreateSubmittingTask(11L, task);

        assertThat(result).isNull();
        verify(workRepository).claimPendingWork(11L);
        verify(taskRepository, never()).insertTask(task);
    }

    @Test
    void markTaskFailedAndUpdateWorkFailedShouldUpdateTaskBeforeWorkReason() {
        WorkAuditWorkRepository workRepository = mock(WorkAuditWorkRepository.class);
        WorkAuditTaskRepository taskRepository = mock(WorkAuditTaskRepository.class);
        WorkAuditClaimTransactionService service =
                new WorkAuditClaimTransactionService(workRepository, taskRepository);

        service.markTaskFailedAndUpdateWorkFailed(101L, 11L, "远端调用失败", "{}", "提交腾讯云视频审核失败：远端调用失败");

        InOrder inOrder = inOrder(taskRepository, workRepository);
        inOrder.verify(taskRepository).markFailed(101L, "远端调用失败", "{}");
        inOrder.verify(workRepository).updateAuditStatusAndRejectReason(
                11L, WorkAuditStatusDict.FAILED, "提交腾讯云视频审核失败：远端调用失败");
    }

    @Test
    void markVideoSubmittedAndUpdateWorkAuditingShouldClearReason() {
        WorkAuditWorkRepository workRepository = mock(WorkAuditWorkRepository.class);
        WorkAuditTaskRepository taskRepository = mock(WorkAuditTaskRepository.class);
        WorkAuditClaimTransactionService service =
                new WorkAuditClaimTransactionService(workRepository, taskRepository);

        service.markVideoSubmittedAndUpdateWorkAuditing(101L, 11L, "video-job-id", "{}");

        InOrder inOrder = inOrder(taskRepository, workRepository);
        inOrder.verify(taskRepository).markVideoSubmitted(101L, "video-job-id", "{}");
        inOrder.verify(workRepository).updateAuditStatusAndRejectReason(11L, WorkAuditStatusDict.AUDITING, null);
    }

    @Test
    void markTaskSuccessAndUpdateWorkShouldClearReasonWhenPassed() {
        WorkAuditWorkRepository workRepository = mock(WorkAuditWorkRepository.class);
        WorkAuditTaskRepository taskRepository = mock(WorkAuditTaskRepository.class);
        WorkAuditClaimTransactionService service =
                new WorkAuditClaimTransactionService(workRepository, taskRepository);

        service.markTaskSuccessAndUpdateWork(
                101L, 11L, AuditResultDict.PASS, "Success", 0, "Normal", 0, "{}",
                WorkAuditStatusDict.PASSED, null);

        InOrder inOrder = inOrder(taskRepository, workRepository);
        inOrder.verify(taskRepository).markSuccess(101L, AuditResultDict.PASS, "Success", 0, "Normal", 0, "{}");
        inOrder.verify(workRepository).updateAuditStatusAndRejectReason(11L, WorkAuditStatusDict.PASSED, null);
    }

    private void assertTransactional(String methodName, Class<?>... parameterTypes) throws NoSuchMethodException {
        Method method = WorkAuditClaimTransactionService.class.getDeclaredMethod(methodName, parameterTypes);

        Transactional transactional = method.getAnnotation(Transactional.class);

        assertThat(transactional).isNotNull();
        assertThat(transactional.rollbackFor()).contains(Exception.class);
    }
}
