package com.jxc.wefolio.job.config;

import com.jxc.wefolio.job.service.JobExecutionLifecycle;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 调度回调生命周期装饰器测试。
 */
class JobSchedulingTaskDecoratorTest {

    @Test
    void shouldAdmitCallbackBeforeDisableAndSkipItAfterDisable() {
        JobExecutionLifecycle lifecycle = new JobExecutionLifecycle();
        JobSchedulingTaskDecorator decorator = new JobSchedulingTaskDecorator(lifecycle);
        AtomicInteger invocationCount = new AtomicInteger();
        Runnable decorated = decorator.decorate(invocationCount::incrementAndGet);

        decorated.run();
        lifecycle.pause(Duration.ofMinutes(10));
        decorated.run();

        assertThat(invocationCount).hasValue(1);
        assertThat(lifecycle.snapshot().status()).isEqualTo(JobExecutionLifecycle.Status.DISABLED);
    }

}
