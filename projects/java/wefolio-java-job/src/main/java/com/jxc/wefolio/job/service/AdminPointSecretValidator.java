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

    /** 对外统一失败消息，禁止回显密钥 */
    private static final String INVALID_SECRET_MESSAGE = "后台积分密钥无效";

    /** 后台积分配置 */
    private final AdminPointProperties properties;

    /**
     * 校验请求头密钥。
     *
     * @param requestedSecret 请求密钥
     */
    public void validate(String requestedSecret) {
        String configuredSecret = properties.getSecret();
        if (!hasText(configuredSecret) || !hasText(requestedSecret)
                || !MessageDigest.isEqual(
                        configuredSecret.getBytes(StandardCharsets.UTF_8),
                        requestedSecret.getBytes(StandardCharsets.UTF_8))) {
            throw new IllegalArgumentException(INVALID_SECRET_MESSAGE);
        }
    }

    private boolean hasText(String value) {
        return value != null && !value.isBlank();
    }
}
