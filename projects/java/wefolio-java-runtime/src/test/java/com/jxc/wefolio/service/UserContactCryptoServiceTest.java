package com.jxc.wefolio.service;

import com.jxc.wefolio.config.AuthTokenProperties;
import com.jxc.wefolio.exception.BusinessException;
import com.jxc.wefolio.message.MiniappAuthMessage;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** 联系资料加密三态、用途隔离、篡改与列宽边界。 */
class UserContactCryptoServiceTest {
    /** 固定测试用加密原语。 */
    private final EncryptedAuthTokenService primitive = primitive();
    /** 真实受认证加密服务。 */
    private final UserContactCryptoService service = new UserContactCryptoService(primitive);
    /** 空串必须保留，不交给底层拒绝空串的原语。 */
    @Test void preservesNullAndExplicitEmpty() {
        assertThat(service.encryptPhone(null)).isNull(); assertThat(service.decryptPhone(null)).isNull();
        assertThat(service.encryptWechat(null)).isNull(); assertThat(service.decryptWechat(null)).isNull();
        assertThat(service.encryptPhone("")).isEmpty(); assertThat(service.decryptPhone("")).isEmpty();
        assertThat(service.encryptWechat("")).isEmpty(); assertThat(service.decryptWechat("")).isEmpty();
    }
    /** 最大四字节码点输入仍能往返，随机 IV 产生不同密文。 */
    @Test void roundTripsMaxMultibyteValues() {
        String phone = "😀".repeat(32), wechat = "😀".repeat(64);
        String encrypted = service.encryptWechat(wechat);
        assertThat(encrypted).hasSizeLessThanOrEqualTo(512).isNotEqualTo(service.encryptWechat(wechat));
        assertThat(service.decryptWechat(encrypted)).isEqualTo(wechat);
        assertThat(service.decryptPhone(service.encryptPhone(phone))).isEqualTo(phone);
    }
    /** 修改外层用途不能绕过受认证 payload 内的用途校验。 */
    @Test void rejectsCrossPurposeAndTampering() {
        String phone = service.encryptPhone("13800138000");
        assertThatThrownBy(() -> service.decryptWechat(phone)).isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> service.decryptWechat(phone.replace("wf-user-contact-phone:", "wf-user-contact-wechat:")))
                .isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> service.decryptPhone(primitive.encryptPayload("wf-user-contact-phone:", "v1|TEAM_CONTACT_LEAD|hello")))
                .isInstanceOf(BusinessException.class);
        int changed = phone.indexOf(':') + 5;
        String tampered = phone.substring(0, changed) + (phone.charAt(changed) == 'A' ? 'B' : 'A') + phone.substring(changed + 1);
        assertThatThrownBy(() -> service.decryptPhone(tampered)).isInstanceOf(BusinessException.class);
    }
    /** 超长密文受控失败，不能截断存储。 */
    @Test void rejectsColumnOverflow() {
        assertThatThrownBy(() -> service.encryptWechat("😀".repeat(1000))).isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> service.decryptPhone("x".repeat(513))).isInstanceOf(BusinessException.class);
    }
    /** 缺失系统密钥属于配置错误，不能被改写成联系信息重试提示。 */
    @Test void preservesMissingSecretBusinessException() {
        var missingSecret = new UserContactCryptoService(new EncryptedAuthTokenService(new AuthTokenProperties()));
        assertThatThrownBy(() -> missingSecret.encryptPhone("13800138000"))
                .isInstanceOf(BusinessException.class).hasMessage(MiniappAuthMessage.TOKEN_SECRET_MISSING_MESSAGE);
        String ciphertext = service.encryptWechat("小映");
        assertThatThrownBy(() -> missingSecret.decryptWechat(ciphertext))
                .isInstanceOf(BusinessException.class).hasMessage(MiniappAuthMessage.TOKEN_SECRET_MISSING_MESSAGE);
    }
    /** 构造测试密钥，不依赖本地环境。 */
    private static EncryptedAuthTokenService primitive() {
        AuthTokenProperties properties = new AuthTokenProperties(); properties.setSecret("contact-crypto-test-only");
        return new EncryptedAuthTokenService(properties);
    }
}
