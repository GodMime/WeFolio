package com.jxc.wefolio.service.point;

/**
 * 微信代币赠送命令 — 来源业务事务内只创建独立幂等赠送订单。
 *
 * @param userId 受赠用户 ID
 * @param sceneCode 五类赠送场景之一
 * @param amount 赠送积分，必须为正整数
 * @param businessType 来源业务类型
 * @param businessId 来源业务 ID
 * @param businessSnapshot 不可变来源快照 JSON
 * @param idempotencyKey 业务幂等键
 */
public record GiftCommand(
        Long userId,
        String sceneCode,
        long amount,
        String businessType,
        String businessId,
        String businessSnapshot,
        String idempotencyKey
) {
}
