package com.jxc.wefolio.controller;

import com.jxc.wefolio.service.payment.WechatVirtualPaymentCallbackService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.ResponseEntity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** 微信虚拟支付回调控制器测试。 */
@ExtendWith(MockitoExtension.class)
class WechatVirtualPaymentNotificationControllerTest {

    /** 微信虚拟支付回调应用服务模拟 */
    @Mock
    private WechatVirtualPaymentCallbackService callbackService;

    /** 地址校验参数必须原样委派并映射 HTTP 响应。 */
    @Test
    void verifyShouldDelegateParametersAndMapHttpResponse() {
        when(callbackService.verify("signature", "message-signature", "1720000000", "nonce", "echo"))
                .thenReturn(WechatVirtualPaymentCallbackService.CallbackResult.forbidden("签名无效"));
        WechatVirtualPaymentNotificationController controller =
                new WechatVirtualPaymentNotificationController(callbackService);

        ResponseEntity<String> response = controller.verify(
                "signature", "message-signature", "1720000000", "nonce", "echo");

        assertThat(response.getStatusCode().value()).isEqualTo(403);
        assertThat(response.getBody()).isEqualTo("签名无效");
        verify(callbackService).verify(
                "signature", "message-signature", "1720000000", "nonce", "echo");
    }

    /** 通知参数必须原样委派并映射 HTTP 响应。 */
    @Test
    void notifyShouldDelegateParametersAndMapHttpResponse() {
        String failureBody =
                "<xml><return_code>FAIL</return_code><return_msg>RETRY</return_msg></xml>";
        when(callbackService.notify("<xml/>", "signature", null, "1720000001", "nonce"))
                .thenReturn(WechatVirtualPaymentCallbackService.CallbackResult.badRequest(failureBody));
        WechatVirtualPaymentNotificationController controller =
                new WechatVirtualPaymentNotificationController(callbackService);

        ResponseEntity<String> response = controller.notify(
                "<xml/>", "signature", null, "1720000001", "nonce");

        assertThat(response.getStatusCode().value()).isEqualTo(400);
        assertThat(response.getBody()).isEqualTo(failureBody);
        verify(callbackService).notify("<xml/>", "signature", null, "1720000001", "nonce");
    }

    /** 无响应正文的应用结果必须映射为空 HTTP 响应。 */
    @Test
    void callbackWithoutBodyShouldKeepEmptyHttpResponse() {
        when(callbackService.verify(null, null, "1", "2", "3"))
                .thenReturn(WechatVirtualPaymentCallbackService.CallbackResult.notFound());
        WechatVirtualPaymentNotificationController controller =
                new WechatVirtualPaymentNotificationController(callbackService);

        ResponseEntity<String> response = controller.verify(null, null, "1", "2", "3");

        assertThat(response.getStatusCode().value()).isEqualTo(404);
        assertThat(response.hasBody()).isFalse();
    }
}
