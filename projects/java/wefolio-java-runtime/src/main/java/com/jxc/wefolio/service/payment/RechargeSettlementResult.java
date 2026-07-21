package com.jxc.wefolio.service.payment;

import com.jxc.wefolio.entity.RechargeOrderEntity;

/**
 * 充值结算结果。
 *
 * @param order 结算后的充值订单
 * @param balance 结算后的积分余额；幂等命中时可为空
 * @param idempotent 是否幂等命中
 */
public record RechargeSettlementResult(
        RechargeOrderEntity order,
        Long balance,
        boolean idempotent
) {
}
