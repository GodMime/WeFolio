package com.jxc.wefolio.controller;

import com.jxc.wefolio.annotation.SystemAccess;
import com.jxc.wefolio.common.Response;
import com.jxc.wefolio.dto.WorkStorageBillingSettlementRequest;
import com.jxc.wefolio.dto.WorkStorageBillingSettlementResponse;
import com.jxc.wefolio.service.WorkStorageBillingSettlementApplicationService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

import static com.jxc.wefolio.constant.PointConstants.ADMIN_POINT_SECRET_HEADER;

/** job 调用的 runtime 月度作品存储内部接口。 */
@SystemAccess
@RestController
@RequiredArgsConstructor
public class WorkStorageBillingSettlementController {

    /** 月度作品存储结算入口应用服务 */
    private final WorkStorageBillingSettlementApplicationService settlementApplicationService;

    /** 执行单用户账期结算。 */
    @PostMapping("/api/admin/points/work-storage-billing/settlements")
    public Response<WorkStorageBillingSettlementResponse> settle(
            @RequestHeader(value = ADMIN_POINT_SECRET_HEADER, required = false) String secret,
            @RequestBody WorkStorageBillingSettlementRequest request
    ) {
        return Response.success(settlementApplicationService.settle(secret, request));
    }
}
