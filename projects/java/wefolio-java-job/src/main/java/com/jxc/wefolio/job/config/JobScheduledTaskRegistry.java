package com.jxc.wefolio.job.config;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.ScheduledAnnotationBeanPostProcessor;
import org.springframework.scheduling.annotation.SchedulingConfigurer;
import org.springframework.scheduling.config.ScheduledTask;
import org.springframework.scheduling.config.ScheduledTaskRegistrar;

import java.util.Set;

/**
 * 保存 Spring 定时任务注册器，并提供全部任务的非中断取消能力。
 */
@Configuration(proxyBeanMethods = false)
public class JobScheduledTaskRegistry implements SchedulingConfigurer {

    /** 调度基础设施尚未完成初始化时使用的固定异常消息。 */
    private static final String REGISTRAR_NOT_READY_MESSAGE = "Spring 定时任务注册器尚未初始化";

    /** 延迟获取持有全部注解任务的 Spring 调度处理器。 */
    private final ObjectProvider<ScheduledAnnotationBeanPostProcessor> scheduledProcessorProvider;

    /** Spring 回调提供的实际定时任务注册器。 */
    private volatile ScheduledTaskRegistrar scheduledTaskRegistrar;

    /**
     * 创建定时任务注册访问器。
     *
     * @param scheduledProcessorProvider 延迟取得注解调度处理器，避免初始化阶段形成循环依赖
     */
    public JobScheduledTaskRegistry(
            ObjectProvider<ScheduledAnnotationBeanPostProcessor> scheduledProcessorProvider) {
        this.scheduledProcessorProvider = scheduledProcessorProvider;
    }

    /**
     * 保存实际承载全部 {@code @Scheduled} 任务的注册器。
     *
     * @param taskRegistrar Spring 定时任务注册器
     */
    @Override
    public void configureTasks(ScheduledTaskRegistrar taskRegistrar) {
        this.scheduledTaskRegistrar = taskRegistrar;
    }

    /**
     * 返回当前注册任务的稳定快照。
     *
     * @return 已注册的全部定时任务
     */
    public Set<ScheduledTask> scheduledTasks() {
        ScheduledAnnotationBeanPostProcessor scheduledProcessor = scheduledProcessorProvider.getIfAvailable();
        if (scheduledProcessor != null) {
            return Set.copyOf(scheduledProcessor.getScheduledTasks());
        }
        ScheduledTaskRegistrar registrar = scheduledTaskRegistrar;
        if (registrar == null) {
            throw new IllegalStateException(REGISTRAR_NOT_READY_MESSAGE);
        }
        return Set.copyOf(registrar.getScheduledTasks());
    }

    /**
     * 非中断取消全部已注册任务，允许已经运行的回调自然结束。
     *
     * @return 本次遍历到的注册任务数
     */
    public int cancelScheduledTasks() {
        Set<ScheduledTask> tasks = scheduledTasks();
        tasks.forEach(task -> task.cancel(false));
        return tasks.size();
    }
}
