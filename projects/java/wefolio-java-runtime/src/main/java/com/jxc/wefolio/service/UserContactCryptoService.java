package com.jxc.wefolio.service;

import com.jxc.wefolio.exception.BusinessException;
import com.jxc.wefolio.exception.InvalidAuthTokenException;
import com.jxc.wefolio.message.MineProfileMessage;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/**
 * 用户联系资料专用三态加密。v1 依赖共享 AUTH_TOKEN_SECRET，完成存量迁移或引入 keyring 前不得直接轮换密钥。
 * 外层前缀与受认证明文用途同时检查，禁止手机、微信及其他业务密文互换。
 */
@Service
@RequiredArgsConstructor
public class UserContactCryptoService {
    /** 手机密文用途前缀。 */
    private static final String PHONE_PREFIX = "wf-user-contact-phone:";
    /** 微信密文用途前缀。 */
    private static final String WECHAT_PREFIX = "wf-user-contact-wechat:";
    /** 手机受认证用途。 */
    private static final String PHONE_PAYLOAD = "v1|USER_CONTACT_PHONE|";
    /** 微信受认证用途。 */
    private static final String WECHAT_PAYLOAD = "v1|USER_CONTACT_WECHAT|";
    /** 数据库密文列宽。 */
    private static final int MAX_CIPHERTEXT_LENGTH = 512;
    /** 既有 AES-GCM 原语。 */
    private final EncryptedAuthTokenService encryptedAuthTokenService;

    /** 加密联系手机，保留未设置和明确清空哨兵。 */
    public String encryptPhone(String value) { return encrypt(value, PHONE_PREFIX, PHONE_PAYLOAD); }
    /** 解密联系手机并检查用途。 */
    public String decryptPhone(String value) { return decrypt(value, PHONE_PREFIX, PHONE_PAYLOAD); }
    /** 加密联系微信，保留未设置和明确清空哨兵。 */
    public String encryptWechat(String value) { return encrypt(value, WECHAT_PREFIX, WECHAT_PAYLOAD); }
    /** 解密联系微信并检查用途。 */
    public String decryptWechat(String value) { return decrypt(value, WECHAT_PREFIX, WECHAT_PAYLOAD); }

    /** 非空明文编码为带用途版本的受认证 payload。 */
    private String encrypt(String value, String prefix, String payloadPrefix) {
        if (value == null || value.isEmpty()) { return value; }
        try {
            String ciphertext = encryptedAuthTokenService.encryptPayload(prefix, payloadPrefix + value);
            if (ciphertext.length() > MAX_CIPHERTEXT_LENGTH) { throw new IllegalArgumentException(); }
            return ciphertext;
        } catch (BusinessException exception) {
            throw exception;
        } catch (RuntimeException exception) {
            throw new BusinessException(MineProfileMessage.CONTACT_CRYPTO_INVALID);
        }
    }

    /** 先检查外层用途和长度，再认证解密并检查 payload 用途。 */
    private String decrypt(String value, String prefix, String payloadPrefix) {
        if (value == null || value.isEmpty()) { return value; }
        try {
            if (value.length() > MAX_CIPHERTEXT_LENGTH || !value.startsWith(prefix)) {
                throw new IllegalArgumentException();
            }
            String payload = encryptedAuthTokenService.decryptPayload(prefix, value);
            if (!payload.startsWith(payloadPrefix) || payload.length() == payloadPrefix.length()) {
                throw new IllegalArgumentException();
            }
            return payload.substring(payloadPrefix.length());
        } catch (InvalidAuthTokenException exception) {
            // 资料密文损坏不属于当前用户登录失效，不能触发登录态刷新。
            throw new BusinessException(MineProfileMessage.CONTACT_CRYPTO_INVALID);
        } catch (BusinessException exception) {
            throw exception;
        } catch (RuntimeException exception) {
            throw new BusinessException(MineProfileMessage.CONTACT_CRYPTO_INVALID);
        }
    }
}
