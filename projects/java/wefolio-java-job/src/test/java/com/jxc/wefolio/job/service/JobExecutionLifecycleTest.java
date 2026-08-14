package com.jxc.wefolio.job.service;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 后台任务统一执行生命周期测试。
 */
class JobExecutionLifecycleTest {

    @Test
    void disableWithNoActiveWorkShouldImmediatelyBecomeDisabled() throws InterruptedException {
        JobExecutionLifecycle lifecycle = new JobExecutionLifecycle();

        JobExecutionLifecycle.ExecutionSnapshot snapshot = lifecycle.disable();

        assertThat(snapshot.status()).isEqualTo(JobExecutionLifecycle.Status.DISABLED);
        assertThat(snapshot.activeTaskCount()).isZero();
        assertThat(lifecycle.awaitDisabled(Duration.ofMillis(10))).isTrue();
        assertThat(lifecycle.tryAcquire()).isEmpty();
    }

    @Test
    void disableShouldWaitForAdmittedWorkAndRejectNewWork() throws InterruptedException {
        JobExecutionLifecycle lifecycle = new JobExecutionLifecycle();
        JobExecutionLifecycle.ExecutionPermit permit = lifecycle.tryAcquire().orElseThrow();

        assertThat(lifecycle.disable().status()).isEqualTo(JobExecutionLifecycle.Status.DRAINING);
        assertThat(lifecycle.tryAcquire()).isEmpty();
        assertThat(lifecycle.awaitDisabled(Duration.ofMillis(5))).isFalse();

        permit.close();
        permit.close();

        assertThat(lifecycle.awaitDisabled(Duration.ofMillis(10))).isTrue();
        assertThat(lifecycle.snapshot().status()).isEqualTo(JobExecutionLifecycle.Status.DISABLED);
        assertThat(lifecycle.snapshot().activeTaskCount()).isZero();
    }

    @Test
    void admittedScheduledCallbackShouldCreateContinuationDuringDraining() {
        JobExecutionLifecycle lifecycle = new JobExecutionLifecycle();
        AtomicReference<JobExecutionLifecycle.ExecutionPermit> continuation = new AtomicReference<>();

        lifecycle.runScheduled(() -> {
            assertThat(lifecycle.disable().status()).isEqualTo(JobExecutionLifecycle.Status.DRAINING);
            continuation.set(lifecycle.tryAcquireContinuation().orElseThrow());
        });

        assertThat(lifecycle.snapshot().status()).isEqualTo(JobExecutionLifecycle.Status.DRAINING);
        assertThat(lifecycle.snapshot().activeTaskCount()).isOne();
        assertThat(lifecycle.tryAcquireContinuation()).isEmpty();

        continuation.get().close();

        assertThat(lifecycle.snapshot().status()).isEqualTo(JobExecutionLifecycle.Status.DISABLED);
        assertThat(lifecycle.snapshot().activeTaskCount()).isZero();
    }

    @Test
    void scheduledCallbackShouldNotRunAfterDisable() {
        JobExecutionLifecycle lifecycle = new JobExecutionLifecycle();
        AtomicInteger invocationCount = new AtomicInteger();
        lifecycle.disable();

        lifecycle.runScheduled(invocationCount::incrementAndGet);

        assertThat(invocationCount).hasValue(0);
        assertThat(lifecycle.snapshot().status()).isEqualTo(JobExecutionLifecycle.Status.DISABLED);
    }
}
