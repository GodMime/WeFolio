package com.jxc.wefolio.service;

import com.jxc.wefolio.config.AuthTokenProperties;
import com.jxc.wefolio.exception.BusinessException;
import com.jxc.wefolio.message.MiniappAuthMessage;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 加密认证令牌服务测试 — 覆盖维护者与访客共用的 AES-GCM 加解密能力。
 */
class EncryptedAuthTokenServiceTest {

    /** 测试令牌前缀 */
    private static final String TOKEN_PREFIX = "wf-test-v1.";

    /** 测试令牌 payload */
    private static final String TOKEN_PAYLOAD = "1024:visitor-key:1893456000";

    /**
     * 加密后可用同一密钥解回原始 payload。
     */
    @Test
    void encryptsAndDecryptsPayloadWithConfiguredSecret() {
        EncryptedAuthTokenService service = service("shared-token-secret");

        String token = service.encryptPayload(TOKEN_PREFIX, TOKEN_PAYLOAD);
        String payload = service.decryptPayload(TOKEN_PREFIX, token);

        assertThat(token).startsWith(TOKEN_PREFIX);
        assertThat(payload).isEqualTo(TOKEN_PAYLOAD);
    }

    /**
     * 不同密钥无法解密已签发令牌。
     */
    @Test
    void rejectsTokenWhenSecretDoesNotMatch() {
        EncryptedAuthTokenService issuer = service("issuer-token-secret");
        EncryptedAuthTokenService resolver = service("resolver-token-secret");
        String token = issuer.encryptPayload(TOKEN_PREFIX, TOKEN_PAYLOAD);

        assertThatThrownBy(() -> resolver.decryptPayload(TOKEN_PREFIX, token))
                .isInstanceOf(BusinessException.class)
                .hasMessage(MiniappAuthMessage.TOKEN_PARSE_FAILED_MESSAGE);
    }

    /**
     * 构造加密认证令牌服务。
     *
     * @param secret 令牌密钥
     * @return 加密认证令牌服务
     */
    private EncryptedAuthTokenService service(String secret) {
        AuthTokenProperties properties = new AuthTokenProperties();
        properties.setSecret(secret);
        return new EncryptedAuthTokenService(properties);
    }
}
