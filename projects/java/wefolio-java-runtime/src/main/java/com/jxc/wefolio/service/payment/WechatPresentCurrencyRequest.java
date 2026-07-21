package com.jxc.wefolio.service.payment;

/**
 * 微信虚拟支付赠送代币请求。
 */
public record WechatPresentCurrencyRequest(
        Long userId,
        String localOrderNo,
        String openid,
        String orderId,
        long amount,
        long timestampSeconds
) {
}
