package com.jxc.wefolio.service;

import com.jxc.wefolio.config.AdminPointProperties;
import com.jxc.wefolio.exception.BusinessException;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

/**
 * 后台积分密钥固定时间校验器。
 */
@Component
@RequiredArgsConstructor
public class AdminPointSecretValidator {

    private final AdminPointProperties properties;

    /** 校验请求密钥，不记录任何密钥内容。 */
    public void validate(String suppliedSecret) {
        String configuredSecret = properties.getSecret();
        if (configuredSecret == null || configuredSecret.isBlank() || suppliedSecret == null
                || !MessageDigest.isEqual(
                        configuredSecret.getBytes(StandardCharsets.UTF_8),
                        suppliedSecret.getBytes(StandardCharsets.UTF_8))) {
            throw new BusinessException("后台积分密钥无效");
        }
    }
}
