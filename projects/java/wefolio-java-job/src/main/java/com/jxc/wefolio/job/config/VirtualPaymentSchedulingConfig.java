package com.jxc.wefolio.job.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;

/**
 * 微信虚拟支付调度线程隔离配置。
 */
@Configuration
public class VirtualPaymentSchedulingConfig {

    /** Spring 未指定 scheduler 的定时任务使用的默认 Bean 名称。 */
    public static final String DEFAULT_TASK_SCHEDULER_BEAN_NAME = "taskScheduler";

    /** 虚拟支付专用调度器 Bean 名称。 */
    public static final String TASK_SCHEDULER_BEAN_NAME = "virtualPaymentTaskScheduler";

    /**
     * 保持其他 job 原有单线程调度语义，避免落入虚拟支付专用线程池。
     *
     * @param taskDecorator 统一调度生命周期装饰器
     * @return 默认单线程调度器
     */
    @Bean(name = DEFAULT_TASK_SCHEDULER_BEAN_NAME)
    public ThreadPoolTaskScheduler taskScheduler(JobSchedulingTaskDecorator taskDecorator) {
        ThreadPoolTaskScheduler scheduler = new ThreadPoolTaskScheduler();
        scheduler.setPoolSize(1);
        scheduler.setThreadNamePrefix("scheduling-");
        scheduler.setTaskDecorator(taskDecorator);
        return scheduler;
    }

    /**
     * 两条分发调度各占一个专用线程，不阻塞 job 工程的其他定时任务。
     *
     * @param taskDecorator 统一调度生命周期装饰器
     * @return 虚拟支付双线程调度器
     */
    @Bean(name = TASK_SCHEDULER_BEAN_NAME)
    public ThreadPoolTaskScheduler virtualPaymentTaskScheduler(JobSchedulingTaskDecorator taskDecorator) {
        ThreadPoolTaskScheduler scheduler = new ThreadPoolTaskScheduler();
        scheduler.setPoolSize(2);
        scheduler.setThreadNamePrefix("virtual-payment-scheduler-");
        scheduler.setTaskDecorator(taskDecorator);
        return scheduler;
    }
}
