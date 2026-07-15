package com.jxc.wefolio.job.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * 作品存储月度结算配置。
 */
@Data
@Component
@ConfigurationProperties(prefix = "work-storage-billing")
public class WorkStorageBillingProperties {

    /** 是否启用定时调度 */
    private boolean enabled = true;

    /** 每月调度表达式 */
    private String cron = "0 0 2 1 * ?";

    /** 调度和账期时区 */
    private String zone = "Asia/Shanghai";

    /** 用户游标查询批大小 */
    private int batchSize = 500;
}
