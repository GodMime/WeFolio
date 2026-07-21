package com.jxc.wefolio.service.point;

import java.util.List;

/**
 * 赠送订单创建结果。
 *
 * @param orders 本次命令对应的订单结果
 */
public record GiftOrderResult(List<GiftOrderItem> orders) {

    /**
     * 单笔赠送订单结果。
     *
     * @param orderId 本地赠送订单 ID
     * @param orderNo 稳定微信赠送订单号
     * @param userId 受赠用户 ID
     * @param status 当前订单状态
     * @param idempotent 是否命中已有订单
     */
    public record GiftOrderItem(
            Long orderId,
            String orderNo,
            Long userId,
            String status,
            boolean idempotent
    ) {
    }
}
