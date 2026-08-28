package com.jxc.wefolio.job.service;

import com.jxc.wefolio.job.config.JobSchedulingTaskDecorator;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.lang.reflect.Constructor;
import java.lang.reflect.Modifier;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 后台任务统一执行生命周期测试。
 */
class JobExecutionLifecycleTest {

    private static final Instant INITIAL_INSTANT = Instant.parse("2026-08-27T02:00:00Z");

    @Test
    void productionShouldNotInjectAnUnqualifiedApplicationClock() throws NoSuchMethodException {
        Constructor<JobExecutionLifecycle> clockConstructor =
                JobExecutionLifecycle.class.getDeclaredConstructor(Clock.class);

        assertThat(Modifier.isPublic(clockConstructor.getModifiers())).isFalse();
        assertThat(clockConstructor.isAnnotationPresent(Autowired.class)).isFalse();
    }

    @Test
    void pauseShouldRejectNewWorkAndLazilyReopenAtDeadline() {
        MutableClock clock = new MutableClock(INITIAL_INSTANT);
        JobExecutionLifecycle lifecycle = new JobExecutionLifecycle(clock);

        JobExecutionLifecycle.ExecutionSnapshot paused = lifecycle.pause(Duration.ofMinutes(10));

        assertThat(paused.status()).isEqualTo(JobExecutionLifecycle.Status.DISABLED);
        assertThat(paused.pausedUntil()).isEqualTo(INITIAL_INSTANT.plus(Duration.ofMinutes(10)));
        assertThat(lifecycle.tryAcquire()).isEmpty();

        clock.advance(Duration.ofMinutes(10));
        JobExecutionLifecycle.ExecutionPermit permit = lifecycle.tryAcquire().orElseThrow();

        assertThat(lifecycle.snapshot().status()).isEqualTo(JobExecutionLifecycle.Status.ACCEPTING);
        assertThat(lifecycle.snapshot().pausedUntil()).isNull();
        permit.close();
    }

    @Test
    void repeatedPauseShouldExtendDeadlineFromCurrentTime() {
        MutableClock clock = new MutableClock(INITIAL_INSTANT);
        JobExecutionLifecycle lifecycle = new JobExecutionLifecycle(clock);
        lifecycle.pause(Duration.ofMinutes(10));
        clock.advance(Duration.ofMinutes(9));

        JobExecutionLifecycle.ExecutionSnapshot extended = lifecycle.pause(Duration.ofMinutes(10));

        assertThat(extended.pausedUntil()).isEqualTo(INITIAL_INSTANT.plus(Duration.ofMinutes(19)));
        clock.advance(Duration.ofMinutes(1));
        assertThat(lifecycle.tryAcquire()).isEmpty();
        clock.advance(Duration.ofMinutes(9));
        lifecycle.tryAcquire().orElseThrow().close();
    }

    @Test
    void registeredDecoratedCallbackShouldRunAgainAfterPauseExpires() {
        MutableClock clock = new MutableClock(INITIAL_INSTANT);
        JobExecutionLifecycle lifecycle = new JobExecutionLifecycle(clock);
        JobSchedulingTaskDecorator decorator = new JobSchedulingTaskDecorator(lifecycle);
        AtomicInteger invocationCount = new AtomicInteger();
        Runnable decorated = decorator.decorate(invocationCount::incrementAndGet);
        lifecycle.pause(Duration.ofMinutes(10));

        decorated.run();

        assertThat(invocationCount).hasValue(0);
        clock.advance(Duration.ofMinutes(10));
        decorated.run();

        assertThat(invocationCount).hasValue(1);
    }

    @Test
    void pauseShouldDrainActivePermitBeforeBecomingFullyPaused() throws InterruptedException {
        MutableClock clock = new MutableClock(INITIAL_INSTANT);
        JobExecutionLifecycle lifecycle = new JobExecutionLifecycle(clock);
        JobExecutionLifecycle.ExecutionPermit permit = lifecycle.tryAcquire().orElseThrow();

        JobExecutionLifecycle.ExecutionSnapshot draining = lifecycle.pause(Duration.ofMinutes(10));

        assertThat(draining.status()).isEqualTo(JobExecutionLifecycle.Status.DRAINING);
        assertThat(lifecycle.tryAcquire()).isEmpty();
        assertThat(lifecycle.awaitDisabled(Duration.ofMillis(5))).isFalse();

        permit.close();

        assertThat(lifecycle.awaitDisabled(Duration.ofMillis(10))).isTrue();
        assertThat(lifecycle.snapshot().status()).isEqualTo(JobExecutionLifecycle.Status.DISABLED);
        assertThat(lifecycle.snapshot().pausedUntil())
                .isEqualTo(INITIAL_INSTANT.plus(Duration.ofMinutes(10)));
    }

    @Test
    void disableWithNoActiveWorkShouldImmediatelyBecomeDisabled() throws InterruptedException {
        JobExecutionLifecycle lifecycle = new JobExecutionLifecycle();

        JobExecutionLifecycle.ExecutionSnapshot snapshot = lifecycle.pause(Duration.ofMinutes(10));

        assertThat(snapshot.status()).isEqualTo(JobExecutionLifecycle.Status.DISABLED);
        assertThat(snapshot.activeTaskCount()).isZero();
        assertThat(lifecycle.awaitDisabled(Duration.ofMillis(10))).isTrue();
        assertThat(lifecycle.tryAcquire()).isEmpty();
    }

    @Test
    void disableShouldWaitForAdmittedWorkAndRejectNewWork() throws InterruptedException {
        JobExecutionLifecycle lifecycle = new JobExecutionLifecycle();
        JobExecutionLifecycle.ExecutionPermit permit = lifecycle.tryAcquire().orElseThrow();

        assertThat(lifecycle.pause(Duration.ofMinutes(10)).status())
                .isEqualTo(JobExecutionLifecycle.Status.DRAINING);
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
            assertThat(lifecycle.pause(Duration.ofMinutes(10)).status())
                    .isEqualTo(JobExecutionLifecycle.Status.DRAINING);
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
        lifecycle.pause(Duration.ofMinutes(10));

        lifecycle.runScheduled(invocationCount::incrementAndGet);

        assertThat(invocationCount).hasValue(0);
        assertThat(lifecycle.snapshot().status()).isEqualTo(JobExecutionLifecycle.Status.DISABLED);
    }

    /** 测试可推进时钟，避免真实等待暂停窗口。 */
    private static final class MutableClock extends Clock {

        /** 当前测试时间。 */
        private final AtomicReference<Instant> currentInstant;

        private MutableClock(Instant initialInstant) {
            this.currentInstant = new AtomicReference<>(initialInstant);
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return currentInstant.get();
        }

        /** 推进测试时间。 */
        private void advance(Duration duration) {
            currentInstant.updateAndGet(current -> current.plus(duration));
        }
    }
}
