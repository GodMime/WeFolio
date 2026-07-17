package com.jxc.wefolio.message;

/**
 * 充值与微信支付错误文案。
 */
public interface RechargeMessage {

    /** 微信支付未启用提示。 */
    String PAYMENT_NOT_CONFIGURED_MESSAGE = "微信支付暂未配置，请稍后再试";

    /** 充值请求缺少套餐提示。 */
    String PACKAGE_ID_REQUIRED_MESSAGE = "请选择充值套餐";

    /** 充值套餐无效提示。 */
    String PACKAGE_UNAVAILABLE_MESSAGE = "充值套餐不存在或已失效，请重新选择";

    /** 用户状态无效提示。 */
    String USER_UNAVAILABLE_MESSAGE = "用户不存在或已停用";

    /** 维护者缺少微信身份提示。 */
    String WECHAT_IDENTITY_MISSING_MESSAGE = "未找到微信身份，请重新登录后再试";

    /** 微信预下单失败提示。 */
    String PREPAY_FAILED_MESSAGE = "微信支付下单失败，请稍后重试";

    /** 微信预下单响应无效提示。 */
    String PREPAY_RESPONSE_INVALID_MESSAGE = "微信支付预下单响应无效";

    /** 微信主动查单暂时失败提示。 */
    String ORDER_QUERY_FAILED_MESSAGE = "微信支付订单查询失败，请稍后在充值记录中查看";

    /** 订单创建失败提示。 */
    String ORDER_CREATE_FAILED_MESSAGE = "充值订单创建失败，请稍后重试";

    /** 订单不存在提示。 */
    String ORDER_NOT_FOUND_MESSAGE = "充值订单不存在";

    /** 商户订单号为空提示。 */
    String MERCHANT_ORDER_NO_REQUIRED_MESSAGE = "商户订单号不能为空";

    /** 微信查单响应商户订单号不匹配提示。 */
    String MERCHANT_ORDER_NO_MISMATCH_MESSAGE = "微信支付返回的商户订单号不一致";

    /** 订单更新失败提示。 */
    String ORDER_UPDATE_FAILED_MESSAGE = "充值订单更新失败，请重试";

    /** 微信交易状态异常提示。 */
    String TRADE_STATE_INVALID_MESSAGE = "微信支付交易状态不是支付成功";

    /** 微信交易 AppID 不匹配提示。 */
    String APP_ID_MISMATCH_MESSAGE = "微信支付 AppID 与充值订单不一致";

    /** 微信交易商户号不匹配提示。 */
    String MERCHANT_ID_MISMATCH_MESSAGE = "微信支付商户号与充值订单不一致";

    /** 微信交易金额不匹配提示。 */
    String AMOUNT_MISMATCH_MESSAGE = "微信支付金额与充值订单不一致";

    /** 微信交易币种不匹配提示。 */
    String CURRENCY_MISMATCH_MESSAGE = "微信支付币种与充值订单不一致";

    /** 微信交易号缺失提示。 */
    String TRANSACTION_ID_MISSING_MESSAGE = "微信支付交易号不能为空";

    /** 已支付订单交易号冲突提示。 */
    String TRANSACTION_ID_CONFLICT_MESSAGE = "已支付订单的微信交易号不一致";

    /** 微信支付通知验签失败提示。 */
    String NOTIFICATION_SIGNATURE_INVALID_MESSAGE = "微信支付通知验签失败";

    /** 微信支付通知内容异常提示。 */
    String NOTIFICATION_INVALID_MESSAGE = "微信支付通知内容无效";

    /** 通知不是支付成功事件提示。 */
    String NOTIFICATION_NOT_SUCCESS_MESSAGE = "微信支付通知不是支付成功事件";

    /** 充值成功状态文案。 */
    String STATUS_PAID_TEXT = "充值成功";

    /** 待支付状态文案。 */
    String STATUS_PENDING_TEXT = "待支付";

    /** 支付失败状态文案。 */
    String STATUS_PAYMENT_FAILED_TEXT = "支付失败";

    /** 已关闭状态文案。 */
    String STATUS_CLOSED_TEXT = "已关闭";
}
