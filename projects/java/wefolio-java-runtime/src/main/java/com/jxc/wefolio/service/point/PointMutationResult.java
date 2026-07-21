package com.jxc.wefolio.service.point;

/**
 * 本地积分扣除结果。
 *
 * @param transactionId 积分流水 ID
 * @param accountId 积分账户 ID
 * @param points 完整扣除积分
 * @param balanceBefore 扣除前实际可用积分
 * @param balanceAfter 扣除后实际可用积分
 * @param idempotent 是否命中已有幂等流水
 */
public record PointMutationResult(
        Long transactionId,
        Long accountId,
        long points,
        long balanceBefore,
        long balanceAfter,
        boolean idempotent
) {
}
