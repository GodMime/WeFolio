package com.jxc.wefolio.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.time.Duration;

/** 作品最终轮人工审核配置，承载专用飞书机器人和回传基础地址。 */
@Getter
@Setter
@Component
@ConfigurationProperties(prefix = "wefolio.work-audit.manual-review")
public class WorkManualAuditProperties {

    /** 默认飞书连接超时。 */
    private static final Duration DEFAULT_CONNECT_TIMEOUT = Duration.ofSeconds(2);

    /** 默认飞书读取超时。 */
    private static final Duration DEFAULT_READ_TIMEOUT = Duration.ofSeconds(3);

    /** 默认人工审核回传 API 基础地址。 */
    private static final String DEFAULT_REVIEW_API_BASE_URL =
            "https://api.we-folio.dingchenyong.top";

    /** 作品人工审核飞书群自定义机器人 Webhook，禁止写入日志。 */
    private String webhookUrl;

    /** 作品人工审核飞书群自定义机器人签名密钥，禁止写入日志。 */
    private String webhookSecret;

    /** 审核回传 curl 使用的 Runtime 公开基础地址。 */
    private String reviewApiBaseUrl = DEFAULT_REVIEW_API_BASE_URL;

    /** 飞书 HTTP 连接超时。 */
    private Duration connectTimeout = DEFAULT_CONNECT_TIMEOUT;

    /** 飞书 HTTP 响应读取超时。 */
    private Duration readTimeout = DEFAULT_READ_TIMEOUT;

    /** 设置飞书 HTTP 连接超时。 */
    public void setConnectTimeout(Duration connectTimeout) {
        this.connectTimeout = requirePositive(connectTimeout, "作品审核飞书连接超时必须大于 0");
    }

    /** 设置飞书 HTTP 响应读取超时。 */
    public void setReadTimeout(Duration readTimeout) {
        this.readTimeout = requirePositive(readTimeout, "作品审核飞书读取超时必须大于 0");
    }

    /** 校验超时配置必须为正数。 */
    private Duration requirePositive(Duration value, String message) {
        if (value == null || value.isZero() || value.isNegative()) {
            throw new IllegalArgumentException(message);
        }
        return value;
    }
}
