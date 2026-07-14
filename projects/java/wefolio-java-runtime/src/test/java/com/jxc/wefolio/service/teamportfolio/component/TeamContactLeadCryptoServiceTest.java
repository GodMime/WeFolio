package com.jxc.wefolio.service.teamportfolio.component;

import com.jxc.wefolio.config.AuthTokenProperties;
import com.jxc.wefolio.service.EncryptedAuthTokenService;
import com.jxc.wefolio.service.teamportfolio.component.contactform.TeamContactLeadCryptoService;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 团队预留联系信息加解密服务测试。
 */
class TeamContactLeadCryptoServiceTest {

    /** 测试手机号。 */
    private static final String PHONE = "13800138000";

    /** 测试微信号。 */
    private static final String WECHAT = "wefolio-contact";

    /** 固定解密失败提示。 */
    private static final String DECRYPTION_FAILED_MESSAGE = "团队预留联系信息解密失败";

    @Test
    void shouldEncryptAndDecryptPhoneAndWechatByPurpose() {
        TeamContactLeadCryptoService service = service();

        String phoneCiphertext = service.encryptPhone(PHONE);
        String wechatCiphertext = service.encryptWechat(WECHAT);

        assertThat(phoneCiphertext).doesNotContain(PHONE);
        assertThat(wechatCiphertext).doesNotContain(WECHAT);
        assertThat(service.decryptPhone(phoneCiphertext)).isEqualTo(PHONE);
        assertThat(service.decryptWechat(wechatCiphertext)).isEqualTo(WECHAT);
    }

    @Test
    void shouldKeepOptionalBlankContactValuesEmpty() {
        TeamContactLeadCryptoService service = service();

        assertThat(service.encryptPhone(" ")).isNull();
        assertThat(service.encryptWechat(null)).isNull();
        assertThat(service.decryptPhone(" ")).isEmpty();
        assertThat(service.decryptWechat(null)).isEmpty();
    }

    @Test
    void shouldRejectCiphertextFromAnotherContactPurpose() {
        TeamContactLeadCryptoService service = service();
        String phoneCiphertext = service.encryptPhone(PHONE);

        assertThatThrownBy(() -> service.decryptWechat(phoneCiphertext))
                .hasMessage(DECRYPTION_FAILED_MESSAGE)
                .hasMessageNotContaining(phoneCiphertext);
    }

    @Test
    void shouldRejectCorruptedCiphertextWithoutLeakingIt() {
        TeamContactLeadCryptoService service = service();
        String corruptedCiphertext = "wf-team-contact-phone:corrupted-value";

        assertThatThrownBy(() -> service.decryptPhone(corruptedCiphertext))
                .hasMessage(DECRYPTION_FAILED_MESSAGE)
                .hasMessageNotContaining(corruptedCiphertext);
    }

    /** 创建使用真实 AES-GCM 的待测服务。 */
    private static TeamContactLeadCryptoService service() {
        AuthTokenProperties properties = new AuthTokenProperties();
        properties.setSecret("team-contact-crypto-test-secret");
        return new TeamContactLeadCryptoService(new EncryptedAuthTokenService(properties));
    }
}
