package com.jxc.wefolio.message;

/**
 * 积分业务错误文案。
 */
public interface PointMessage {

    /** 幂等键已归属其他用户提示 */
    String IDEMPOTENCY_USER_CONFLICT_MESSAGE = "幂等键已被其他用户使用";

    /** 幂等键已归属其他积分业务提示 */
    String IDEMPOTENCY_BUSINESS_CONFLICT_MESSAGE = "幂等键已用于其他积分业务";

    /** 访客积分滚动扣费窗口异常提示 */
    String BILLING_WINDOW_ABNORMAL_MESSAGE = "积分扣费窗口异常，请重试";
}
