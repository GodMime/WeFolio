package com.jxc.wefolio.controller;

import com.jxc.wefolio.annotation.SystemAccess;
import com.jxc.wefolio.common.Response;
import com.jxc.wefolio.constant.PointConstants;
import com.jxc.wefolio.dto.VirtualPaymentTaskExecutionResponse;
import com.jxc.wefolio.service.payment.RechargeOrderReconciliationService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

/** job 调用的充值订单单条核对接口，只承担 HTTP 参数和响应适配。 */
@SystemAccess
@RestController
@RequiredArgsConstructor
public class RechargeOrderReconciliationController {
    /** 后台单订单核对路由。 */
    private static final String RECONCILIATION_PATH =
            "/api/admin/points/virtual-payment/recharge-orders/{orderId}/reconciliations";
    /** 负责鉴权、核对和恢复的应用服务。 */
    private final RechargeOrderReconciliationService service;

    /** 原样委派内部密钥和订单 ID。 */
    @PostMapping(RECONCILIATION_PATH)
    public Response<VirtualPaymentTaskExecutionResponse> reconcile(
            @RequestHeader(value = PointConstants.ADMIN_POINT_SECRET_HEADER, required = false) String secret,
            @PathVariable Long orderId) {
        return Response.success(service.reconcile(secret, orderId));
    }
}
