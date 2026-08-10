package com.jxc.wefolio.job.config;

import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 微信虚拟支付任务分发配置测试。
 */
class VirtualPaymentDispatchPropertiesTest {

    /** 默认必须关闭，避免 job 发布后与 runtime 扫描器未经门禁直接并行。 */
    @Test
    void defaultsShouldRemainDisabledAndBounded() {
        VirtualPaymentDispatchProperties properties = new VirtualPaymentDispatchProperties();

        assertThat(properties.isEnabled()).isFalse();
        assertThat(properties.getGiftScanInterval()).isEqualTo(Duration.ofSeconds(60));
        assertThat(properties.getDebitScanInterval()).isEqualTo(Duration.ofSeconds(30));
        assertThat(properties.getBatchSize()).isEqualTo(100);
        assertThat(properties.getWorkerCount()).isEqualTo(4);
        assertThat(properties.getRequestTimeout()).isEqualTo(Duration.ofSeconds(30));
    }

    /** 批大小、线程数和请求超时都必须为正数。 */
    @Test
    void validateShouldRejectNonPositiveBounds() {
        VirtualPaymentDispatchProperties properties = new VirtualPaymentDispatchProperties();
        properties.setWorkerCount(0);

        assertThatThrownBy(properties::validate)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("worker-count");
    }

    /** 赠送和待扣固定延迟都必须大于零。 */
    @Test
    void validateShouldRejectNonPositiveDispatchIntervals() {
        VirtualPaymentDispatchProperties giftProperties = new VirtualPaymentDispatchProperties();
        giftProperties.setGiftScanInterval(Duration.ZERO);

        assertThatThrownBy(giftProperties::validate)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("gift-scan-interval");

        VirtualPaymentDispatchProperties debitProperties = new VirtualPaymentDispatchProperties();
        debitProperties.setDebitScanInterval(Duration.ofSeconds(-1));

        assertThatThrownBy(debitProperties::validate)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("debit-scan-interval");
    }
}
