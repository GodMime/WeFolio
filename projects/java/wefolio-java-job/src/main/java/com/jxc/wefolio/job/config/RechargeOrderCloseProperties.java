package com.jxc.wefolio.job.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * 过期充值订单关闭任务配置。
 */
@Data
@Component
@ConfigurationProperties(prefix = "recharge-order-close")
public class RechargeOrderCloseProperties {

    /** 是否启用任务 */
    private boolean enabled = true;

    /** 调度表达式，默认每分钟第 30 秒执行 */
    private String cron = "30 * * * * ?";

    /** 调度与过期判断时区 */
    private String zone = "Asia/Shanghai";

    /** 单批最多关闭的订单数 */
    private int batchSize = 200;

    /** 单轮最多执行的批次数 */
    private int maxBatches = 10;
}
