package com.jxc.wefolio.job.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;

/**
 * 反馈附件清理独立调度线程配置。
 */
@Configuration(proxyBeanMethods = false)
public class FeedbackUploadCleanupSchedulingConfig {

    /** 反馈附件清理专用调度器 Bean 名称。 */
    public static final String TASK_SCHEDULER_BEAN_NAME = "feedbackUploadCleanupTaskScheduler";

    /** 反馈附件清理调度线程名前缀。 */
    public static final String THREAD_NAME_PREFIX = "feedback-upload-cleanup-scheduler-";

    /**
     * 创建接入统一生命周期保护的单线程调度器。
     *
     * @param taskDecorator 统一调度生命周期装饰器
     * @return 反馈附件清理专用调度器
     */
    @Bean(name = TASK_SCHEDULER_BEAN_NAME)
    public ThreadPoolTaskScheduler feedbackUploadCleanupTaskScheduler(
            JobSchedulingTaskDecorator taskDecorator) {
        ThreadPoolTaskScheduler scheduler = new ThreadPoolTaskScheduler();
        scheduler.setPoolSize(1);
        scheduler.setThreadNamePrefix(THREAD_NAME_PREFIX);
        scheduler.setTaskDecorator(taskDecorator);
        return scheduler;
    }
}
