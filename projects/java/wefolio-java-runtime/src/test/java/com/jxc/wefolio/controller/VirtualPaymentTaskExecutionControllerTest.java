package com.jxc.wefolio.controller;

import com.jxc.wefolio.annotation.SystemAccess;
import com.jxc.wefolio.common.Response;
import com.jxc.wefolio.dto.VirtualPaymentDebitTaskRecoveryResponse;
import com.jxc.wefolio.dto.VirtualPaymentTaskExecutionResponse;
import com.jxc.wefolio.service.payment.VirtualPaymentTaskExecutionApplicationService;
import org.junit.jupiter.api.Test;
import org.springframework.web.bind.annotation.PostMapping;

import java.lang.reflect.Method;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 微信虚拟支付单任务执行控制器测试。
 */
class VirtualPaymentTaskExecutionControllerTest {

    /** 三个入口必须原样委派参数和应用结果。 */
    @Test
    void endpointsShouldDelegateWithoutBusinessLogic() {
        VirtualPaymentTaskExecutionApplicationService service =
                mock(VirtualPaymentTaskExecutionApplicationService.class);
        VirtualPaymentTaskExecutionResponse gift =
                new VirtualPaymentTaskExecutionResponse(17L, "GIFT_ORDER", "PROCESSED");
        VirtualPaymentTaskExecutionResponse debit =
                new VirtualPaymentTaskExecutionResponse(23L, "DEBIT_TASK", "SKIPPED_NOT_CLAIMABLE");
        VirtualPaymentDebitTaskRecoveryResponse recovery =
                new VirtualPaymentDebitTaskRecoveryResponse(7L, 31L, "ACTIVE_TASK_ENSURED");
        when(service.executeGiftOrder("secret", 17L)).thenReturn(gift);
        when(service.executeDebitTask("secret", 23L)).thenReturn(debit);
        when(service.recoverDebitTask("secret", 7L)).thenReturn(recovery);
        VirtualPaymentTaskExecutionController controller =
                new VirtualPaymentTaskExecutionController(service);

        Response<VirtualPaymentTaskExecutionResponse> giftResponse =
                controller.executeGiftOrder("secret", 17L);
        Response<VirtualPaymentTaskExecutionResponse> debitResponse =
                controller.executeDebitTask("secret", 23L);
        Response<VirtualPaymentDebitTaskRecoveryResponse> recoveryResponse =
                controller.recoverDebitTask("secret", 7L);

        assertThat(giftResponse.getData()).isSameAs(gift);
        assertThat(debitResponse.getData()).isSameAs(debit);
        assertThat(recoveryResponse.getData()).isSameAs(recovery);
        verify(service).executeGiftOrder("secret", 17L);
        verify(service).executeDebitTask("secret", 23L);
        verify(service).recoverDebitTask("secret", 7L);
    }

    /** 控制器必须受系统访问控制保护并固定三个 POST 路径。 */
    @Test
    void controllerShouldDeclareSystemAccessAndStablePostPaths() throws Exception {
        assertThat(VirtualPaymentTaskExecutionController.class.isAnnotationPresent(SystemAccess.class)).isTrue();
        assertPath("executeGiftOrder", "/api/admin/points/virtual-payment/gift-orders/{orderId}/executions");
        assertPath("executeDebitTask", "/api/admin/points/virtual-payment/debit-tasks/{taskId}/executions");
        assertPath("recoverDebitTask", "/api/admin/points/virtual-payment/users/{userId}/debit-task-recoveries");
    }

    /** 断言指定方法的 POST 路径。 */
    private void assertPath(String methodName, String expectedPath) throws Exception {
        Method method = VirtualPaymentTaskExecutionController.class
                .getDeclaredMethod(methodName, String.class, Long.class);
        assertThat(method.getAnnotation(PostMapping.class).value()).containsExactly(expectedPath);
    }
}
