package com.jxc.wefolio.job.service;

import com.jxc.wefolio.job.config.AdminPointProperties;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

/**
 * 后台积分密钥校验器。
 */
@Service
@RequiredArgsConstructor
public class AdminPointSecretValidator {

    /** 后台积分配置 */
    private final AdminPointProperties properties;

    /**
     * 判断请求头密钥是否有效。
     *
     * @param requestedSecret 请求密钥
     * @return 是否与服务端配置密钥一致
     */
    public boolean isValid(String requestedSecret) {
        String configuredSecret = properties.getSecret();
        if (!hasText(configuredSecret) || !hasText(requestedSecret)
                || !MessageDigest.isEqual(
                        configuredSecret.getBytes(StandardCharsets.UTF_8),
                        requestedSecret.getBytes(StandardCharsets.UTF_8))) {
            return false;
        }
        return true;
    }

    /**
     * 判断字符串是否包含非空白字符。
     *
     * @param value 待判断字符串
     * @return 是否包含非空白字符
     */
    private boolean hasText(String value) {
        return value != null && !value.isBlank();
    }
}
