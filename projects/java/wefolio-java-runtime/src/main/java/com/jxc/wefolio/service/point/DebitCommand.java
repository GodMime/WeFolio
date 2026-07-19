package com.jxc.wefolio.service.point;

/**
 * 积分扣除命令 — 携带账户归属、业务幂等身份和不可变计算快照。
 *
 * @param userId 被扣费维护者用户 ID
 * @param ruleId 采用的积分规则 ID，可空
 * @param sceneCode 积分场景编码
 * @param businessType 来源业务类型
 * @param businessId 来源业务 ID
 * @param calculationSnapshot 不可变计算快照 JSON
 * @param idempotencyKey 业务幂等键
 * @param remark 脱敏备注
 * @param points 完整扣除积分，必须为正整数
 */
public record DebitCommand(
        Long userId,
        Long ruleId,
        String sceneCode,
        String businessType,
        String businessId,
        String calculationSnapshot,
        String idempotencyKey,
        String remark,
        long points
) {
}
