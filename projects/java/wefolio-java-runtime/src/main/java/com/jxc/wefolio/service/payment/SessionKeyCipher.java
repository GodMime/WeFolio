package com.jxc.wefolio.service.payment;

import com.jxc.wefolio.config.WechatVirtualPaymentProperties;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Base64;

/**
 * 维护者微信会话密钥加密器 — 使用独立 256 位 AES-GCM 密钥和随机十二字节 nonce。
 */
@Component
@RequiredArgsConstructor
public class SessionKeyCipher {

    /** AES-GCM 算法名称。 */
    private static final String TRANSFORMATION = "AES/GCM/NoPadding";

    /** AES 算法名称。 */
    private static final String AES_ALGORITHM = "AES";

    /** GCM nonce 长度。 */
    private static final int NONCE_LENGTH = 12;

    /** GCM 认证标签位数。 */
    private static final int TAG_LENGTH_BITS = 128;

    /** AES-256 密钥字节数。 */
    private static final int KEY_LENGTH = 32;

    /** 虚拟支付配置。 */
    private final WechatVirtualPaymentProperties properties;

    /** 安全随机数生成器。 */
    private final SecureRandom secureRandom = new SecureRandom();

    /** 加密 session_key 并返回 Base64 文本。 */
    public String encrypt(String plaintext) {
        if (plaintext == null || plaintext.isBlank()) {
            throw new IllegalArgumentException("微信会话密钥不能为空");
        }
        byte[] nonce = new byte[NONCE_LENGTH];
        secureRandom.nextBytes(nonce);
        try {
            Cipher cipher = Cipher.getInstance(TRANSFORMATION);
            cipher.init(Cipher.ENCRYPT_MODE, key(), new GCMParameterSpec(TAG_LENGTH_BITS, nonce));
            byte[] ciphertext = cipher.doFinal(plaintext.getBytes(StandardCharsets.UTF_8));
            return Base64.getEncoder().encodeToString(
                    ByteBuffer.allocate(nonce.length + ciphertext.length).put(nonce).put(ciphertext).array());
        } catch (GeneralSecurityException exception) {
            throw new IllegalStateException("维护者微信会话加密失败", exception);
        }
    }

    /** 解密 Base64 会话密文。 */
    public String decrypt(String encodedCiphertext) {
        try {
            byte[] payload = Base64.getDecoder().decode(encodedCiphertext);
            if (payload.length <= NONCE_LENGTH) {
                throw new IllegalArgumentException("维护者微信会话密文格式错误");
            }
            byte[] nonce = new byte[NONCE_LENGTH];
            byte[] ciphertext = new byte[payload.length - NONCE_LENGTH];
            System.arraycopy(payload, 0, nonce, 0, NONCE_LENGTH);
            System.arraycopy(payload, NONCE_LENGTH, ciphertext, 0, ciphertext.length);
            Cipher cipher = Cipher.getInstance(TRANSFORMATION);
            cipher.init(Cipher.DECRYPT_MODE, key(), new GCMParameterSpec(TAG_LENGTH_BITS, nonce));
            return new String(cipher.doFinal(ciphertext), StandardCharsets.UTF_8);
        } catch (IllegalArgumentException exception) {
            throw exception;
        } catch (GeneralSecurityException exception) {
            throw new IllegalStateException("维护者微信会话解密失败", exception);
        }
    }

    /** 解析并校验独立 AES-256 密钥。 */
    private SecretKeySpec key() {
        String configured = properties.getSessionEncryptionSecret();
        if (configured == null || configured.isBlank()) {
            throw new IllegalStateException("维护者微信会话加密密钥未配置");
        }
        byte[] decoded = Base64.getDecoder().decode(configured.strip());
        if (decoded.length != KEY_LENGTH) {
            throw new IllegalStateException("维护者微信会话加密密钥必须是 Base64 编码的 32 字节密钥");
        }
        return new SecretKeySpec(decoded, AES_ALGORITHM);
    }
}
