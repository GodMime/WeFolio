package com.jxc.wefolio.job.config;

import com.jxc.wefolio.job.service.JobExecutionLifecycle;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;
import org.springframework.scheduling.support.CronTrigger;

import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/** 使用真实周期调度验证三个调度器在暂停结束后能自动恢复。 */
class JobLifecycleTaskSchedulerTest {

    /** 每种调度器和周期类型都必须跳过暂停期业务并保持续排。 */
    @ParameterizedTest
    @CsvSource({"0,cron", "0,rate", "0,delay", "1,cron", "1,rate", "1,delay",
            "2,cron", "2,rate", "2,delay"})
    void shouldResumeWithoutRescheduling(int bean, String mode) throws Exception {
        JobExecutionLifecycle lifecycle = new JobExecutionLifecycle();
        AtomicReference<CountDownLatch> executions = new AtomicReference<>(new CountDownLatch(1));
        CountDownLatch skipped = new CountDownLatch(1);
        AtomicBoolean ranDuringPause = new AtomicBoolean();
        JobSchedulingTaskDecorator decorator = new JobSchedulingTaskDecorator(lifecycle) {
            @Override
            public Runnable decorate(Runnable callback) {
                Runnable guarded = super.decorate(callback);
                return () -> {
                    boolean paused = lifecycle.snapshot().status() == JobExecutionLifecycle.Status.DISABLED;
                    guarded.run();
                    if (paused) {
                        skipped.countDown();
                    }
                };
            }
        };
        ThreadPoolTaskScheduler scheduler = switch (bean) {
            case 0 -> new VirtualPaymentSchedulingConfig().taskScheduler(decorator);
            case 1 -> new VirtualPaymentSchedulingConfig().virtualPaymentTaskScheduler(decorator);
            default -> new FeedbackUploadCleanupSchedulingConfig().feedbackUploadCleanupTaskScheduler(decorator);
        };
        scheduler.initialize();
        try {
            Runnable business = () -> {
                if (lifecycle.snapshot().status() == JobExecutionLifecycle.Status.DISABLED) {
                    ranDuringPause.set(true);
                }
                executions.get().countDown();
            };
            switch (mode) {
                case "cron" -> scheduler.schedule(business, new CronTrigger("* * * * * *"));
                case "rate" -> scheduler.scheduleAtFixedRate(business, Duration.ofMillis(20));
                default -> scheduler.scheduleWithFixedDelay(business, Duration.ofMillis(20));
            }
            assertThat(executions.get().await(3, TimeUnit.SECONDS)).isTrue();
            lifecycle.pause(Duration.ofSeconds(2));
            assertThat(skipped.await(2, TimeUnit.SECONDS)).isTrue();
            executions.set(new CountDownLatch(1));
            assertThat(executions.get().await(4, TimeUnit.SECONDS)).isTrue();
            assertThat(ranDuringPause).isFalse();
        } finally {
            scheduler.shutdown();
        }
    }
}
