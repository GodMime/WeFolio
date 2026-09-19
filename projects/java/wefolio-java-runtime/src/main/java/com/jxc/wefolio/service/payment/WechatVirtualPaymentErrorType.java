package com.jxc.wefolio.service.payment;

/**
 * 微信虚拟支付调用结果分类。
 *
 * <p>SUCCESS 与 DUPLICATE_SUCCESS 的枚举名称会持久化到任务和赠送订单的 last_error_code，
 * PointDebitTaskEntityMapper 与 PointGiftOrderEntityMapper 的人工重试 SQL 用相同字面量保留成功事实。
 * 修改这两个名称必须同步 SQL，并兼容已有记录，避免恢复时重复请求扣币或赠送。</p>
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
