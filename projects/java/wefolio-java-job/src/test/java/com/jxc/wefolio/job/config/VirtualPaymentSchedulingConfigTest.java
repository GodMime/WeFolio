package com.jxc.wefolio.job.config;

import org.junit.jupiter.api.Test;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 微信虚拟支付调度线程隔离配置测试。
 */
class VirtualPaymentSchedulingConfigTest {

    /** 两条可能长时间等待 runtime 的调度必须使用独立双线程调度器。 */
    @Test
    void shouldCreateDedicatedTwoThreadScheduler() {
        ThreadPoolTaskScheduler scheduler =
                new VirtualPaymentSchedulingConfig().virtualPaymentTaskScheduler();
        ThreadPoolTaskScheduler defaultScheduler =
                new VirtualPaymentSchedulingConfig().taskScheduler();
        scheduler.initialize();
        defaultScheduler.initialize();
        try {
            assertThat(scheduler.getScheduledThreadPoolExecutor().getCorePoolSize()).isEqualTo(2);
            assertThat(scheduler.getThreadNamePrefix()).isEqualTo("virtual-payment-scheduler-");
            assertThat(defaultScheduler.getScheduledThreadPoolExecutor().getCorePoolSize()).isEqualTo(1);
            assertThat(defaultScheduler.getThreadNamePrefix()).isEqualTo("scheduling-");
        } finally {
            scheduler.shutdown();
            defaultScheduler.shutdown();
        }
    }
}
