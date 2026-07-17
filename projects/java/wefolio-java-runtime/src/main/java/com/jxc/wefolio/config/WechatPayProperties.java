package com.jxc.wefolio.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.URISyntaxException;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * 微信支付配置 — 从部署环境注入 APIv3 商户参数和通知地址。
 */
@Data
@Component
@ConfigurationProperties(prefix = "wechat.pay")
public class WechatPayProperties {

    /** 支付通知固定路径。 */
    private static final String NOTIFY_PATH = "/api/payment/wechat/recharge/notify";

    /** 微信支付公钥 ID 固定前缀。 */
    private static final String PUBLIC_KEY_ID_PREFIX = "PUB_KEY_ID_";

    /** 微信支付总开关。 */
    private boolean enabled = false;

    /** 微信支付商户号。 */
    private String merchantId;

    /** 商户 API 证书序列号。 */
    private String merchantSerialNumber;

    /** 商户 API 私钥 PEM 文件路径。 */
    private String privateKeyPath;

    /** APIv3 密钥。 */
    private String apiV3Key;

    /** 微信支付公钥 ID。 */
    private String publicKeyId;

    /** 微信支付公钥 PEM 文件路径。 */
    private String publicKeyPath;

    /** 微信支付通知完整地址。 */
    private String notifyUrl;

    /** 订单有效分钟数。 */
    private int orderExpireMinutes = 15;

    /**
     * 校验启用状态下的必填配置。
     */
    public void validateEnabledConfiguration() {
        if (!enabled) {
            return;
        }
        List<String> missingKeys = new ArrayList<>();
        requireValue(merchantId, "WECHAT_PAY_MERCHANT_ID", missingKeys);
        requireValue(merchantSerialNumber, "WECHAT_PAY_MERCHANT_SERIAL_NUMBER", missingKeys);
        requireValue(privateKeyPath, "WECHAT_PAY_PRIVATE_KEY_PATH", missingKeys);
        requireValue(apiV3Key, "WECHAT_PAY_API_V3_KEY", missingKeys);
        requireValue(publicKeyId, "WECHAT_PAY_PUBLIC_KEY_ID", missingKeys);
        requireValue(publicKeyPath, "WECHAT_PAY_PUBLIC_KEY_PATH", missingKeys);
        requireValue(notifyUrl, "WECHAT_PAY_NOTIFY_URL", missingKeys);
        if (!missingKeys.isEmpty()) {
            throw new IllegalArgumentException("微信支付缺少配置：" + String.join(", ", missingKeys));
        }
        if (orderExpireMinutes <= 0) {
            throw new IllegalArgumentException("WECHAT_PAY_ORDER_EXPIRE_MINUTES 必须大于 0");
        }
        if (!publicKeyId.strip().startsWith(PUBLIC_KEY_ID_PREFIX)) {
            throw new IllegalArgumentException("WECHAT_PAY_PUBLIC_KEY_ID 必须包含 PUB_KEY_ID_ 前缀");
        }
        validateAbsolutePath(privateKeyPath, "WECHAT_PAY_PRIVATE_KEY_PATH");
        validateAbsolutePath(publicKeyPath, "WECHAT_PAY_PUBLIC_KEY_PATH");
        validateNotifyUrl();
    }

    /**
     * 校验证书文件配置为绝对路径，错误信息不回显实际路径。
     *
     * @param value 文件路径
     * @param key 环境变量键
     */
    private void validateAbsolutePath(String value, String key) {
        try {
            if (!Path.of(value.strip()).isAbsolute()) {
                throw new IllegalArgumentException(key + " 必须是绝对路径");
            }
        } catch (InvalidPathException exception) {
            throw new IllegalArgumentException(key + " 不是有效路径");
        }
    }

    /**
     * 校验通知地址符合微信支付公网回调约束。
     */
    private void validateNotifyUrl() {
        try {
            URI uri = new URI(notifyUrl.strip());
            String host = uri.getHost();
            if (!"https".equalsIgnoreCase(uri.getScheme())
                    || host == null
                    || isUnsafeHost(host)
                    || !NOTIFY_PATH.equals(uri.getPath())
                    || uri.getRawQuery() != null
                    || uri.getRawFragment() != null
                    || uri.getUserInfo() != null) {
                throw invalidNotifyUrl();
            }
        } catch (URISyntaxException exception) {
            throw invalidNotifyUrl();
        }
    }

    /**
     * 判断是否为本机、内网域名或 IP 字面量。
     *
     * @param host 主机名
     * @return 是否不适合作为公网通知域名
     */
    private boolean isUnsafeHost(String host) {
        String normalizedHost = host.toLowerCase(Locale.ROOT);
        return "localhost".equals(normalizedHost)
                || normalizedHost.endsWith(".localhost")
                || normalizedHost.endsWith(".local")
                || normalizedHost.endsWith(".internal")
                || normalizedHost.matches("^[0-9.]+$")
                || normalizedHost.contains(":");
    }

    /**
     * 创建通知地址异常。
     *
     * @return 参数异常
     */
    private IllegalArgumentException invalidNotifyUrl() {
        return new IllegalArgumentException("WECHAT_PAY_NOTIFY_URL 必须是公网 HTTPS 完整通知地址");
    }

    /**
     * 收集空配置键。
     *
     * @param value 配置值
     * @param key 环境变量键
     * @param missingKeys 空配置键集合
     */
    private void requireValue(String value, String key, List<String> missingKeys) {
        if (value == null || value.isBlank()) {
            missingKeys.add(key);
        }
    }
}
