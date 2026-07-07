package com.jxc.wefolio.job.service;

import com.jxc.wefolio.job.config.CosProperties;

import java.util.regex.Pattern;

/**
 * 审核日志脱敏工具。
 */
final class AuditLogSanitizer {

    /** 敏感值脱敏占位 */
    private static final String REDACTED_VALUE = "***REDACTED***";

    /** 腾讯云签名 URL 中的敏感查询参数 */
    private static final Pattern TENCENT_SIGN_QUERY_PARAM_PATTERN =
            Pattern.compile("(?i)(q-ak|q-signature|x-cos-security-token)=([^&\"\\\\]+)");

    private AuditLogSanitizer() {
    }

    /**
     * 脱敏审核日志载荷。
     *
     * @param payload 原始载荷
     * @param cosProperties COS 配置
     * @return 脱敏后的载荷
     */
    static String sanitize(String payload, CosProperties cosProperties) {
        if (payload == null || payload.isBlank()) {
            return payload;
        }

        String sanitized = payload;
        if (cosProperties != null) {
            sanitized = maskLiteral(sanitized, cosProperties.getSecretId());
            sanitized = maskLiteral(sanitized, cosProperties.getSecretKey());
        }
        return TENCENT_SIGN_QUERY_PARAM_PATTERN.matcher(sanitized)
                .replaceAll("$1=" + REDACTED_VALUE);
    }

    private static String maskLiteral(String payload, String sensitiveValue) {
        if (sensitiveValue == null || sensitiveValue.isBlank()) {
            return payload;
        }
        return payload.replace(sensitiveValue, REDACTED_VALUE);
    }
}
