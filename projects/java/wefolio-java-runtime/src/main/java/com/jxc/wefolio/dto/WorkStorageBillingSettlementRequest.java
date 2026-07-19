package com.jxc.wefolio.dto;

import lombok.Data;

/** runtime 月度作品存储结算请求。 */
@Data
public class WorkStorageBillingSettlementRequest {
    /** 用户 ID。 */
    private Long userId;
    /** 账期 yyyy-MM。 */
    private String billingMonth;
}
