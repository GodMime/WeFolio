package com.jxc.wefolio.service;

import com.jxc.wefolio.entity.UserEntity;
import com.jxc.wefolio.exception.BusinessException;
import com.jxc.wefolio.message.MineProfileMessage;
import java.util.function.Function;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/** 统一解析公开联系资料的三态快照，供个人资料接口和 AI 可信事实共用。 */
@Slf4j
@Service
@RequiredArgsConstructor
public class UserContactProfileService {
    /** 联系手机密文列名，仅用于定位损坏资料。 */
    private static final String COL_CONTACT_PHONE = "contact_phone_ciphertext";
    /** 联系微信密文列名，仅用于定位损坏资料。 */
    private static final String COL_CONTACT_WECHAT = "contact_wechat_ciphertext";
    /** 联系资料专用用途隔离解密器。 */
    private final UserContactCryptoService contactCrypto;

    /**
     * 解析现有公开资料语义：手机未设置时使用既有默认手机，明确清空不回填，密文损坏沿原规则降级。
     *
     * @param user 已由调用方确认可读取的用户记录
     * @return 当前公开联系资料，不包含密文或其他账户字段
     */
    public ContactProfile resolve(UserEntity user) {
        String phone = readContactField(user.getId(), COL_CONTACT_PHONE, user.getContactPhoneCiphertext(),
                defaultString(user.getPhoneNumber()), contactCrypto::decryptPhone);
        String wechat = readContactField(user.getId(), COL_CONTACT_WECHAT, user.getContactWechatCiphertext(),
                "", contactCrypto::decryptWechat);
        return new ContactProfile(phone, wechat);
    }

    /**
     * 公开联系资料只读值。
     *
     * @param contactPhone 当前公开联系手机
     * @param contactWechat 当前公开联系微信
     */
    public record ContactProfile(String contactPhone, String contactWechat) { }

    /** 单条损坏密文降级展示，不修改存储；系统配置错误继续上抛。 */
    private String readContactField(Long userId, String field, String ciphertext, String fallback,
            Function<String, String> decrypt) {
        if (ciphertext == null) { return fallback; }
        try {
            return defaultString(decrypt.apply(ciphertext));
        } catch (BusinessException exception) {
            if (!MineProfileMessage.CONTACT_CRYPTO_INVALID.equals(exception.getMessage())) { throw exception; }
            // 沿用原资料日志定位信息，不记录明文、密文或可能夹带敏感值的异常。
            log.warn("联系资料解密失败，使用展示默认值 userId={} field={}", userId, field);
            return fallback;
        }
    }

    /** 缺省公开字段按空文本返回，保持现有资料响应语义。 */
    private static String defaultString(String value) { return value == null ? "" : value; }
}
