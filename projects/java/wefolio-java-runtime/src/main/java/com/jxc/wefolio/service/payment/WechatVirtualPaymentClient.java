package com.jxc.wefolio.service.payment;

/**
 * 微信虚拟支付客户端。
 */
public interface WechatVirtualPaymentClient {

    /** 查询用户微信代币余额。 */
    WechatVirtualPaymentResult queryUserBalance(WechatBalanceQueryRequest request);

    /** 扣减用户微信代币。 */
    WechatVirtualPaymentResult currencyPay(WechatCurrencyPayRequest request);

    /** 赠送用户微信代币。 */
    WechatVirtualPaymentResult presentCurrency(WechatPresentCurrencyRequest request);

    /** 查询微信虚拟支付订单。 */
    WechatVirtualPaymentResult queryOrder(WechatQueryOrderRequest request);
}
