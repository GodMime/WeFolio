package com.jxc.wefolio.dto;

/** runtime 月度作品存储结算结果。 */
public record WorkStorageBillingSettlementResponse(
        Long userId,
        String billingMonth,
        String status,
        long pointsDue,
        long pointsDeducted,
        boolean idempotent
) {
}
