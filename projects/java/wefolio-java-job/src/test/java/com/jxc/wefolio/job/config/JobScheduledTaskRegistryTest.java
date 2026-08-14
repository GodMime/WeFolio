package com.jxc.wefolio.job.config;

import org.junit.jupiter.api.Test;
import org.springframework.scheduling.config.FixedDelayTask;
import org.springframework.scheduling.config.ScheduledTaskRegistrar;
import org.springframework.beans.factory.support.StaticListableBeanFactory;
import org.springframework.scheduling.annotation.ScheduledAnnotationBeanPostProcessor;

import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Spring 定时任务注册器访问与取消测试。
 */
class JobScheduledTaskRegistryTest {

    @Test
    void shouldCancelRegisteredTasksWithoutInterruptingActiveCallback() throws InterruptedException {
        StaticListableBeanFactory beanFactory = new StaticListableBeanFactory();
        JobScheduledTaskRegistry registry = new JobScheduledTaskRegistry(
                beanFactory.getBeanProvider(ScheduledAnnotationBeanPostProcessor.class));
        ScheduledTaskRegistrar registrar = new ScheduledTaskRegistrar();
        ScheduledThreadPoolExecutor executor = new ScheduledThreadPoolExecutor(1);
        CountDownLatch callbackStarted = new CountDownLatch(1);
        CountDownLatch callbackCanFinish = new CountDownLatch(1);
        AtomicBoolean interrupted = new AtomicBoolean();
        AtomicInteger invocationCount = new AtomicInteger();
        registrar.setScheduler(executor);
        registrar.addFixedDelayTask(new FixedDelayTask(() -> {
            invocationCount.incrementAndGet();
            callbackStarted.countDown();
            try {
                callbackCanFinish.await();
            } catch (InterruptedException exception) {
                interrupted.set(true);
                Thread.currentThread().interrupt();
            }
        }, Duration.ofMillis(5), Duration.ZERO));
        registry.configureTasks(registrar);
        registrar.afterPropertiesSet();

        try {
            assertThat(callbackStarted.await(1, TimeUnit.SECONDS)).isTrue();

            assertThat(registry.cancelScheduledTasks()).isEqualTo(1);
            assertThat(registry.cancelScheduledTasks()).isEqualTo(1);
            assertThat(interrupted).isFalse();

            callbackCanFinish.countDown();
            assertThat(executor.getQueue().isEmpty()).isTrue();
            assertThat(invocationCount).hasValue(1);
        } finally {
            callbackCanFinish.countDown();
            registrar.destroy();
            executor.shutdownNow();
        }
    }
}
