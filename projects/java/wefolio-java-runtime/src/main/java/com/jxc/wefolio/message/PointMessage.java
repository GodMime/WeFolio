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

    /** 本地扣除积分非法提示 */
    String DEBIT_POINTS_INVALID_MESSAGE = "扣除积分必须为正整数";

    /** 微信代币赠送积分非法提示 */
    String GIFT_POINTS_INVALID_MESSAGE = "赠送积分必须为正整数";

    /** 赠送命令列表为空提示 */
    String GIFT_COMMAND_REQUIRED_MESSAGE = "赠送命令不能为空";

    /** 赠送来源快照为空提示 */
    String GIFT_SNAPSHOT_REQUIRED_MESSAGE = "赠送来源快照不能为空";

    /** 积分命令业务身份缺失提示 */
    String POINT_COMMAND_IDENTITY_INVALID_MESSAGE = "积分命令业务身份不完整";

    /** 幂等冲突回滚后没有找到既有结果提示 */
    String IDEMPOTENCY_RECOVERY_FAILED_MESSAGE = "积分幂等结果恢复失败，请重试";

    /** 月度存储结算请求为空提示 */
    String STORAGE_SETTLEMENT_REQUEST_REQUIRED_MESSAGE = "结算请求不能为空";

    /** 月度存储结算账期格式提示 */
    String STORAGE_SETTLEMENT_MONTH_INVALID_MESSAGE = "账期格式必须为 yyyy-MM";
}
