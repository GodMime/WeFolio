package com.jxc.wefolio.job.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.time.Clock;
import java.time.ZoneId;
import java.util.concurrent.ThreadPoolExecutor;

/**
 * 作品存储月度结算基础设施配置。
 */
@Configuration
public class WorkStorageBillingConfig {

    /**
     * 提供可替换时钟，生产环境固定使用配置时区。
     */
    @Bean
    public Clock workStorageBillingClock(WorkStorageBillingProperties properties) {
        return Clock.system(ZoneId.of(properties.getZone()));
    }

    /**
     * 单线程且不排队的专用执行器，本机运行态负责防止重复提交。
     */
    @Bean(name = "workStorageBillingExecutor")
    public ThreadPoolTaskExecutor workStorageBillingExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(1);
        executor.setMaxPoolSize(1);
        executor.setQueueCapacity(0);
        executor.setThreadNamePrefix("work-storage-billing-");
        executor.setRejectedExecutionHandler(new ThreadPoolExecutor.AbortPolicy());
        executor.initialize();
        return executor;
    }
}
