package com.jxc.wefolio.job.service;

import com.jxc.wefolio.job.config.AdminPointProperties;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 后台积分密钥校验测试。
 */
class AdminPointSecretValidatorTest {

    /** 配置密钥与请求头完全一致时应校验通过。 */
    @Test
    void configuredSecretShouldAcceptExactHeaderValue() {
        assertThat(validator("server-secret").isValid("server-secret")).isTrue();
    }

    /** 缺少或错误密钥应返回校验失败，不通过异常传递控制流。 */
    @Test
    void missingOrWrongSecretShouldReturnFalse() {
        assertThat(validator(null).isValid("request-secret")).isFalse();
        assertThat(validator("server-secret").isValid(null)).isFalse();
        assertThat(validator("server-secret").isValid("wrong-secret")).isFalse();
    }

    /** 构造指定配置密钥的校验器。 */
    private AdminPointSecretValidator validator(String secret) {
        AdminPointProperties properties = new AdminPointProperties();
        properties.setSecret(secret);
        return new AdminPointSecretValidator(properties);
    }
}
