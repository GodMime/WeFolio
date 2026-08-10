package com.jxc.wefolio.job.config;

import jakarta.annotation.PostConstruct;
import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.time.Duration;

/**
 * 微信虚拟支付候选任务分发配置。
 */
@Data
@Component
@ConfigurationProperties(prefix = "virtual-payment-dispatch")
public class VirtualPaymentDispatchProperties {

    /** 是否启用虚拟支付任务分发，扩展阶段默认关闭。 */
    private boolean enabled;

    /** 赠送订单上一轮完成后的固定扫描延迟。 */
    private Duration giftScanInterval = Duration.ofSeconds(60);

    /** 待扣任务上一轮完成后的固定扫描延迟。 */
    private Duration debitScanInterval = Duration.ofSeconds(30);

    /** 每类候选单轮最多读取数量。 */
    private int batchSize = 100;

    /** 单轮分发固定工作线程数。 */
    private int workerCount = 4;

    /** 单次 runtime 请求连接和读取超时。 */
    private Duration requestTimeout = Duration.ofSeconds(30);

    /** 校验所有有界执行参数。 */
    @PostConstruct
    public void validate() {
        if (giftScanInterval == null || giftScanInterval.isZero() || giftScanInterval.isNegative()) {
            throw new IllegalStateException("virtual-payment-dispatch.gift-scan-interval 必须大于零");
        }
        if (debitScanInterval == null || debitScanInterval.isZero() || debitScanInterval.isNegative()) {
            throw new IllegalStateException("virtual-payment-dispatch.debit-scan-interval 必须大于零");
        }
        if (batchSize <= 0) {
            throw new IllegalStateException("virtual-payment-dispatch.batch-size 必须大于零");
        }
        if (workerCount <= 0) {
            throw new IllegalStateException("virtual-payment-dispatch.worker-count 必须大于零");
        }
        if (requestTimeout == null || requestTimeout.isZero() || requestTimeout.isNegative()) {
            throw new IllegalStateException("virtual-payment-dispatch.request-timeout 必须大于零");
        }
    }
}
