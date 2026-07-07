package com.jxc.wefolio.job.service;

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
}
