package com.jxc.wefolio.dto;

import lombok.Data;

/**
 * 创建充值订单响应 — 可直接映射到小程序 requestVirtualPayment。
 */
@Data
public class CreateRechargeOrderResponse {

    /** 商户订单号。 */
    private String merchantOrderNo;

    /** 本地订单状态。 */
    private String status;

    /** 虚拟支付模式。 */
    private String mode;

    /** 一次序列化并原样签名的请求正文。 */
    private String signData;

    /** AppKey 支付签名。 */
    private String paySig;

    /** 维护者 session_key 用户签名。 */
    private String signature;
}
