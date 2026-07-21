package com.jxc.wefolio.job.service;

import org.junit.jupiter.api.Test;
import org.springframework.core.task.TaskExecutor;
import org.springframework.core.task.TaskRejectedException;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 月度作品存储异步执行协调器测试。
 */
class WorkStorageBillingExecutionCoordinatorTest {

    @Test
    void submitShouldReturnAcceptedAndRejectSecondSubmissionWithCurrentExecution() {
        WorkStorageBillingService billingService = mock(WorkStorageBillingService.class);
        ManualExecutor executor = new ManualExecutor();
        WorkStorageBillingExecutionCoordinator coordinator =
                new WorkStorageBillingExecutionCoordinator(billingService, executor);

        var accepted = coordinator.submit(month(7), WorkStorageBillingExecutionCoordinator.TriggerSource.MANUAL);
        var duplicate = coordinator.submit(month(8), WorkStorageBillingExecutionCoordinator.TriggerSource.SCHEDULED);

        assertThat(accepted.accepted()).isTrue();
        assertThat(accepted.status()).isEqualTo("ACCEPTED");
        assertThat(duplicate.accepted()).isFalse();
        assertThat(duplicate.status()).isEqualTo("ALREADY_RUNNING");
        assertThat(duplicate.executionId()).isEqualTo(accepted.executionId());
        assertThat(duplicate.billingMonth()).isEqualTo(month(7));
        executor.runNext();
        verify(billingService).run(month(7), accepted.executionId());

        assertThat(coordinator.submit(month(8), WorkStorageBillingExecutionCoordinator.TriggerSource.MANUAL)
                .accepted()).isTrue();
    }

    @Test
    void failedRunShouldReleaseRunningState() {
        WorkStorageBillingService billingService = mock(WorkStorageBillingService.class);
        when(billingService.run(any(), any())).thenThrow(new IllegalStateException("执行失败"));
        ManualExecutor executor = new ManualExecutor();
        WorkStorageBillingExecutionCoordinator coordinator =
                new WorkStorageBillingExecutionCoordinator(billingService, executor);

        coordinator.submit(month(7), WorkStorageBillingExecutionCoordinator.TriggerSource.MANUAL);
        executor.runNext();

        assertThat(coordinator.submit(month(8), WorkStorageBillingExecutionCoordinator.TriggerSource.MANUAL)
                .accepted()).isTrue();
    }

    @Test
    void rejectedExecutionShouldReleaseStateAndReportUnavailable() {
        WorkStorageBillingExecutionCoordinator coordinator = new WorkStorageBillingExecutionCoordinator(
                mock(WorkStorageBillingService.class),
                task -> {
                    throw new TaskRejectedException("队列已满");
                });

        assertThatThrownBy(() -> coordinator.submit(
                month(7), WorkStorageBillingExecutionCoordinator.TriggerSource.MANUAL))
                .isInstanceOf(WorkStorageBillingUnavailableException.class);
        assertThatThrownBy(() -> coordinator.submit(
                month(7), WorkStorageBillingExecutionCoordinator.TriggerSource.MANUAL))
                .isInstanceOf(WorkStorageBillingUnavailableException.class);
    }

    private LocalDate month(int month) {
        return LocalDate.of(2026, month, 1);
    }

    private static final class ManualExecutor implements TaskExecutor {
        private final List<Runnable> tasks = new ArrayList<>();

        @Override
        public void execute(Runnable task) {
            tasks.add(task);
        }

        private void runNext() {
            tasks.removeFirst().run();
        }
    }
}
