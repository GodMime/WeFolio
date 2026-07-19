package com.jxc.wefolio.service.payment;

import com.jxc.wefolio.config.WechatMiniappProperties;
import com.jxc.wefolio.config.WechatVirtualPaymentProperties;
import org.junit.jupiter.api.Test;

import javax.crypto.Cipher;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.Base64;
import java.util.HexFormat;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** 微信虚拟支付回调安全测试。 */
class WechatVirtualPaymentMessageSecurityTest {

    private static final String TOKEN = "message-token";
    private static final String APP_ID = "wx-test-app";
    private static final byte[] AES_KEY = "0123456789ABCDEF0123456789ABCDEF".getBytes(StandardCharsets.US_ASCII);

    @Test
    void shouldVerifyPlaintextSignatureAndRejectTampering() {
        WechatVirtualPaymentMessageSecurity security = security();
        String signature = sha1Sorted(TOKEN, "1720000000", "nonce-1");

        assertThat(security.verifyPlaintextSignature(signature, "1720000000", "nonce-1")).isTrue();
        assertThat(security.verifyPlaintextSignature("broken", "1720000000", "nonce-1")).isFalse();
    }

    @Test
    void shouldVerifyAndDecryptSafeModePayload() throws Exception {
        String xml = "<xml><Event><![CDATA[xpay_coin_pay_notify]]></Event>"
                + "<OrderId><![CDATA[WFR202607190001]]></OrderId></xml>";
        String encrypted = encrypt(xml);
        String signature = sha1Sorted(TOKEN, "1720000000", "nonce-2", encrypted);

        assertThat(security().decryptMessage(signature, "1720000000", "nonce-2", encrypted))
                .isEqualTo(xml);
    }

    @Test
    void shouldRejectSafeModePayloadWithWrongAppId() throws Exception {
        String encrypted = encrypt("<xml/>", "wx-other-app");
        String signature = sha1Sorted(TOKEN, "1720000000", "nonce-3", encrypted);

        assertThatThrownBy(() -> security().decryptMessage(
                signature, "1720000000", "nonce-3", encrypted))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("AppID");
    }

    private WechatVirtualPaymentMessageSecurity security() {
        WechatVirtualPaymentProperties properties = new WechatVirtualPaymentProperties();
        properties.setMessageToken(TOKEN);
        properties.setMessageEncodingAesKey(
                Base64.getEncoder().withoutPadding().encodeToString(AES_KEY));
        WechatMiniappProperties miniappProperties = new WechatMiniappProperties();
        miniappProperties.setAppId(APP_ID);
        return new WechatVirtualPaymentMessageSecurity(properties, miniappProperties);
    }

    private String encrypt(String xml) throws Exception {
        return encrypt(xml, APP_ID);
    }

    private String encrypt(String xml, String appId) throws Exception {
        byte[] random = "1234567890ABCDEF".getBytes(StandardCharsets.US_ASCII);
        byte[] message = xml.getBytes(StandardCharsets.UTF_8);
        byte[] app = appId.getBytes(StandardCharsets.UTF_8);
        ByteBuffer buffer = ByteBuffer.allocate(random.length + 4 + message.length + app.length + 32);
        buffer.put(random).putInt(message.length).put(message).put(app);
        int dataLength = buffer.position();
        int padding = 32 - dataLength % 32;
        for (int index = 0; index < padding; index++) {
            buffer.put((byte) padding);
        }
        byte[] plain = Arrays.copyOf(buffer.array(), dataLength + padding);
        Cipher cipher = Cipher.getInstance("AES/CBC/NoPadding");
        cipher.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(AES_KEY, "AES"),
                new IvParameterSpec(Arrays.copyOf(AES_KEY, 16)));
        return Base64.getEncoder().encodeToString(cipher.doFinal(plain));
    }

    private String sha1Sorted(String... parts) {
        try {
            String joined = Arrays.stream(parts).sorted().reduce("", String::concat);
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-1")
                    .digest(joined.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception exception) {
            throw new IllegalStateException(exception);
        }
    }
}
