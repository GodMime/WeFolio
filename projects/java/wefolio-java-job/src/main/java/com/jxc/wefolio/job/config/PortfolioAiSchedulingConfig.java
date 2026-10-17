package com.jxc.wefolio.job.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;


/** AI 扫描与清理使用独立调度线程，不被其它长任务阻塞。 */
@Configuration
public class PortfolioAiSchedulingConfig {
    /** AI 专用调度器名。 */
    public static final String SCHEDULER = "portfolioAiTaskScheduler";

    /** 创建带部署排空装饰器的双线程调度器。 */
    @Bean(name = SCHEDULER)
    public ThreadPoolTaskScheduler portfolioAiScheduler(JobSchedulingTaskDecorator decorator) {
        ThreadPoolTaskScheduler scheduler = new JobLifecycleTaskScheduler(decorator);
        scheduler.setPoolSize(2); scheduler.setThreadNamePrefix("portfolio-ai-scheduler-"); return scheduler;
    }
}
