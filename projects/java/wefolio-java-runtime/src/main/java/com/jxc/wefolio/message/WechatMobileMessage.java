package com.jxc.wefolio.message;

/** 移动应用微信授权提示 — 不暴露外部凭证与账号查询细节。 */
public interface WechatMobileMessage {
    /** 尚未完成开放平台配置。 */
    String NOT_ENABLED = "安卓微信登录尚未开通";
    /** 缺少授权凭证。 */
    String CODE_REQUIRED = "请重新进行微信授权";
    /** 微信未能验证本次授权。 */
    String EXCHANGE_FAILED = "微信授权校验失败，请重新授权";
    /** 同一开放平台身份缺失或未关联。 */
    String ACCOUNT_NOT_LINKED = "暂未关联已有账号，请联系管理员完成小程序账号跨端关联";
    /** 多账号身份不明确。 */
    String ACCOUNT_AMBIGUOUS = "微信身份关联异常，请联系管理员处理";
    /** 原账号已经停用。 */
    String ACCOUNT_INACTIVE = "微信账号状态异常";
    /** 服务端身份摘要配置不可用。 */
    String IDENTITY_FAILED = "微信身份校验暂不可用，请稍后重试";
}
