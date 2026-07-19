package com.jxc.wefolio.service.payment;

/**
 * 微信虚拟支付用户余额查询请求。
 */
public record WechatBalanceQueryRequest(
        Long userId,
        String referenceNo,
        String openid,
        String sessionKey,
        String userIp,
        long timestampSeconds
) {
}
