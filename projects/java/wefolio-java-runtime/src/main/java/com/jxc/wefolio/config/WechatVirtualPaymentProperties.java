package com.jxc.wefolio.config;

import jakarta.annotation.PostConstruct;
import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.time.Duration;

/**
 * 微信虚拟支付配置 — 正式环境参数、会话加密和持久任务执行策略。
 */
@Data
@Component
@ConfigurationProperties(prefix = "wechat.virtual-payment")
public class WechatVirtualPaymentProperties {

    /** 是否启用微信虚拟支付。 */
    private boolean enabled;

    /** 微信虚拟支付 offer_id。 */
    private String offerId;

    /** 微信虚拟支付 AppKey。 */
    private String appKey;

    /** 维护者 session_key 独立 AES-GCM 密钥，使用 Base64 编码。 */
    private String sessionEncryptionSecret;

    /** 微信消息回调 Token。 */
    private String messageToken;

    /** 微信消息回调 EncodingAESKey。 */
    private String messageEncodingAesKey;

    /** 微信远端请求超时。 */
    private Duration requestTimeout = Duration.ofSeconds(5L);

    /** 小程序前台会话检查间隔。 */
    private Duration sessionCheckInterval = Duration.ofSeconds(300L);

    /** 待结算任务配置。 */
    private Settlement settlement = new Settlement();

    /** 启用时校验所有敏感必填配置，不允许静默使用空值。 */
    @PostConstruct
    public void validateWhenEnabled() {
        if (!enabled) {
            return;
        }
        requireText(offerId, "WECHAT_VIRTUAL_PAYMENT_OFFER_ID");
        requireText(appKey, "WECHAT_VIRTUAL_PAYMENT_APP_KEY");
        requireText(sessionEncryptionSecret, "WECHAT_SESSION_ENCRYPTION_SECRET");
        requireText(messageToken, "WECHAT_MESSAGE_TOKEN");
        requireText(messageEncodingAesKey, "WECHAT_MESSAGE_ENCODING_AES_KEY");
    }

    /** 获取会话检查间隔秒数。 */
    public long sessionCheckIntervalSeconds() {
        return sessionCheckInterval == null ? 300L : Math.max(1L, sessionCheckInterval.toSeconds());
    }

    /** 校验单个必填配置。 */
    private void requireText(String value, String environmentName) {
        if (value == null || value.isBlank()) {
            throw new IllegalStateException("微信虚拟支付已启用但缺少环境变量：" + environmentName);
        }
    }

    /** 待结算任务配置。 */
    @Data
    public static class Settlement {

        /** 本地消费提交后的最小待处理延时。 */
        private Duration delay = Duration.ofSeconds(10L);

        /** 首次失败后的最大自动重试次数。 */
        private int maxRetries = 3;

        /** 数据库恢复扫描固定延迟。 */
        private Duration scanInterval = Duration.ofSeconds(10L);

        /** 单次任务领取租约时长。 */
        private Duration leaseDuration = Duration.ofSeconds(60L);
    }
}
