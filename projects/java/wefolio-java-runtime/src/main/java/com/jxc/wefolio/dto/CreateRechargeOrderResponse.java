package com.jxc.wefolio.dto;

import lombok.Data;

/**
 * 创建充值订单响应 — 可直接映射到小程序 requestPayment。
 */
@Data
public class CreateRechargeOrderResponse {

    /** 商户订单号。 */
    private String merchantOrderNo;

    /** 本地订单状态。 */
    private String status;

    /** 支付时间戳。 */
    private String timeStamp;

    /** 支付随机串。 */
    private String nonceStr;

    /** 小程序支付 package 参数。 */
    private String packageValue;

    /** 签名类型。 */
    private String signType;

    /** 支付签名。 */
    private String paySign;
}
