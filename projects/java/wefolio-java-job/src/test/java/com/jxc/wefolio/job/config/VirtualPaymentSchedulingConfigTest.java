package com.jxc.wefolio.job.config;

import com.jxc.wefolio.job.service.JobExecutionLifecycle;
import org.junit.jupiter.api.Test;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;

import java.time.Instant;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 微信虚拟支付调度线程隔离配置测试。
 */
class VirtualPaymentSchedulingConfigTest {

    /** 两条可能长时间等待 runtime 的调度必须使用独立双线程调度器。 */
    @Test
    void shouldCreateProtectedSchedulersWithExpectedThreadPools() throws InterruptedException {
        JobExecutionLifecycle lifecycle = new JobExecutionLifecycle();
        AtomicReference<CountDownLatch> executionLatch = new AtomicReference<>(new CountDownLatch(2));
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
        VirtualPaymentSchedulingConfig config = new VirtualPaymentSchedulingConfig();
        ThreadPoolTaskScheduler scheduler =
                config.virtualPaymentTaskScheduler(decorator);
        ThreadPoolTaskScheduler defaultScheduler =
                config.taskScheduler(decorator);
        scheduler.initialize();
        defaultScheduler.initialize();
        try {
            assertThat(scheduler.getScheduledThreadPoolExecutor().getCorePoolSize()).isEqualTo(2);
            assertThat(scheduler.getThreadNamePrefix()).isEqualTo("virtual-payment-scheduler-");
            assertThat(defaultScheduler.getScheduledThreadPoolExecutor().getCorePoolSize()).isEqualTo(1);
            assertThat(defaultScheduler.getThreadNamePrefix()).isEqualTo("scheduling-");

            AtomicInteger invocationCount = new AtomicInteger();
            defaultScheduler.schedule(invocationCount::incrementAndGet, Instant.now());
            scheduler.schedule(invocationCount::incrementAndGet, Instant.now());
            assertThat(executionLatch.get().await(1, TimeUnit.SECONDS)).isTrue();

            lifecycle.disable();
            executionLatch.set(new CountDownLatch(2));
            defaultScheduler.schedule(invocationCount::incrementAndGet, Instant.now());
            scheduler.schedule(invocationCount::incrementAndGet, Instant.now());
            assertThat(executionLatch.get().await(1, TimeUnit.SECONDS)).isTrue();

            assertThat(invocationCount).hasValue(2);
        } finally {
            scheduler.shutdown();
            defaultScheduler.shutdown();
        }
    }
}
