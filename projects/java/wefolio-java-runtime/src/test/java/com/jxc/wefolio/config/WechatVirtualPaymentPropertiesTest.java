package com.jxc.wefolio.config;

import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 微信虚拟支付配置测试。
 */
class WechatVirtualPaymentPropertiesTest {

    /** 启用时租约至少覆盖两倍单次远端请求超时。 */
    @Test
    void enabledConfigurationShouldRejectLeaseShorterThanTwiceRequestTimeout() {
        WechatVirtualPaymentProperties properties = enabledProperties();
        properties.setRequestTimeout(Duration.ofSeconds(5));
        properties.getSettlement().setLeaseDuration(Duration.ofSeconds(9));

        assertThatThrownBy(properties::validateWhenEnabled)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("租约时长")
                .hasMessageContaining("请求超时");
    }

    /** 等于两倍请求超时时属于合法边界。 */
    @Test
    void enabledConfigurationShouldAcceptLeaseEqualToTwiceRequestTimeout() {
        WechatVirtualPaymentProperties properties = enabledProperties();
        properties.setRequestTimeout(Duration.ofSeconds(5));
        properties.getSettlement().setLeaseDuration(Duration.ofSeconds(10));

        assertThatCode(properties::validateWhenEnabled).doesNotThrowAnyException();
    }

    /** 构造必填敏感配置齐全的启用配置。 */
    private WechatVirtualPaymentProperties enabledProperties() {
        WechatVirtualPaymentProperties properties = new WechatVirtualPaymentProperties();
        properties.setEnabled(true);
        properties.setOfferId("offer-id");
        properties.setAppKey("app-key");
        properties.setSessionEncryptionSecret("session-secret");
        properties.setMessageToken("message-token");
        properties.setMessageEncodingAesKey("encoding-aes-key");
        return properties;
    }
}
