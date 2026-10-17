package com.jxc.wefolio.job.config;

import org.springframework.scheduling.Trigger;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;

import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.ScheduledFuture;

/** 在业务回调层控制准入，确保暂停期间调度器仍能续排。 */
public class JobLifecycleTaskScheduler extends ThreadPoolTaskScheduler {

    /** 仅包装业务任务，不包装执行器内部的续排回调。 */
    private final JobSchedulingTaskDecorator lifecycleDecorator;

    /** 创建使用统一生命周期门闩的调度器。 */
    public JobLifecycleTaskScheduler(JobSchedulingTaskDecorator lifecycleDecorator) {
        this.lifecycleDecorator = lifecycleDecorator;
    }

    /** 包装触发器调度的业务任务。 */
    @Override
    public ScheduledFuture<?> schedule(Runnable task, Trigger trigger) {
        return super.schedule(lifecycleDecorator.decorate(task), trigger);
    }

    /** 包装指定时刻执行的业务任务。 */
    @Override
    public ScheduledFuture<?> schedule(Runnable task, Instant startTime) {
        return super.schedule(lifecycleDecorator.decorate(task), startTime);
    }

    /** 包装指定起点的固定频率业务任务。 */
    @Override
    public ScheduledFuture<?> scheduleAtFixedRate(Runnable task, Instant startTime, Duration period) {
        return super.scheduleAtFixedRate(lifecycleDecorator.decorate(task), startTime, period);
    }

    /** 包装立即开始的固定频率业务任务。 */
    @Override
    public ScheduledFuture<?> scheduleAtFixedRate(Runnable task, Duration period) {
        return super.scheduleAtFixedRate(lifecycleDecorator.decorate(task), period);
    }

    /** 包装指定起点的固定间隔业务任务。 */
    @Override
    public ScheduledFuture<?> scheduleWithFixedDelay(Runnable task, Instant startTime, Duration delay) {
        return super.scheduleWithFixedDelay(lifecycleDecorator.decorate(task), startTime, delay);
    }

    /** 包装立即开始的固定间隔业务任务。 */
    @Override
    public ScheduledFuture<?> scheduleWithFixedDelay(Runnable task, Duration delay) {
        return super.scheduleWithFixedDelay(lifecycleDecorator.decorate(task), delay);
    }
}
