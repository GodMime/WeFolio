package com.jxc.wefolio.service.teamportfolio.component.contactform;

import com.jxc.wefolio.exception.BusinessException;
import com.jxc.wefolio.service.EncryptedAuthTokenService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/**
 * 团队预留联系信息加解密服务。
 *
 * <p>密文 v1 依赖共享 AUTH_TOKEN_SECRET。完成密文迁移或引入 keyring 前不得直接轮换该密钥。</p>
 */
@Service
@RequiredArgsConstructor
public class TeamContactLeadCryptoService {

    /** 团队手机号加密前缀。 */
    private static final String TEAM_PHONE_TOKEN_PREFIX = "wf-team-contact-phone:";

    /** 团队微信号加密前缀。 */
    private static final String TEAM_WECHAT_TOKEN_PREFIX = "wf-team-contact-wechat:";

    /** 认证 payload 版本。 */
    private static final String ENCRYPTION_PAYLOAD_VERSION = "v1";

    /** 认证 payload 字段分隔符。 */
    private static final String ENCRYPTION_PAYLOAD_SEPARATOR = "|";

    /** 手机号密文用途。 */
    private static final String ENCRYPTION_PURPOSE_PHONE = "PHONE";

    /** 微信号密文用途。 */
    private static final String ENCRYPTION_PURPOSE_WECHAT = "WECHAT";

    /** 联系方式密文最大长度。 */
    private static final int CONTACT_CIPHERTEXT_MAX_LENGTH = 512;

    /** 写入失败提示。 */
    private static final String PERSISTENCE_FAILED_MESSAGE = "预留联系信息保存失败";

    /** 解密失败固定提示，不携带密文或联系方式。 */
    private static final String DECRYPTION_FAILED_MESSAGE = "团队预留联系信息解密失败";

    /** AES-GCM 加密服务。 */
    private final EncryptedAuthTokenService encryptedAuthTokenService;

    /**
     * 加密团队手机号。
     *
     * @param phone 手机号明文
     * @return 手机号密文，空值返回 null
     */
    public String encryptPhone(String phone) {
        return encrypt(TEAM_PHONE_TOKEN_PREFIX, ENCRYPTION_PURPOSE_PHONE, phone);
    }

    /**
     * 加密团队微信号。
     *
     * @param wechat 微信号明文
     * @return 微信号密文，空值返回 null
     */
    public String encryptWechat(String wechat) {
        return encrypt(TEAM_WECHAT_TOKEN_PREFIX, ENCRYPTION_PURPOSE_WECHAT, wechat);
    }

    /**
     * 解密团队手机号。
     *
     * @param ciphertext 手机号密文
     * @return 手机号明文，空值返回空字符串
     */
    public String decryptPhone(String ciphertext) {
        return decrypt(TEAM_PHONE_TOKEN_PREFIX, ENCRYPTION_PURPOSE_PHONE, ciphertext);
    }

    /**
     * 解密团队微信号。
     *
     * @param ciphertext 微信号密文
     * @return 微信号明文，空值返回空字符串
     */
    public String decryptWechat(String ciphertext) {
        return decrypt(TEAM_WECHAT_TOKEN_PREFIX, ENCRYPTION_PURPOSE_WECHAT, ciphertext);
    }

    /** 加密指定用途的可选联系方式。 */
    private String encrypt(String prefix, String purpose, String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        String ciphertext = encryptedAuthTokenService.encryptPayload(
                prefix, authenticatedPayloadPrefix(purpose) + value);
        if (ciphertext == null || ciphertext.length() > CONTACT_CIPHERTEXT_MAX_LENGTH) {
            throw new BusinessException(PERSISTENCE_FAILED_MESSAGE);
        }
        return ciphertext;
    }

    /** 解密指定用途的可选联系方式。 */
    private String decrypt(String prefix, String purpose, String ciphertext) {
        if (ciphertext == null || ciphertext.isBlank()) {
            return "";
        }
        try {
            String payload = encryptedAuthTokenService.decryptPayload(prefix, ciphertext);
            String expectedPrefix = authenticatedPayloadPrefix(purpose);
            if (payload == null || !payload.startsWith(expectedPrefix)) {
                throw new BusinessException(DECRYPTION_FAILED_MESSAGE);
            }
            return payload.substring(expectedPrefix.length());
        } catch (RuntimeException exception) {
            throw new BusinessException(DECRYPTION_FAILED_MESSAGE);
        }
    }

    /** 构建受认证的版本与用途前缀，防止不同联系方式密文互换。 */
    private String authenticatedPayloadPrefix(String purpose) {
        return ENCRYPTION_PAYLOAD_VERSION
                + ENCRYPTION_PAYLOAD_SEPARATOR
                + purpose
                + ENCRYPTION_PAYLOAD_SEPARATOR;
    }
}
