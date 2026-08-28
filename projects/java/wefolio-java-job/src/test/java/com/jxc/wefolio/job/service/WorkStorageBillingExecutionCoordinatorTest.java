package com.jxc.wefolio.job.service;

import org.junit.jupiter.api.Test;
import org.springframework.core.task.TaskExecutor;
import org.springframework.core.task.TaskRejectedException;

import java.time.Duration;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

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
        JobExecutionLifecycle lifecycle = new JobExecutionLifecycle();
        WorkStorageBillingExecutionCoordinator coordinator =
                new WorkStorageBillingExecutionCoordinator(billingService, executor, lifecycle);

        var accepted = coordinator.submit(month(7), WorkStorageBillingExecutionCoordinator.TriggerSource.MANUAL);
        var duplicate = coordinator.submit(month(8), WorkStorageBillingExecutionCoordinator.TriggerSource.MANUAL);

        assertThat(accepted.accepted()).isTrue();
        assertThat(accepted.status()).isEqualTo("ACCEPTED");
        assertThat(duplicate.accepted()).isFalse();
        assertThat(duplicate.status()).isEqualTo("ALREADY_RUNNING");
        assertThat(duplicate.executionId()).isEqualTo(accepted.executionId());
        assertThat(duplicate.billingMonth()).isEqualTo(month(7));
        assertThat(lifecycle.snapshot().activeTaskCount()).isOne();
        executor.runNext();
        verify(billingService).run(month(7), accepted.executionId());
        assertThat(lifecycle.snapshot().activeTaskCount()).isZero();

        assertThat(coordinator.submit(month(8), WorkStorageBillingExecutionCoordinator.TriggerSource.MANUAL)
                .accepted()).isTrue();
    }

    @Test
    void failedRunShouldReleaseRunningState() {
        WorkStorageBillingService billingService = mock(WorkStorageBillingService.class);
        when(billingService.run(any(), any())).thenThrow(new IllegalStateException("执行失败"));
        ManualExecutor executor = new ManualExecutor();
        JobExecutionLifecycle lifecycle = new JobExecutionLifecycle();
        WorkStorageBillingExecutionCoordinator coordinator =
                new WorkStorageBillingExecutionCoordinator(billingService, executor, lifecycle);

        coordinator.submit(month(7), WorkStorageBillingExecutionCoordinator.TriggerSource.MANUAL);
        executor.runNext();

        assertThat(lifecycle.snapshot().activeTaskCount()).isZero();
        assertThat(coordinator.submit(month(8), WorkStorageBillingExecutionCoordinator.TriggerSource.MANUAL)
                .accepted()).isTrue();
    }

    @Test
    void rejectedExecutionShouldReleaseStateAndReportUnavailable() {
        JobExecutionLifecycle lifecycle = new JobExecutionLifecycle();
        WorkStorageBillingExecutionCoordinator coordinator = new WorkStorageBillingExecutionCoordinator(
                mock(WorkStorageBillingService.class),
                task -> {
                    throw new TaskRejectedException("队列已满");
                },
                lifecycle);

        assertThatThrownBy(() -> coordinator.submit(
                month(7), WorkStorageBillingExecutionCoordinator.TriggerSource.MANUAL))
                .isInstanceOf(WorkStorageBillingUnavailableException.class);
        assertThat(lifecycle.snapshot().activeTaskCount()).isZero();
        assertThatThrownBy(() -> coordinator.submit(
                month(7), WorkStorageBillingExecutionCoordinator.TriggerSource.MANUAL))
                .isInstanceOf(WorkStorageBillingUnavailableException.class);
        assertThat(lifecycle.snapshot().activeTaskCount()).isZero();
    }

    @Test
    void acceptedAsyncWorkShouldRemainActiveUntilWorkerFinishes() {
        JobExecutionLifecycle lifecycle = new JobExecutionLifecycle();
        ManualExecutor executor = new ManualExecutor();
        WorkStorageBillingExecutionCoordinator coordinator = new WorkStorageBillingExecutionCoordinator(
                mock(WorkStorageBillingService.class), executor, lifecycle);

        coordinator.submit(month(7), WorkStorageBillingExecutionCoordinator.TriggerSource.MANUAL);
        assertThat(lifecycle.snapshot().activeTaskCount()).isOne();

        lifecycle.pause(Duration.ofMinutes(10));
        assertThat(lifecycle.snapshot().status()).isEqualTo(JobExecutionLifecycle.Status.DRAINING);
        executor.runNext();

        assertThat(lifecycle.snapshot().status()).isEqualTo(JobExecutionLifecycle.Status.DISABLED);
        assertThat(lifecycle.snapshot().activeTaskCount()).isZero();
    }

    @Test
    void manualSubmissionShouldBeRejectedAfterDisable() {
        JobExecutionLifecycle lifecycle = new JobExecutionLifecycle();
        lifecycle.pause(Duration.ofMinutes(10));
        WorkStorageBillingExecutionCoordinator coordinator = new WorkStorageBillingExecutionCoordinator(
                mock(WorkStorageBillingService.class), new ManualExecutor(), lifecycle);

        assertThatThrownBy(() -> coordinator.submit(
                month(7), WorkStorageBillingExecutionCoordinator.TriggerSource.MANUAL))
                .isInstanceOf(WorkStorageBillingUnavailableException.class)
                .hasMessage("job 服务正在停用，不再接受作品存储结算任务");
    }

    @Test
    void admittedScheduledCallbackShouldHandOffAsyncWorkDuringDraining() {
        JobExecutionLifecycle lifecycle = new JobExecutionLifecycle();
        ManualExecutor executor = new ManualExecutor();
        WorkStorageBillingExecutionCoordinator coordinator = new WorkStorageBillingExecutionCoordinator(
                mock(WorkStorageBillingService.class), executor, lifecycle);
        AtomicReference<WorkStorageBillingExecutionCoordinator.Submission> submission = new AtomicReference<>();

        lifecycle.runScheduled(() -> {
            lifecycle.pause(Duration.ofMinutes(10));
            submission.set(coordinator.submit(
                    month(7), WorkStorageBillingExecutionCoordinator.TriggerSource.SCHEDULED));
        });

        assertThat(submission.get().accepted()).isTrue();
        assertThat(lifecycle.snapshot().status()).isEqualTo(JobExecutionLifecycle.Status.DRAINING);
        assertThat(lifecycle.snapshot().activeTaskCount()).isOne();
        executor.runNext();
        assertThat(lifecycle.snapshot().status()).isEqualTo(JobExecutionLifecycle.Status.DISABLED);
    }

    @Test
    void scheduledSourceOutsideDecoratorShouldReportInvalidContext() {
        JobExecutionLifecycle lifecycle = new JobExecutionLifecycle();
        WorkStorageBillingExecutionCoordinator coordinator = new WorkStorageBillingExecutionCoordinator(
                mock(WorkStorageBillingService.class), new ManualExecutor(), lifecycle);

        assertThatThrownBy(() -> coordinator.submit(
                month(7), WorkStorageBillingExecutionCoordinator.TriggerSource.SCHEDULED))
                .isInstanceOf(WorkStorageBillingUnavailableException.class)
                .hasMessage("作品存储结算调度上下文无效，无法提交异步任务");
        assertThat(lifecycle.snapshot().status()).isEqualTo(JobExecutionLifecycle.Status.ACCEPTING);
        assertThat(lifecycle.snapshot().activeTaskCount()).isZero();
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
