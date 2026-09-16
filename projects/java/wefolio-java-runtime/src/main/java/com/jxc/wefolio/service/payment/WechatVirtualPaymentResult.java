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

    /**
     * 使用补查的完整余额，保留资金操作自身的分类与赠送币用量。
     * 重复应答的 usedPresentAmount 为未知占位 0，不能视为实际未消耗赠送币；
     * 余额查询无法还原该次用量，审计时需结合任务保留的成功分类区分。
     */
    public WechatVirtualPaymentResult withBalanceSnapshot(WechatVirtualPaymentResult snapshot) {
        return new WechatVirtualPaymentResult(errorCode, errorMessage, errorType,
                snapshot.balance(), snapshot.presentBalance(), usedPresentAmount, orderStatus,
                buyQuantity, payAmount, httpStatus, openid, environment, remoteOrderId);
    }

    /** @return 微信是否确认操作成功；扣币及重复应答仍需补齐余额后才能结算。 */
    public boolean isSuccessful() {
        return errorType == WechatVirtualPaymentErrorType.SUCCESS
                || errorType == WechatVirtualPaymentErrorType.DUPLICATE_SUCCESS;
    }
}
