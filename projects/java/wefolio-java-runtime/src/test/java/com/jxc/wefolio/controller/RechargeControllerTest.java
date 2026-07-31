package com.jxc.wefolio.controller;

import com.jxc.wefolio.annotation.MaintainerAccess;
import com.jxc.wefolio.annotation.SystemAccess;
import com.jxc.wefolio.common.Response;
import com.jxc.wefolio.common.auth.AuthContext;
import com.jxc.wefolio.common.auth.AuthContextHolder;
import com.jxc.wefolio.dto.CreateRechargeOrderRequest;
import com.jxc.wefolio.dto.CreateRechargeOrderResponse;
import com.jxc.wefolio.dto.RechargeOrderSyncResponse;
import com.jxc.wefolio.dto.RechargeOrdersResponse;
import com.jxc.wefolio.dto.RechargePageResponse;
import com.jxc.wefolio.service.payment.RechargeService;
import com.jxc.wefolio.service.payment.WechatPayClient;
import com.jxc.wefolio.service.payment.WechatPayNotificationException;
import com.jxc.wefolio.service.payment.WechatPaySignatureException;
import com.jxc.wefolio.service.payment.WechatRechargeNotificationService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

import java.lang.reflect.Method;
import java.lang.reflect.Parameter;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 充值维护者接口和微信通知接口测试。
 */
@ExtendWith(MockitoExtension.class)
class RechargeControllerTest {

    /** 充值服务模拟。 */
    @Mock
    private RechargeService rechargeService;

    /** 微信通知服务模拟。 */
    @Mock
    private WechatRechargeNotificationService notificationService;

    @BeforeEach
    void setUp() {
        AuthContextHolder.set(new AuthContext(7L, "wf-dev-user-7"));
    }

    @AfterEach
    void tearDown() {
        AuthContextHolder.clear();
    }

    @Test
    void maintainerRechargeEndpointsShouldUseExactPathsAndCurrentUser() throws NoSuchMethodException {
        MineRechargeController controller = new MineRechargeController(rechargeService);
        RechargePageResponse pageResponse = new RechargePageResponse();
        CreateRechargeOrderRequest request = new CreateRechargeOrderRequest();
        CreateRechargeOrderResponse createResponse = new CreateRechargeOrderResponse();
        RechargeOrdersResponse recordsResponse = new RechargeOrdersResponse();
        RechargeOrderSyncResponse syncResponse = new RechargeOrderSyncResponse();
        when(rechargeService.getPage(7L)).thenReturn(pageResponse);
        when(rechargeService.createOrder(7L, request)).thenReturn(createResponse);
        when(rechargeService.listOrders(7L, 1, 20)).thenReturn(recordsResponse);
        when(rechargeService.syncOrder(7L, "WFR1")).thenReturn(syncResponse);

        Response<RechargePageResponse> page = controller.page();
        Response<CreateRechargeOrderResponse> created = controller.createOrder(request);
        Response<RechargeOrdersResponse> records = controller.orders(1, 20);
        Response<RechargeOrderSyncResponse> synced = controller.syncOrder("WFR1");

        assertThat(MineRechargeController.class.isAnnotationPresent(MaintainerAccess.class)).isTrue();
        assertThat(MineRechargeController.class.getMethod("page").getAnnotation(GetMapping.class).value())
                .containsExactly("/api/mine/recharges");
        assertThat(MineRechargeController.class
                .getMethod("createOrder", CreateRechargeOrderRequest.class)
                .getAnnotation(PostMapping.class).value())
                .containsExactly("/api/mine/recharges/orders");
        assertThat(MineRechargeController.class
                .getMethod("orders", int.class, int.class)
                .getAnnotation(GetMapping.class).value())
                .containsExactly("/api/mine/recharges/orders");
        assertThat(MineRechargeController.class
                .getMethod("syncOrder", String.class)
                .getAnnotation(PostMapping.class).value())
                .containsExactly("/api/mine/recharges/orders/{merchantOrderNo}/sync");
        assertThat(page.getData()).isSameAs(pageResponse);
        assertThat(created.getData()).isSameAs(createResponse);
        assertThat(records.getData()).isSameAs(recordsResponse);
        assertThat(synced.getData()).isSameAs(syncResponse);
    }

    @Test
    void notificationControllerShouldForwardAllHeadersAndRawBody() {
        WechatPayNotificationController controller = new WechatPayNotificationController(notificationService);

        ResponseEntity<Void> response = controller.notifyRecharge(
                "PUB_KEY_ID_123", "signature", "1784273400", "nonce",
                "WECHATPAY2-SHA256-RSA2048", "{\"id\":\"notification-1\"}");

        assertThat(WechatPayNotificationController.class.isAnnotationPresent(SystemAccess.class)).isTrue();
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        verify(notificationService).handle(new WechatPayClient.NotificationRequest(
                "PUB_KEY_ID_123", "signature", "1784273400", "nonce",
                "WECHATPAY2-SHA256-RSA2048", "{\"id\":\"notification-1\"}"));
    }

    @Test
    void notificationControllerShouldExposeExactPublicPostContract() throws NoSuchMethodException {
        Method method = WechatPayNotificationController.class.getMethod(
                "notifyRecharge", String.class, String.class, String.class,
                String.class, String.class, String.class);
        Parameter[] parameters = method.getParameters();

        assertThat(WechatPayNotificationController.class.isAnnotationPresent(RestController.class)).isTrue();
        assertThat(WechatPayNotificationController.class.isAnnotationPresent(SystemAccess.class)).isTrue();
        assertThat(method.getAnnotation(PostMapping.class).value())
                .containsExactly("/api/payment/wechat/recharge/notify");
        assertThat(parameters[0].getAnnotation(RequestHeader.class).value())
                .isEqualTo("Wechatpay-Serial");
        assertThat(parameters[1].getAnnotation(RequestHeader.class).value())
                .isEqualTo("Wechatpay-Signature");
        assertThat(parameters[2].getAnnotation(RequestHeader.class).value())
                .isEqualTo("Wechatpay-Timestamp");
        assertThat(parameters[3].getAnnotation(RequestHeader.class).value())
                .isEqualTo("Wechatpay-Nonce");
        assertThat(parameters[4].getAnnotation(RequestHeader.class).value())
                .isEqualTo("Wechatpay-Signature-Type");
        assertThat(parameters[4].getAnnotation(RequestHeader.class).required()).isFalse();
        assertThat(parameters[5].isAnnotationPresent(RequestBody.class)).isTrue();
    }

    @Test
    void notificationControllerShouldReturnUnauthorizedForSignatureFailure() {
        WechatPayNotificationController controller = new WechatPayNotificationController(notificationService);
        WechatPayClient.NotificationRequest request = new WechatPayClient.NotificationRequest(
                "PUB_KEY_ID_123", "signature", "1784273400", "nonce", null, "{}");
        when(notificationService.handle(request))
                .thenThrow(new WechatPaySignatureException("微信支付通知验签失败", null));

        ResponseEntity<Void> response = controller.notifyRecharge(
                "PUB_KEY_ID_123", "signature", "1784273400", "nonce", null, "{}");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    void notificationControllerShouldReturnBadRequestForMalformedNotification() {
        WechatPayNotificationController controller = new WechatPayNotificationController(notificationService);
        WechatPayClient.NotificationRequest request = new WechatPayClient.NotificationRequest(
                "PUB_KEY_ID_123", "signature", "1784273400", "nonce", null, "{}");
        when(notificationService.handle(request))
                .thenThrow(new WechatPayNotificationException("微信支付通知内容无效", null));

        ResponseEntity<Void> response = controller.notifyRecharge(
                "PUB_KEY_ID_123", "signature", "1784273400", "nonce", null, "{}");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }
}
