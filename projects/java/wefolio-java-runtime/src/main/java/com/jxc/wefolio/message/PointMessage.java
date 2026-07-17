package com.jxc.wefolio.message;

/**
 * 积分业务错误文案。
 */
public interface PointMessage {

    /** 积分余额不足提示 */
    String INSUFFICIENT_BALANCE_MESSAGE = "积分余额不足，请充值后再试";

    /** 幂等键已归属其他用户提示 */
    String IDEMPOTENCY_USER_CONFLICT_MESSAGE = "幂等键已被其他用户使用";

    /** 幂等键已归属其他积分业务提示 */
    String IDEMPOTENCY_BUSINESS_CONFLICT_MESSAGE = "幂等键已用于其他积分业务";

    /** 访客积分滚动扣费窗口异常提示 */
    String BILLING_WINDOW_ABNORMAL_MESSAGE = "积分扣费窗口异常，请重试";

    /** 积分账户原子更新失败提示 */
    String ACCOUNT_UPDATE_FAILED_MESSAGE = "积分账户更新失败，请重试";

    /** 充值积分非法提示 */
    String RECHARGE_POINTS_INVALID_MESSAGE = "充值积分必须大于 0";

    /** 充值商户订单号为空提示 */
    String RECHARGE_ORDER_NO_REQUIRED_MESSAGE = "商户订单号不能为空";

    /** 充值套餐快照为空提示 */
    String RECHARGE_SNAPSHOT_REQUIRED_MESSAGE = "充值套餐快照不能为空";
}
