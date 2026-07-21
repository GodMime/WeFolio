package com.jxc.wefolio.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.time.Duration;

/**
 * 微信小程序配置 — 从环境变量注入 AppID 与 AppSecret
 */
@Data
@Component
@ConfigurationProperties(prefix = "wechat.miniapp")
public class WechatMiniappProperties {

    /** 默认微信连接超时。 */
    private static final Duration DEFAULT_CONNECT_TIMEOUT = Duration.ofSeconds(1);

    /** 默认微信读取超时。 */
    private static final Duration DEFAULT_READ_TIMEOUT = Duration.ofSeconds(3);

    /** 微信小程序 AppID */
    private String appId;

    /** 微信小程序 AppSecret */
    private String appSecret;

    /** 是否打印微信接口详细日志；敏感字段仍会基础脱敏，生产环境建议关闭 */
    private boolean logVerbose = false;

    /** 微信 HTTP 连接超时。 */
    private Duration connectTimeout = DEFAULT_CONNECT_TIMEOUT;

    /** 微信 HTTP 响应读取超时。 */
    private Duration readTimeout = DEFAULT_READ_TIMEOUT;

    /** 设置微信 HTTP 连接超时。 */
    public void setConnectTimeout(Duration connectTimeout) {
        this.connectTimeout = requirePositive(connectTimeout, "微信连接超时必须大于 0");
    }

    /** 设置微信 HTTP 响应读取超时。 */
    public void setReadTimeout(Duration readTimeout) {
        this.readTimeout = requirePositive(readTimeout, "微信读取超时必须大于 0");
    }

    /** 校验超时必须大于 0。 */
    private Duration requirePositive(Duration value, String message) {
        if (value == null || value.isZero() || value.isNegative()) {
            throw new IllegalArgumentException(message);
        }
        return value;
    }
}
