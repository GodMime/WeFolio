package com.jxc.wefolio.config;

import com.jxc.wefolio.exception.BusinessException;
import com.jxc.wefolio.message.RechargeMessage;
import com.jxc.wefolio.service.payment.SdkWechatPayClient;
import com.jxc.wefolio.service.payment.WechatPayClient;
import com.wechat.pay.java.core.Config;
import com.wechat.pay.java.core.RSAPublicKeyConfig;
import com.wechat.pay.java.core.notification.NotificationParser;
import com.wechat.pay.java.service.payments.jsapi.JsapiServiceExtension;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 微信支付官方 SDK 客户端配置。
 *
 * @deprecated 普通微信支付已由微信虚拟支付替代，待删除官方 SDK Bean
 */
@Configuration
@Deprecated(forRemoval = true)
public class WechatPayConfiguration {

    /**
     * 创建微信支付边界客户端；支付关闭时不读取证书文件。
     *
     * @param properties 微信支付配置
     * @return 微信支付客户端
     * @deprecated 仅保留普通微信支付兼容代码，后续删除
     */
    @Bean
    @Deprecated(forRemoval = true)
    public WechatPayClient wechatPayClient(WechatPayProperties properties) {
        if (!properties.isEnabled()) {
            return new DisabledWechatPayClient();
        }
        properties.validateEnabledConfiguration();
        Config config = new RSAPublicKeyConfig.Builder()
                .merchantId(properties.getMerchantId())
                .privateKeyFromPath(properties.getPrivateKeyPath())
                .publicKeyFromPath(properties.getPublicKeyPath())
                .publicKeyId(properties.getPublicKeyId())
                .merchantSerialNumber(properties.getMerchantSerialNumber())
                .apiV3Key(properties.getApiV3Key())
                .build();
        JsapiServiceExtension service = new JsapiServiceExtension.Builder()
                .config(config)
                .build();
        NotificationParser parser = new NotificationParser(
                (com.wechat.pay.java.core.notification.NotificationConfig) config);
        return new SdkWechatPayClient(service, parser, properties.getMerchantId());
    }

    /**
     * 支付关闭时的安全客户端，不执行任何远端操作。
     */
    private static final class DisabledWechatPayClient implements WechatPayClient {

        @Override
        @Deprecated(forRemoval = true)
        public PrepayResult prepay(PrepayCommand command) {
            throw unavailable();
        }

        @Override
        @Deprecated(forRemoval = true)
        public Transaction queryByMerchantOrderNo(String merchantOrderNo) {
            throw unavailable();
        }

        @Override
        @Deprecated(forRemoval = true)
        public Transaction parseNotification(NotificationRequest request) {
            throw unavailable();
        }

        /**
         * 创建支付未配置异常。
         */
        private BusinessException unavailable() {
            return new BusinessException(RechargeMessage.PAYMENT_NOT_CONFIGURED_MESSAGE);
        }
    }
}
