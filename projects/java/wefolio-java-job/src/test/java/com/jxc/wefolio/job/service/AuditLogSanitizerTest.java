package com.jxc.wefolio.job.service;

import com.jxc.wefolio.job.config.CosProperties;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 审核日志脱敏工具测试。
 */
class AuditLogSanitizerTest {

    /** 测试用 COS SecretId */
    private static final String SECRET_ID = "AKID_TEST_SECRET_ID";

    /** 测试用 COS SecretKey */
    private static final String SECRET_KEY = "TEST_SECRET_KEY_VALUE";

    /** 测试用腾讯云签名 */
    private static final String SIGNATURE = "TEST_SIGNATURE_VALUE";

    /** 测试用临时 token */
    private static final String SECURITY_TOKEN = "TEST_SECURITY_TOKEN_VALUE";

    @Test
    void sanitizeShouldMaskCosSecretsAndTencentSignedUrlFields() {
        CosProperties properties = new CosProperties();
        properties.setSecretId(SECRET_ID);
        properties.setSecretKey(SECRET_KEY);
        String payload = """
                {"secretId":"AKID_TEST_SECRET_ID","secretKey":"TEST_SECRET_KEY_VALUE",\
                "url":"https://example.com/demo.jpg?q-ak=AKID_TEST_SECRET_ID&q-signature=TEST_SIGNATURE_VALUE&x-cos-security-token=TEST_SECURITY_TOKEN_VALUE"}\
                """;

        String sanitized = AuditLogSanitizer.sanitize(payload, properties);

        assertThat(sanitized)
                .doesNotContain(SECRET_ID)
                .doesNotContain(SECRET_KEY)
                .doesNotContain(SIGNATURE)
                .doesNotContain(SECURITY_TOKEN)
                .contains("q-ak=***REDACTED***")
                .contains("q-signature=***REDACTED***")
                .contains("x-cos-security-token=***REDACTED***");
    }

    @Test
    void sanitizeShouldKeepNonSensitivePayloadReadable() {
        CosProperties properties = new CosProperties();
        properties.setSecretId(SECRET_ID);
        properties.setSecretKey(SECRET_KEY);

        String sanitized = AuditLogSanitizer.sanitize(
                "{\"bucketName\":\"test-bucket\",\"objectKey\":\"demo/demo-image-1.jpg\"}", properties);

        assertThat(sanitized).contains("test-bucket", "demo/demo-image-1.jpg");
    }
}
