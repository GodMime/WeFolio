package com.jxc.wefolio.job.config;

import com.jxc.wefolio.job.service.JobExecutionLifecycle;
import org.junit.jupiter.api.Test;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;

import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 反馈附件清理独立调度器配置测试。
 */
class FeedbackUploadCleanupSchedulingConfigTest {

    /** 验证清理任务使用独立单线程调度器并受停机生命周期保护。 */
    @Test
    void shouldCreateSingleThreadSchedulerProtectedByLifecycleDecorator() throws Exception {
        JobExecutionLifecycle lifecycle = new JobExecutionLifecycle();
        AtomicReference<CountDownLatch> executionLatch =
                new AtomicReference<>(new CountDownLatch(1));
        JobSchedulingTaskDecorator decorator = new JobSchedulingTaskDecorator(lifecycle) {
            @Override
            public Runnable decorate(Runnable callback) {
                Runnable protectedCallback = super.decorate(callback);
                return () -> {
                    try {
                        protectedCallback.run();
                    } finally {
                        executionLatch.get().countDown();
                    }
                };
            }
        };
        FeedbackUploadCleanupSchedulingConfig config =
                new FeedbackUploadCleanupSchedulingConfig();
        ThreadPoolTaskScheduler scheduler =
                config.feedbackUploadCleanupTaskScheduler(decorator);
        scheduler.initialize();

        try {
            assertThat(scheduler.getScheduledThreadPoolExecutor().getCorePoolSize()).isEqualTo(1);
            assertThat(scheduler.getThreadNamePrefix())
                    .isEqualTo(FeedbackUploadCleanupSchedulingConfig.THREAD_NAME_PREFIX);

            AtomicInteger invocationCount = new AtomicInteger();
            scheduler.schedule(invocationCount::incrementAndGet, Instant.now());
            assertThat(executionLatch.get().await(1, TimeUnit.SECONDS)).isTrue();

            lifecycle.pause(Duration.ofMinutes(10));
            executionLatch.set(new CountDownLatch(1));
            scheduler.schedule(invocationCount::incrementAndGet, Instant.now());
            assertThat(executionLatch.get().await(1, TimeUnit.SECONDS)).isTrue();
            assertThat(invocationCount).hasValue(1);
        } finally {
            scheduler.shutdown();
        }
    }
}
