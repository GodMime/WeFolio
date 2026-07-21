package com.jxc.wefolio.service;

import com.jxc.wefolio.dto.WorkStorageBillingSettlementRequest;
import com.jxc.wefolio.dto.WorkStorageBillingSettlementResponse;
import com.jxc.wefolio.exception.BusinessException;
import com.jxc.wefolio.message.PointMessage;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.YearMonth;
import java.time.format.DateTimeParseException;

/** runtime 月度作品存储结算入口应用服务。 */
@Service
@RequiredArgsConstructor
public class WorkStorageBillingSettlementApplicationService {

    /** 后台积分密钥校验器 */
    private final AdminPointSecretValidator secretValidator;

    /** 月度作品存储事务结算服务 */
    private final WorkStorageBillingSettlementService settlementService;

    /** 校验内部请求并执行单用户账期结算。 */
    public WorkStorageBillingSettlementResponse settle(
            String secret,
            WorkStorageBillingSettlementRequest request
    ) {
        secretValidator.validate(secret);
        if (request == null) {
            throw new BusinessException(PointMessage.STORAGE_SETTLEMENT_REQUEST_REQUIRED_MESSAGE);
        }
        YearMonth billingMonth;
        try {
            billingMonth = YearMonth.parse(request.getBillingMonth());
        } catch (DateTimeParseException | NullPointerException exception) {
            throw new BusinessException(
                    PointMessage.STORAGE_SETTLEMENT_MONTH_INVALID_MESSAGE, exception);
        }
        return settlementService.settle(request.getUserId(), billingMonth);
    }
}
