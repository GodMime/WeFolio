package com.jxc.wefolio.service;

import com.jxc.wefolio.config.AuthTokenProperties;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** 安卓令牌兼容性 — 使用未修改的小程序解析器校验真实加密令牌。 */
class MobileMaintainerTokenServiceTest {
    /** 保持原令牌协议、有效期及撤销规则，无须改变任何现有认证入口。 */
    @Test
    void existingParserAcceptsMobileTokenAndEnforcesRevocation() {
        var properties = new AuthTokenProperties();
        properties.setSecret("mobile-token-test-secret");
        var encryption = new EncryptedAuthTokenService(properties);
        var issuer = new MobileMaintainerTokenService(encryption);
        var parser = new MiniappAuthService(null, null, null, null, encryption,
                null, null, null, null, null, null);
        var response = issuer.issue(7L);
        var parsed = parser.resolveAuthToken(response.getTokenType() + " " + response.getToken());
        assertThat(parsed.userId()).isEqualTo(7L);
        assertThat(parsed.expiresAt().getEpochSecond() - response.getExpiresInSeconds())
                .isBetween(Instant.now().getEpochSecond() - 5, Instant.now().getEpochSecond());
        properties.getMaintainerInvalidThroughEpochSeconds().put(7L, Instant.now().getEpochSecond());
        assertThatThrownBy(() -> parser.resolveAuthToken(response.getToken())).hasMessageContaining("过期");
    }

    /** 无效用户编号不能签发令牌。 */
    @Test
    void rejectsInvalidUser() {
        var issuer = new MobileMaintainerTokenService(null);
        assertThatThrownBy(() -> issuer.issue(null)).hasMessageContaining("账号状态");
        assertThatThrownBy(() -> issuer.issue(0L)).hasMessageContaining("账号状态");
    }
}
