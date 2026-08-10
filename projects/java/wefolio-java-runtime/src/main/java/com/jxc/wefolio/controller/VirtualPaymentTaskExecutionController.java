package com.jxc.wefolio.controller;

import com.jxc.wefolio.annotation.SystemAccess;
import com.jxc.wefolio.common.Response;
import com.jxc.wefolio.dto.VirtualPaymentDebitTaskRecoveryResponse;
import com.jxc.wefolio.dto.VirtualPaymentTaskExecutionResponse;
import com.jxc.wefolio.service.payment.VirtualPaymentTaskExecutionApplicationService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

/**
 * job 调用的微信虚拟支付单任务内部接口。
 */
@SystemAccess
@RestController
@RequiredArgsConstructor
public class VirtualPaymentTaskExecutionController {

    /** 后台积分内部密钥请求头。 */
    private static final String ADMIN_POINT_SECRET_HEADER = "X-Admin-Point-Secret";

    /** 赠送订单执行路径。 */
    private static final String GIFT_ORDER_EXECUTION_PATH =
            "/api/admin/points/virtual-payment/gift-orders/{orderId}/executions";

    /** 扣币任务执行路径。 */
    private static final String DEBIT_TASK_EXECUTION_PATH =
            "/api/admin/points/virtual-payment/debit-tasks/{taskId}/executions";

    /** 扣币任务恢复路径。 */
    private static final String DEBIT_TASK_RECOVERY_PATH =
            "/api/admin/points/virtual-payment/users/{userId}/debit-task-recoveries";

    /** 微信虚拟支付单任务执行应用服务。 */
    private final VirtualPaymentTaskExecutionApplicationService executionApplicationService;

    /** 执行单条赠送订单。 */
    @PostMapping(GIFT_ORDER_EXECUTION_PATH)
    public Response<VirtualPaymentTaskExecutionResponse> executeGiftOrder(
            @RequestHeader(value = ADMIN_POINT_SECRET_HEADER, required = false) String secret,
            @PathVariable Long orderId
    ) {
        return Response.success(executionApplicationService.executeGiftOrder(secret, orderId));
    }

    /** 执行单条扣币任务。 */
    @PostMapping(DEBIT_TASK_EXECUTION_PATH)
    public Response<VirtualPaymentTaskExecutionResponse> executeDebitTask(
            @RequestHeader(value = ADMIN_POINT_SECRET_HEADER, required = false) String secret,
            @PathVariable Long taskId
    ) {
        return Response.success(executionApplicationService.executeDebitTask(secret, taskId));
    }

    /** 为指定用户恢复缺失的活动扣币任务。 */
    @PostMapping(DEBIT_TASK_RECOVERY_PATH)
    public Response<VirtualPaymentDebitTaskRecoveryResponse> recoverDebitTask(
            @RequestHeader(value = ADMIN_POINT_SECRET_HEADER, required = false) String secret,
            @PathVariable Long userId
    ) {
        return Response.success(executionApplicationService.recoverDebitTask(secret, userId));
    }
}
