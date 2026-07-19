package com.jxc.wefolio.service.payment;

/**
 * 微信虚拟支付扣币请求。
 *
 * @param userId 本地用户 ID
 * @param taskNo 本地扣币任务号
 * @param openid 微信 openid
 * @param sessionKey 微信会话密钥
 * @param userIp 本次会话可信客户端 IP
 * @param orderId 稳定微信订单号
 * @param amount 扣币数量
 * @param timestampSeconds 请求时间戳秒数；重放时保持不变
 */
public record WechatCurrencyPayRequest(
        Long userId,
        String taskNo,
        String openid,
        String sessionKey,
        String userIp,
        String orderId,
        long amount,
        long timestampSeconds
) {
}
