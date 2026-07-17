package com.jxc.wefolio.service.payment;

import java.time.LocalDateTime;

/**
 * 微信支付边界接口 — 隔离官方 SDK 的请求模型和异常类型。
 */
public interface WechatPayClient {

    /**
     * 创建小程序预支付单。
     *
     * @param command 预支付命令
     * @return 小程序调起支付参数
     */
    PrepayResult prepay(PrepayCommand command);

    /**
     * 按商户订单号查询微信交易。
     *
     * @param merchantOrderNo 商户订单号
     * @return 标准化交易
     */
    Transaction queryByMerchantOrderNo(String merchantOrderNo);

    /**
     * 验签、解密并解析支付通知。
     *
     * @param request 原始通知
     * @return 标准化交易
     */
    Transaction parseNotification(NotificationRequest request);

    /**
     * 预支付命令。
     *
     * @param appId 小程序 AppID
     * @param merchantId 商户号
     * @param description 商品描述
     * @param merchantOrderNo 商户订单号
     * @param amountFen 支付金额，单位分
     * @param payerOpenId 付款人 openid
     * @param expireAt 订单过期时间
     * @param notifyUrl 通知地址
     */
    record PrepayCommand(
            String appId,
            String merchantId,
            String description,
            String merchantOrderNo,
            int amountFen,
            String payerOpenId,
            LocalDateTime expireAt,
            String notifyUrl
    ) {
    }

    /**
     * 小程序调起支付参数。
     *
     * @param prepayId 微信预支付标识
     * @param timeStamp 时间戳
     * @param nonceStr 随机串
     * @param packageValue 小程序 package 参数
     * @param signType 签名类型
     * @param paySign 支付签名
     */
    record PrepayResult(
            String prepayId,
            String timeStamp,
            String nonceStr,
            String packageValue,
            String signType,
            String paySign
    ) {
    }

    /**
     * 微信交易状态。
     */
    enum TradeState {
        /** 支付成功。 */
        SUCCESS,
        /** 转入退款。 */
        REFUND,
        /** 未支付。 */
        NOTPAY,
        /** 已关闭。 */
        CLOSED,
        /** 已撤销。 */
        REVOKED,
        /** 支付中。 */
        USERPAYING,
        /** 支付失败。 */
        PAYERROR,
        /** 已接收。 */
        ACCEPT
    }

    /**
     * 标准化微信交易。
     *
     * @param appId 小程序 AppID
     * @param merchantId 商户号
     * @param merchantOrderNo 商户订单号
     * @param transactionId 微信交易号
     * @param tradeState 交易状态
     * @param currency 币种
     * @param amountFen 支付金额，单位分
     * @param successTime 支付成功时间
     */
    record Transaction(
            String appId,
            String merchantId,
            String merchantOrderNo,
            String transactionId,
            TradeState tradeState,
            String currency,
            Integer amountFen,
            LocalDateTime successTime
    ) {
    }

    /**
     * 微信支付通知原始参数。
     *
     * @param serialNumber 微信支付签名序列号或公钥 ID
     * @param signature 签名
     * @param timestamp 时间戳
     * @param nonce 随机串
     * @param signType 签名类型
     * @param body 原始请求体
     */
    record NotificationRequest(
            String serialNumber,
            String signature,
            String timestamp,
            String nonce,
            String signType,
            String body
    ) {
    }
}
