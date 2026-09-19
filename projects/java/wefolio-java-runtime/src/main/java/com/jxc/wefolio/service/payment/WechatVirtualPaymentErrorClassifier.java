package com.jxc.wefolio.service.payment;

import org.springframework.stereotype.Component;

import java.util.Set;

/**
 * 微信虚拟支付错误分类器。
 */
@Component
public class WechatVirtualPaymentErrorClassifier {

    /** AccessToken 无效或过期错误码。 */
    private static final Set<Integer> ACCESS_TOKEN_ERRORS = Set.of(40001, 40014, 42001);

    /** 明确的配置或商户能力错误码。 */
    private static final Set<Integer> CONFIGURATION_ERRORS = Set.of(268490001, 268490002, 268490003);

    /** 明确的请求参数、身份或签名错误码。 */
    private static final Set<Integer> PERMANENT_ERRORS = Set.of(40003, 40013, 40125, 41001, 41002, 41003);

    /**
     * 分类微信响应。
     *
     * @param errorCode 微信 errcode，可为空
     * @param httpStatus HTTP 状态码
     * @return 稳定错误分类
     */
    public WechatVirtualPaymentErrorType classify(Integer errorCode, int httpStatus) {
        if (httpStatus == 429) {
            return WechatVirtualPaymentErrorType.RATE_LIMITED;
        }
        if (httpStatus >= 500) {
            return WechatVirtualPaymentErrorType.TRANSIENT;
        }
        if (errorCode == null) {
            return WechatVirtualPaymentErrorType.UNKNOWN;
        }
        if (errorCode == 0) {
            return httpStatus >= 200 && httpStatus < 300
                    ? WechatVirtualPaymentErrorType.SUCCESS : WechatVirtualPaymentErrorType.UNKNOWN;
        }
        // 微信业务错误码在 4xx 中仍有确定含义；不能把余额不足、会话失效降级成未知重试。
        if (errorCode == 268490004) {
            return WechatVirtualPaymentErrorType.DUPLICATE_SUCCESS;
        }
        if (errorCode == 268490009) {
            return WechatVirtualPaymentErrorType.SESSION_INVALID;
        }
        if (errorCode == 268490006) {
            return WechatVirtualPaymentErrorType.INSUFFICIENT_BALANCE;
        }
        if (errorCode == -1) {
            return WechatVirtualPaymentErrorType.TRANSIENT;
        }
        if (errorCode == 268490015) {
            return WechatVirtualPaymentErrorType.RATE_LIMITED;
        }
        if (ACCESS_TOKEN_ERRORS.contains(errorCode)) {
            return WechatVirtualPaymentErrorType.ACCESS_TOKEN_REJECTED;
        }
        if (CONFIGURATION_ERRORS.contains(errorCode)) {
            return WechatVirtualPaymentErrorType.CONFIGURATION;
        }
        if (PERMANENT_ERRORS.contains(errorCode)) {
            return WechatVirtualPaymentErrorType.PERMANENT;
        }
        return WechatVirtualPaymentErrorType.UNKNOWN;
    }
}
