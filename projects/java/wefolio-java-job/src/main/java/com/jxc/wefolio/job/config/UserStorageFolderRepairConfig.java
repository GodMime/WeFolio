package com.jxc.wefolio.job.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.ThreadPoolExecutor;

/**
 * 历史用户 COS 目录修复执行器配置。
 */
@Configuration
public class UserStorageFolderRepairConfig {

    /**
     * 创建单线程且零队列的专用执行器。
     *
     * @return 目录修复执行器
     */
    @Bean(name = "userStorageFolderRepairExecutor")
    public ThreadPoolTaskExecutor userStorageFolderRepairExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(1);
        executor.setMaxPoolSize(1);
        executor.setQueueCapacity(0);
        executor.setThreadNamePrefix("user-storage-folder-repair-");
        executor.setRejectedExecutionHandler(new ThreadPoolExecutor.AbortPolicy());
        executor.initialize();
        return executor;
    }
}
