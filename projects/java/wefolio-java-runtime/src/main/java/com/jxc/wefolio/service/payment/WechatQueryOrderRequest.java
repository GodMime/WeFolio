package com.jxc.wefolio.service.payment;

/**
 * 微信虚拟支付订单查询请求。
 */
public record WechatQueryOrderRequest(
        Long userId,
        String localOrderNo,
        String openid,
        String sessionKey,
        String userIp,
        String orderId,
        long timestampSeconds
) {
}
