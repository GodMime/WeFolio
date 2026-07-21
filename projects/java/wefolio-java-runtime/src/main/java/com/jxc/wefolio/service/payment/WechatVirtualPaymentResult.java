package com.jxc.wefolio.service.payment;

/**
 * 微信虚拟支付统一响应。
 */
public record WechatVirtualPaymentResult(
        Integer errorCode,
        String errorMessage,
        WechatVirtualPaymentErrorType errorType,
        long balance,
        long presentBalance,
        long usedPresentAmount,
        String orderStatus,
        long buyQuantity,
        long payAmount,
        int httpStatus,
        String openid,
        Integer environment,
        String remoteOrderId
) {

    /** 不需要订单身份字段的余额、扣币和赠送响应兼容构造器。 */
    public WechatVirtualPaymentResult(
            Integer errorCode,
            String errorMessage,
            WechatVirtualPaymentErrorType errorType,
            long balance,
            long presentBalance,
            long usedPresentAmount,
            String orderStatus,
            long buyQuantity,
            long payAmount,
            int httpStatus
    ) {
        this(errorCode, errorMessage, errorType, balance, presentBalance, usedPresentAmount,
                orderStatus, buyQuantity, payAmount, httpStatus, null, null, null);
    }

    /** @return 是否可按成功结果落库。 */
    public boolean isSuccessful() {
        return errorType == WechatVirtualPaymentErrorType.SUCCESS
                || errorType == WechatVirtualPaymentErrorType.DUPLICATE_SUCCESS;
    }
}
