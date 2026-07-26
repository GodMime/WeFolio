package com.jxc.wefolio.job.dict;

/**
 * 充值订单状态字典。
 */
public enum RechargeOrderStatusDict {

    /** 待支付。 */
    PENDING_PAYMENT("PENDING_PAYMENT"),

    /** 已支付。 */
    PAID("PAID"),

    /** 支付失败。 */
    PAYMENT_FAILED("PAYMENT_FAILED"),

    /** 已关闭。 */
    CLOSED("CLOSED"),

    /** 已退款。 */
    REFUNDED("REFUNDED");

    /** 状态编码。 */
    private final String code;

    /**
     * 创建充值订单状态。
     *
     * @param code 状态编码
     */
    RechargeOrderStatusDict(String code) {
        this.code = code;
    }

    /**
     * 获取状态编码。
     *
     * @return 状态编码
     */
    public String getCode() {
        return code;
    }
}
