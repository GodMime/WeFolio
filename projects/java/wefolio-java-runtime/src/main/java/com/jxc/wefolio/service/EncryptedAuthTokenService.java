package com.jxc.wefolio.service;

import com.jxc.wefolio.config.AuthTokenProperties;
import com.jxc.wefolio.exception.BusinessException;
import com.jxc.wefolio.exception.InvalidAuthTokenException;
import com.jxc.wefolio.message.MiniappAuthMessage;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.Base64;

/**
 * 加密认证令牌服务 — 统一维护认证令牌 AES-GCM 加解密和密钥派生逻辑。
 */
@Service
@RequiredArgsConstructor
public class EncryptedAuthTokenService {

    /** 登录令牌密钥摘要算法 */
    private static final String SHA_256_ALGORITHM = "SHA-256";

    /** 登录令牌加密算法 */
    private static final String AES_GCM_TRANSFORMATION = "AES/GCM/NoPadding";

    /** AES 密钥算法 */
    private static final String AES_ALGORITHM = "AES";

    /** GCM 初始向量字节数 */
    private static final int TOKEN_IV_LENGTH_BYTES = 12;

    /** GCM 认证标签位数 */
    private static final int TOKEN_GCM_TAG_LENGTH_BITS = 128;

    /** 令牌随机数生成器 */
    private static final SecureRandom SECURE_RANDOM = new SecureRandom();

    /** 认证令牌配置 */
    private final AuthTokenProperties authTokenProperties;

    /**
     * 加密 payload 并拼接业务令牌前缀。
     *
     * @param tokenPrefix 业务令牌前缀
     * @param payload 明文 payload
     * @return 加密认证令牌
     */
    public String encryptPayload(String tokenPrefix, String payload) {
        validateTokenInputs(tokenPrefix, payload);
        try {
            byte[] iv = new byte[TOKEN_IV_LENGTH_BYTES];
            SECURE_RANDOM.nextBytes(iv);
            Cipher cipher = Cipher.getInstance(AES_GCM_TRANSFORMATION);
            cipher.init(Cipher.ENCRYPT_MODE, buildTokenSecretKey(), new GCMParameterSpec(TOKEN_GCM_TAG_LENGTH_BITS, iv));
            byte[] cipherText = cipher.doFinal(payload.getBytes(StandardCharsets.UTF_8));
            byte[] tokenBytes = new byte[iv.length + cipherText.length];
            System.arraycopy(iv, 0, tokenBytes, 0, iv.length);
            System.arraycopy(cipherText, 0, tokenBytes, iv.length, cipherText.length);
            return tokenPrefix + Base64.getUrlEncoder().withoutPadding().encodeToString(tokenBytes);
        } catch (BusinessException e) {
            throw e;
        } catch (Exception e) {
            throw new BusinessException(MiniappAuthMessage.TOKEN_GENERATION_FAILED_MESSAGE, e);
        }
    }

    /**
     * 解密认证令牌并返回明文 payload。
     *
     * @param tokenPrefix 业务令牌前缀
     * @param token 加密认证令牌
     * @return 明文 payload
     */
    public String decryptPayload(String tokenPrefix, String token) {
        if (tokenPrefix == null || tokenPrefix.isBlank() || token == null || !token.startsWith(tokenPrefix)) {
            throw new InvalidAuthTokenException(MiniappAuthMessage.TOKEN_PARSE_FAILED_MESSAGE);
        }
        try {
            byte[] tokenBytes = Base64.getUrlDecoder().decode(token.substring(tokenPrefix.length()));
            if (tokenBytes.length <= TOKEN_IV_LENGTH_BYTES) {
                throw new InvalidAuthTokenException(MiniappAuthMessage.TOKEN_PARSE_FAILED_MESSAGE);
            }
            byte[] iv = Arrays.copyOfRange(tokenBytes, 0, TOKEN_IV_LENGTH_BYTES);
            byte[] cipherText = Arrays.copyOfRange(tokenBytes, TOKEN_IV_LENGTH_BYTES, tokenBytes.length);

            Cipher cipher = Cipher.getInstance(AES_GCM_TRANSFORMATION);
            cipher.init(Cipher.DECRYPT_MODE, buildTokenSecretKey(), new GCMParameterSpec(TOKEN_GCM_TAG_LENGTH_BITS, iv));
            return new String(cipher.doFinal(cipherText), StandardCharsets.UTF_8);
        } catch (BusinessException e) {
            throw e;
        } catch (Exception e) {
            throw new InvalidAuthTokenException(MiniappAuthMessage.TOKEN_PARSE_FAILED_MESSAGE, e);
        }
    }

    /**
     * 校验令牌加密入参。
     *
     * @param tokenPrefix 业务令牌前缀
     * @param payload 明文 payload
     */
    private void validateTokenInputs(String tokenPrefix, String payload) {
        if (tokenPrefix == null || tokenPrefix.isBlank() || payload == null || payload.isBlank()) {
            throw new BusinessException(MiniappAuthMessage.TOKEN_GENERATION_FAILED_MESSAGE);
        }
    }

    /**
     * 从应用层令牌密钥派生令牌加密密钥。
     *
     * @return AES 密钥
     */
    private SecretKeySpec buildTokenSecretKey() {
        String tokenSecret = authTokenProperties.getSecret();
        if (tokenSecret == null || tokenSecret.isBlank()) {
            throw new BusinessException(MiniappAuthMessage.TOKEN_SECRET_MISSING_MESSAGE);
        }
        try {
            byte[] key = MessageDigest.getInstance(SHA_256_ALGORITHM).digest(tokenSecret.getBytes(StandardCharsets.UTF_8));
            return new SecretKeySpec(key, AES_ALGORITHM);
        } catch (Exception e) {
            throw new BusinessException(MiniappAuthMessage.TOKEN_SECRET_KEY_FAILED_MESSAGE, e);
        }
    }
}
