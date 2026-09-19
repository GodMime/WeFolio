package com.jxc.wefolio.message;

/** 微信虚拟支付交互错误文案。 */
public interface WechatVirtualPaymentMessage {

    /** 微信查单响应缺少订单状态。 */
    String ORDER_STATUS_MISSING_MESSAGE = "微信查单响应缺少订单状态";

    /** 微信查单响应缺少有效支付金额。 */
    String ORDER_AMOUNT_INVALID_MESSAGE = "微信查单响应缺少有效支付金额";

    /** 微信查单响应环境编号不在官方值域内。 */
    String ORDER_ENVIRONMENT_INVALID_MESSAGE = "微信查单响应环境编号非法";

    /** 微信查单新旧环境字段不一致。 */
    String ORDER_ENVIRONMENT_CONFLICT_MESSAGE = "微信查单响应环境字段冲突";

    /** 微信查单响应代币数量非法。 */
    String ORDER_QUANTITY_INVALID_MESSAGE = "微信查单响应代币数量非法";

    /** 微信赠送余额超过总余额。 */
    String PRESENT_BALANCE_INVALID_MESSAGE = "微信赠送余额超过总余额";

    /** 微信响应缺少字段。 */
    String RESPONSE_FIELD_MISSING_MESSAGE = "微信响应缺少字段：";

    /** 微信响应字段为负数。 */
    String RESPONSE_FIELD_NEGATIVE_MESSAGE = "微信响应字段为负数：";

    /** 微信响应字段不是整数。 */
    String RESPONSE_INTEGER_REQUIRED_MESSAGE = "微信响应字段不是整数";

    /** 重复赠送等待有效微信会话以确认余额。 */
    String DUPLICATE_GIFT_SESSION_REQUIRED_MESSAGE = "重复赠送等待有效微信会话以确认余额";

    /** 重复赠送余额查询异常。 */
    String DUPLICATE_GIFT_BALANCE_QUERY_FAILED_MESSAGE = "重复赠送余额查询异常";

    /** 重复赠送余额暂无法确认。 */
    String DUPLICATE_GIFT_BALANCE_UNCONFIRMED_MESSAGE = "重复赠送余额暂无法确认";

    /** 没有明确的成功事实时禁止直接核销扣币。 */
    String DEBIT_SUCCESS_PROOF_MISSING_MESSAGE = "任务未记录微信成功事实";

    /** 回调签名无效提示 */
    String INVALID_SIGNATURE_MESSAGE = "签名无效";
}
