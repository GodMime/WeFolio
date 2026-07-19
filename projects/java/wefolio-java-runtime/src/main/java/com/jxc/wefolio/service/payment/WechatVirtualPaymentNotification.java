package com.jxc.wefolio.service.payment;

/**
 * 已验签并解密的微信虚拟支付通知。
 *
 * @param type 通知类型
 * @param orderNo 本地商户订单号，iOS 退款问询可为空
 */
public record WechatVirtualPaymentNotification(String type, String orderNo) {
}
