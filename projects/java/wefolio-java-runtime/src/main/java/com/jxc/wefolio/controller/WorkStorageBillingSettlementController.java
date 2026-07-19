package com.jxc.wefolio.controller;

import com.jxc.wefolio.annotation.SystemAccess;
import com.jxc.wefolio.common.Response;
import com.jxc.wefolio.dto.WorkStorageBillingSettlementRequest;
import com.jxc.wefolio.dto.WorkStorageBillingSettlementResponse;
import com.jxc.wefolio.exception.BusinessException;
import com.jxc.wefolio.service.AdminPointSecretValidator;
import com.jxc.wefolio.service.WorkStorageBillingSettlementService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

import java.time.YearMonth;
import java.time.format.DateTimeParseException;

/** job 调用的 runtime 月度作品存储内部接口。 */
@SystemAccess
@RestController
@RequiredArgsConstructor
public class WorkStorageBillingSettlementController {

    private final AdminPointSecretValidator secretValidator;
    private final WorkStorageBillingSettlementService settlementService;

    /** 执行单用户账期结算。 */
    @PostMapping("/api/admin/points/work-storage-billing/settlements")
    public Response<WorkStorageBillingSettlementResponse> settle(
            @RequestHeader(value = "X-Admin-Point-Secret", required = false) String secret,
            @RequestBody WorkStorageBillingSettlementRequest request
    ) {
        secretValidator.validate(secret);
        if (request == null) {
            throw new BusinessException("结算请求不能为空");
        }
        try {
            return Response.success(settlementService.settle(
                    request.getUserId(), YearMonth.parse(request.getBillingMonth())));
        } catch (DateTimeParseException | NullPointerException exception) {
            throw new BusinessException("账期格式必须为 yyyy-MM");
        }
    }
}
