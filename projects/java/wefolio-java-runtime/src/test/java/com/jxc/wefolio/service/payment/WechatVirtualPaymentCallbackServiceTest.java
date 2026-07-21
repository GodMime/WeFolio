package com.jxc.wefolio.service.payment;

import com.jxc.wefolio.config.WechatVirtualPaymentProperties;
import com.jxc.wefolio.service.WechatInteractionLogSanitizer;
import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.slf4j.LoggerFactory;

import java.lang.reflect.Modifier;
import java.util.Arrays;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/** 微信虚拟支付回调应用服务测试。 */
@ExtendWith(OutputCaptureExtension.class)
class WechatVirtualPaymentCallbackServiceTest {

    /** 地址校验应保留脱敏交互日志。 */
    @Test
    void verifyShouldKeepSanitizedInteractionLogs(CapturedOutput output) {
        WechatVirtualPaymentMessageSecurity security = mock(WechatVirtualPaymentMessageSecurity.class);
        when(security.verifyPlaintextSignature("signature-secret", "1720000000", "nonce-secret"))
                .thenReturn(true);
        WechatVirtualPaymentCallbackService service = callbackService(
                enabledProperties(), security, mock(WechatVirtualPaymentNotificationCodec.class),
                mock(WechatVirtualPaymentNotificationService.class));

        WechatVirtualPaymentCallbackService.CallbackResult result = service.verify(
                "signature-secret", null, "1720000000", "nonce-secret", "echo-secret");

        assertThat(result.statusCode()).isEqualTo(200);
        assertThat(result.body()).isEqualTo("echo-secret");
        assertThat(output).contains("微信交互请求 operation=虚拟支付回调地址校验");
        assertThat(output).contains("signature=***");
        assertThat(output).contains("nonce=***");
        assertThat(output).contains("echostr=***");
        assertThat(output).contains("微信交互响应 operation=虚拟支付回调地址校验");
        assertThat(output).contains("httpStatus=200");
        assertThat(output).doesNotContain("signature-secret");
        assertThat(output).doesNotContain("nonce-secret");
        assertThat(output).doesNotContain("echo-secret");
    }

    /** 安全模式通知应保留解密、解析和分发流程。 */
    @Test
    void encryptedNotificationShouldKeepDecryptParseAndDispatchFlow(CapturedOutput output) {
        WechatVirtualPaymentMessageSecurity security = mock(WechatVirtualPaymentMessageSecurity.class);
        WechatVirtualPaymentNotificationCodec codec = mock(WechatVirtualPaymentNotificationCodec.class);
        WechatVirtualPaymentNotificationService notificationService =
                mock(WechatVirtualPaymentNotificationService.class);
        String outerBody = "<xml><Encrypt>cipher-secret</Encrypt></xml>";
        String payload = "<xml><Event>xpay_coin_pay_notify</Event><openid>openid-secret</openid>"
                + "<out_trade_no>WFR202607190001</out_trade_no></xml>";
        WechatVirtualPaymentNotification notification = new WechatVirtualPaymentNotification(
                "xpay_coin_pay_notify", "WFR202607190001");
        when(codec.extractEncrypted(outerBody)).thenReturn("cipher-secret");
        when(security.decryptMessage("message-signature-secret", "1720000000", "nonce-secret",
                "cipher-secret")).thenReturn(payload);
        when(codec.parse(payload)).thenReturn(notification);
        when(notificationService.handle(notification)).thenReturn(successResponse());
        WechatVirtualPaymentCallbackService service = callbackService(
                enabledProperties(), security, codec, notificationService);

        WechatVirtualPaymentCallbackService.CallbackResult result = service.notify(
                outerBody, null, "message-signature-secret", "1720000000", "nonce-secret");

        assertThat(result.statusCode()).isEqualTo(200);
        assertThat(result.body()).isEqualTo(successResponse());
        assertThat(output).contains("<Encrypt>***</Encrypt>");
        assertThat(output).contains("微信交互解密结果 operation=虚拟支付通知回调");
        assertThat(output).contains("<openid>***cret</openid>");
        assertThat(output).contains("referenceNo=WFR202607190001");
        assertThat(output).contains("微信交互响应 operation=虚拟支付通知回调");
        assertThat(output).doesNotContain("cipher-secret");
        assertThat(output).doesNotContain("openid-secret");
    }

    /** 明文签名失败应保留原状态码和失败报文。 */
    @Test
    void plaintextNotificationShouldKeepSignatureFailureResponse() {
        WechatVirtualPaymentMessageSecurity security = mock(WechatVirtualPaymentMessageSecurity.class);
        WechatVirtualPaymentNotificationCodec codec = mock(WechatVirtualPaymentNotificationCodec.class);
        when(codec.extractEncrypted("<xml/>" )).thenReturn(null);
        when(security.verifyPlaintextSignature("bad-signature", "1720000001", "nonce"))
                .thenReturn(false);
        WechatVirtualPaymentCallbackService service = callbackService(
                enabledProperties(), security, codec, mock(WechatVirtualPaymentNotificationService.class));

        WechatVirtualPaymentCallbackService.CallbackResult result = service.notify(
                "<xml/>", "bad-signature", null, "1720000001", "nonce");

        assertThat(result.statusCode()).isEqualTo(403);
        assertThat(result.body()).isEqualTo(
                "<xml><return_code>FAIL</return_code><return_msg>RETRY</return_msg></xml>");
    }

    /** 功能关闭时地址校验和通知均应返回未找到。 */
    @Test
    void disabledCallbackShouldKeepNotFoundResponse() {
        WechatVirtualPaymentProperties properties = enabledProperties();
        properties.setEnabled(false);
        WechatVirtualPaymentCallbackService service = callbackService(
                properties, mock(WechatVirtualPaymentMessageSecurity.class),
                mock(WechatVirtualPaymentNotificationCodec.class),
                mock(WechatVirtualPaymentNotificationService.class));

        assertThat(service.verify(null, null, "1", "2", "3").statusCode()).isEqualTo(404);
        assertThat(service.notify("<xml/>", null, null, "1", "2").statusCode()).isEqualTo(404);
    }

    /** INFO 日志关闭时不得执行报文脱敏和 XML 解析。 */
    @Test
    void disabledInfoLoggingShouldSkipSanitization() {
        Logger logger = (Logger) LoggerFactory.getLogger(WechatVirtualPaymentCallbackService.class);
        Level originalLevel = logger.getLevel();
        WechatInteractionLogSanitizer sanitizer = mock(WechatInteractionLogSanitizer.class);
        WechatVirtualPaymentProperties properties = enabledProperties();
        properties.setEnabled(false);
        WechatVirtualPaymentCallbackService service = new WechatVirtualPaymentCallbackService(
                properties, mock(WechatVirtualPaymentMessageSecurity.class),
                mock(WechatVirtualPaymentNotificationCodec.class),
                mock(WechatVirtualPaymentNotificationService.class), sanitizer);
        try {
            logger.setLevel(Level.WARN);

            service.verify(null, null, "1", "2", "3");
            service.notify("<xml/>", null, null, "1", "2");

            verifyNoInteractions(sanitizer);
        } finally {
            logger.setLevel(originalLevel);
        }
    }

    /** 回调结果构造器必须收紧，避免绕过受约束的工厂方法。 */
    @Test
    void callbackResultConstructorsShouldBePrivate() {
        assertThat(Arrays.stream(WechatVirtualPaymentCallbackService.CallbackResult.class
                        .getDeclaredConstructors()))
                .allMatch(constructor -> Modifier.isPrivate(constructor.getModifiers()));
    }

    /** 构造被测回调服务。 */
    private WechatVirtualPaymentCallbackService callbackService(
            WechatVirtualPaymentProperties properties,
            WechatVirtualPaymentMessageSecurity security,
            WechatVirtualPaymentNotificationCodec codec,
            WechatVirtualPaymentNotificationService notificationService
    ) {
        return new WechatVirtualPaymentCallbackService(
                properties, security, codec, notificationService, new WechatInteractionLogSanitizer());
    }

    /** @return 已开启的虚拟支付配置。 */
    private WechatVirtualPaymentProperties enabledProperties() {
        WechatVirtualPaymentProperties properties = new WechatVirtualPaymentProperties();
        properties.setEnabled(true);
        return properties;
    }

    /** @return 微信成功回调报文。 */
    private String successResponse() {
        return "<xml><return_code>SUCCESS</return_code><return_msg>OK</return_msg></xml>";
    }
}
