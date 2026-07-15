package com.jxc.wefolio.job.service;

import com.jxc.wefolio.job.config.AdminPointProperties;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 后台积分密钥校验测试。
 */
class AdminPointSecretValidatorTest {

    @Test
    void configuredSecretShouldAcceptExactHeaderValue() {
        assertThatCode(() -> validator("server-secret").validate("server-secret"))
                .doesNotThrowAnyException();
    }

    @Test
    void missingOrWrongSecretShouldUseSameSafeMessage() {
        assertRejected(null, "request-secret");
        assertRejected("server-secret", null);
        assertRejected("server-secret", "wrong-secret");
    }

    private void assertRejected(String configured, String requested) {
        assertThatThrownBy(() -> validator(configured).validate(requested))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("后台积分密钥无效")
                .hasMessageNotContaining("server-secret")
                .hasMessageNotContaining("request-secret")
                .hasMessageNotContaining("wrong-secret");
    }

    private AdminPointSecretValidator validator(String secret) {
        AdminPointProperties properties = new AdminPointProperties();
        properties.setSecret(secret);
        return new AdminPointSecretValidator(properties);
    }
}
