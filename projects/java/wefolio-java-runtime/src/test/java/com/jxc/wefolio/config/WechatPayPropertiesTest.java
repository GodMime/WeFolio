package com.jxc.wefolio.config;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

/**
 * 微信支付配置测试。
 */
class WechatPayPropertiesTest {

    /** 生产通知地址。 */
    private static final String PRODUCTION_NOTIFY_URL =
            "https://api.we-folio.dingchenyong.top/api/payment/wechat/recharge/notify";

    @Test
    void defaultsShouldKeepPaymentDisabledAndUseFifteenMinuteExpiry() {
        WechatPayProperties properties = new WechatPayProperties();

        assertThat(properties.isEnabled()).isFalse();
        assertThat(properties.getOrderExpireMinutes()).isEqualTo(15);
    }

    @Test
    void enabledConfigurationShouldAcceptCompleteProductionValues() {
        WechatPayProperties properties = completeProperties();

        properties.validateEnabledConfiguration();
    }

    @Test
    void enabledConfigurationShouldRejectMissingRequiredKeysWithoutEchoingSecrets() {
        WechatPayProperties properties = completeProperties();
        properties.setApiV3Key(" ");

        assertThatIllegalArgumentException()
                .isThrownBy(properties::validateEnabledConfiguration)
                .withMessageContaining("WECHAT_PAY_API_V3_KEY")
                .withMessageNotContaining("api-v3-secret");
    }

    @Test
    void enabledConfigurationShouldRequireWechatPublicKeyIdAndAbsolutePemPaths() {
        WechatPayProperties properties = completeProperties();
        properties.setPublicKeyId("1234567890");

        assertThatIllegalArgumentException()
                .isThrownBy(properties::validateEnabledConfiguration)
                .withMessageContaining("WECHAT_PAY_PUBLIC_KEY_ID");

        properties = completeProperties();
        properties.setPrivateKeyPath("certs/apiclient_key.pem");
        WechatPayProperties relativePrivateKeyProperties = properties;
        assertThatIllegalArgumentException()
                .isThrownBy(relativePrivateKeyProperties::validateEnabledConfiguration)
                .withMessageContaining("WECHAT_PAY_PRIVATE_KEY_PATH");

        properties = completeProperties();
        properties.setPublicKeyPath("certs/pub_key.pem");
        WechatPayProperties relativePublicKeyProperties = properties;
        assertThatIllegalArgumentException()
                .isThrownBy(relativePublicKeyProperties::validateEnabledConfiguration)
                .withMessageContaining("WECHAT_PAY_PUBLIC_KEY_PATH");
    }

    @Test
    void notifyUrlShouldRejectUnsafeOrUnexpectedAddresses() {
        WechatPayProperties properties = completeProperties();

        for (String invalidUrl : new String[]{
                "http://api.we-folio.dingchenyong.top/api/payment/wechat/recharge/notify",
                "https://127.0.0.1/api/payment/wechat/recharge/notify",
                "https://localhost/api/payment/wechat/recharge/notify",
                "https://api.we-folio.dingchenyong.top/api/payment/wechat/other",
                PRODUCTION_NOTIFY_URL + "?source=test",
                PRODUCTION_NOTIFY_URL + "#fragment"
        }) {
            properties.setNotifyUrl(invalidUrl);
            assertThatIllegalArgumentException()
                    .as("应拒绝通知地址 %s", invalidUrl)
                    .isThrownBy(properties::validateEnabledConfiguration);
        }
    }

    @Test
    void applicationYamlShouldBindAllWechatPayEnvironmentVariables() throws IOException {
        String yaml = Files.readString(Path.of("src/main/resources/application.yml"));

        assertThat(yaml)
                .contains("enabled: ${WECHAT_PAY_ENABLED:false}")
                .contains("merchant-id: ${WECHAT_PAY_MERCHANT_ID:}")
                .contains("merchant-serial-number: ${WECHAT_PAY_MERCHANT_SERIAL_NUMBER:}")
                .contains("private-key-path: ${WECHAT_PAY_PRIVATE_KEY_PATH:}")
                .contains("api-v3-key: ${WECHAT_PAY_API_V3_KEY:}")
                .contains("public-key-id: ${WECHAT_PAY_PUBLIC_KEY_ID:}")
                .contains("public-key-path: ${WECHAT_PAY_PUBLIC_KEY_PATH:}")
                .contains("notify-url: ${WECHAT_PAY_NOTIFY_URL:")
                .contains("order-expire-minutes: ${WECHAT_PAY_ORDER_EXPIRE_MINUTES:15}");
    }

    /**
     * 创建完整的启用配置。
     *
     * @return 微信支付配置
     */
    private WechatPayProperties completeProperties() {
        WechatPayProperties properties = new WechatPayProperties();
        properties.setEnabled(true);
        properties.setMerchantId("1900000001");
        properties.setMerchantSerialNumber("SERIAL123");
        properties.setPrivateKeyPath("/secure/apiclient_key.pem");
        properties.setApiV3Key("api-v3-secret");
        properties.setPublicKeyId("PUB_KEY_ID_123");
        properties.setPublicKeyPath("/secure/pub_key.pem");
        properties.setNotifyUrl(PRODUCTION_NOTIFY_URL);
        return properties;
    }
}
