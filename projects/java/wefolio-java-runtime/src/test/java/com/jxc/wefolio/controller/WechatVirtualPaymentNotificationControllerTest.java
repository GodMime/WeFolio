package com.jxc.wefolio.controller;

import com.jxc.wefolio.config.WechatVirtualPaymentProperties;
import com.jxc.wefolio.service.WechatInteractionLogSanitizer;
import com.jxc.wefolio.service.payment.WechatVirtualPaymentMessageSecurity;
import com.jxc.wefolio.service.payment.WechatVirtualPaymentNotification;
import com.jxc.wefolio.service.payment.WechatVirtualPaymentNotificationCodec;
import com.jxc.wefolio.service.payment.WechatVirtualPaymentNotificationService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.http.ResponseEntity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** 微信虚拟支付回调交互日志测试。 */
@ExtendWith(OutputCaptureExtension.class)
class WechatVirtualPaymentNotificationControllerTest {

    /** 地址校验应记录脱敏入参与应答，且不泄漏签名、随机串和回显值。 */
    @Test
    void logsSanitizedVerificationInteraction(CapturedOutput output) {
        WechatVirtualPaymentProperties properties = enabledProperties();
        WechatVirtualPaymentMessageSecurity security = mock(WechatVirtualPaymentMessageSecurity.class);
        when(security.verifyPlaintextSignature("signature-secret", "1720000000", "nonce-secret"))
                .thenReturn(true);
        WechatVirtualPaymentNotificationController controller = new WechatVirtualPaymentNotificationController(
                properties, security, mock(WechatVirtualPaymentNotificationCodec.class),
                mock(WechatVirtualPaymentNotificationService.class),
                new WechatInteractionLogSanitizer());

        ResponseEntity<String> response = controller.verify(
                "signature-secret", null, "1720000000", "nonce-secret", "echo-secret");

        assertThat(response.getStatusCode().value()).isEqualTo(200);
        assertThat(response.getBody()).isEqualTo("echo-secret");
        assertThat(output).contains("微信交互请求 operation=虚拟支付回调地址校验");
        assertThat(output).contains("signature=***");
        assertThat(output).contains("nonce=***");
        assertThat(output).contains("echostr=***");
        assertThat(output).contains("微信交互响应 operation=虚拟支付回调地址校验");
        assertThat(output).contains("httpStatus=200");
        assertThat(output).contains("elapsedMs=");
        assertThat(output).doesNotContain("signature-secret");
        assertThat(output).doesNotContain("nonce-secret");
        assertThat(output).doesNotContain("echo-secret");
    }

    /** 安全模式通知应记录外层密文、解密报文和最终应答的脱敏结果。 */
    @Test
    void logsSanitizedEncryptedNotificationInteraction(CapturedOutput output) {
        WechatVirtualPaymentProperties properties = enabledProperties();
        WechatVirtualPaymentMessageSecurity security = mock(WechatVirtualPaymentMessageSecurity.class);
        WechatVirtualPaymentNotificationCodec codec = mock(WechatVirtualPaymentNotificationCodec.class);
        WechatVirtualPaymentNotificationService service = mock(WechatVirtualPaymentNotificationService.class);
        String outerBody = "<xml><Encrypt>cipher-secret</Encrypt></xml>";
        String payload = "<xml><Event>xpay_coin_pay_notify</Event><openid>openid-secret</openid>"
                + "<out_trade_no>WFR202607190001</out_trade_no></xml>";
        WechatVirtualPaymentNotification notification = new WechatVirtualPaymentNotification(
                "xpay_coin_pay_notify", "WFR202607190001");
        when(codec.extractEncrypted(outerBody)).thenReturn("cipher-secret");
        when(security.decryptMessage("message-signature-secret", "1720000000", "nonce-secret",
                "cipher-secret")).thenReturn(payload);
        when(codec.parse(payload)).thenReturn(notification);
        when(service.handle(notification)).thenReturn(
                "<xml><return_code>SUCCESS</return_code><return_msg>OK</return_msg></xml>");
        WechatVirtualPaymentNotificationController controller = new WechatVirtualPaymentNotificationController(
                properties, security, codec, service, new WechatInteractionLogSanitizer());

        ResponseEntity<String> response = controller.notify(
                outerBody, null, "message-signature-secret", "1720000000", "nonce-secret");

        assertThat(response.getStatusCode().value()).isEqualTo(200);
        assertThat(output).contains("微信交互请求 operation=虚拟支付通知回调");
        assertThat(output).contains("<Encrypt>***</Encrypt>");
        assertThat(output).contains("微信交互解密结果 operation=虚拟支付通知回调");
        assertThat(output).contains("<openid>***cret</openid>");
        assertThat(output).contains("referenceNo=WFR202607190001");
        assertThat(output).contains("微信交互响应 operation=虚拟支付通知回调");
        assertThat(output).contains("httpStatus=200");
        assertThat(output).doesNotContain("cipher-secret");
        assertThat(output).doesNotContain("openid-secret");
        assertThat(output).doesNotContain("message-signature-secret");
        assertThat(output).doesNotContain("nonce-secret");
    }

    /** 明文通知应记录脱敏正文并完成验签、解析和分发。 */
    @Test
    void logsSanitizedPlaintextNotificationInteraction(CapturedOutput output) {
        WechatVirtualPaymentProperties properties = enabledProperties();
        WechatVirtualPaymentMessageSecurity security = mock(WechatVirtualPaymentMessageSecurity.class);
        WechatVirtualPaymentNotificationCodec codec = mock(WechatVirtualPaymentNotificationCodec.class);
        WechatVirtualPaymentNotificationService service = mock(WechatVirtualPaymentNotificationService.class);
        String body = "<xml><Event>xpay_coin_pay_notify</Event><openid>openid-plain-secret</openid>"
                + "<out_trade_no>WFR202607190002</out_trade_no></xml>";
        WechatVirtualPaymentNotification notification = new WechatVirtualPaymentNotification(
                "xpay_coin_pay_notify", "WFR202607190002");
        when(codec.extractEncrypted(body)).thenReturn(null);
        when(security.verifyPlaintextSignature("signature-secret", "1720000001", "nonce-secret"))
                .thenReturn(true);
        when(codec.parse(body)).thenReturn(notification);
        when(service.handle(notification)).thenReturn(
                "<xml><return_code>SUCCESS</return_code><return_msg>OK</return_msg></xml>");
        WechatVirtualPaymentNotificationController controller = new WechatVirtualPaymentNotificationController(
                properties, security, codec, service, new WechatInteractionLogSanitizer());

        ResponseEntity<String> response = controller.notify(
                body, "signature-secret", null, "1720000001", "nonce-secret");

        assertThat(response.getStatusCode().value()).isEqualTo(200);
        assertThat(output).contains("微信交互请求 operation=虚拟支付通知回调");
        assertThat(output).contains("<openid>***cret</openid>");
        assertThat(output).contains("referenceNo=WFR202607190002");
        assertThat(output).contains("微信交互响应 operation=虚拟支付通知回调");
        assertThat(output).doesNotContain("openid-plain-secret");
        assertThat(output).doesNotContain("signature-secret");
        assertThat(output).doesNotContain("nonce-secret");
    }

    /** @return 已开启的虚拟支付配置。 */
    private WechatVirtualPaymentProperties enabledProperties() {
        WechatVirtualPaymentProperties properties = new WechatVirtualPaymentProperties();
        properties.setEnabled(true);
        return properties;
    }
}
