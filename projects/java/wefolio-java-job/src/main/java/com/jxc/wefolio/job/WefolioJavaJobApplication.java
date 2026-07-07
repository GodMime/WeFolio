package com.jxc.wefolio.job;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * WeFolio 后台任务应用启动入口。
 *
 * <p>该工程用于承载独立部署的后台定时任务实例，避免与维护者端 REST API 服务共享实例数量。</p>
 */
@EnableScheduling
@SpringBootApplication
public class WefolioJavaJobApplication {

    /**
     * 启动后台任务应用。
     *
     * @param args 命令行参数
     */
    public static void main(String[] args) {
        SpringApplication.run(WefolioJavaJobApplication.class, args);
    }
}
