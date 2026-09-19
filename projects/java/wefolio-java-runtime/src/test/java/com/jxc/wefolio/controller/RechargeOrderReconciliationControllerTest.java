package com.jxc.wefolio.controller;

import com.jxc.wefolio.annotation.SystemAccess;
import com.jxc.wefolio.constant.PointConstants;
import com.jxc.wefolio.dto.VirtualPaymentTaskExecutionResponse;
import com.jxc.wefolio.service.payment.RechargeOrderReconciliationService;
import org.junit.jupiter.api.Test;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

/** 内部充值核对 Controller 只验证鉴权参数委派与稳定 HTTP 契约。 */
class RechargeOrderReconciliationControllerTest {
    /** 请求密钥、订单 ID 和响应均原样委派，不在 Controller 推导业务结果。 */
    @Test
    void shouldDelegateAndExposeInternalContract() throws Exception {
        RechargeOrderReconciliationService service = mock(RechargeOrderReconciliationService.class);
        VirtualPaymentTaskExecutionResponse result = new VirtualPaymentTaskExecutionResponse(11L, "RECHARGE_ORDER", "RETRY_WAIT");
        when(service.reconcile("secret", 11L)).thenReturn(result);
        RechargeOrderReconciliationController controller = new RechargeOrderReconciliationController(service);
        assertThat(controller.reconcile("secret", 11L).getData()).isSameAs(result);
        verify(service).reconcile("secret", 11L);
        assertThat(RechargeOrderReconciliationController.class.isAnnotationPresent(SystemAccess.class)).isTrue();
        var method = RechargeOrderReconciliationController.class.getDeclaredMethod("reconcile", String.class, Long.class);
        assertThat(method.getAnnotation(PostMapping.class).value()).containsExactly(
                "/api/admin/points/virtual-payment/recharge-orders/{orderId}/reconciliations");
        assertThat(method.getParameters()[0].getAnnotation(RequestHeader.class).value())
                .isEqualTo(PointConstants.ADMIN_POINT_SECRET_HEADER);
    }
}
