package com.jxc.wefolio.service.payment;

/**
 * 微信虚拟支付调用结果分类。
 */
public enum WechatVirtualPaymentErrorType {
    SUCCESS,
    DUPLICATE_SUCCESS,
    SESSION_INVALID,
    INSUFFICIENT_BALANCE,
    TRANSIENT,
    RATE_LIMITED,
    ACCESS_TOKEN_REJECTED,
    PERMANENT,
    CONFIGURATION,
    UNKNOWN
}
