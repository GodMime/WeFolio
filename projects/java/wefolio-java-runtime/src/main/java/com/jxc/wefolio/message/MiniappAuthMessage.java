package com.jxc.wefolio.message;

/**
 * 小程序登录报错信息 — 统一维护登录令牌和微信注册相关提示。
 */
public interface MiniappAuthMessage {

    /** 微信登录凭证为空提示 */
    String WECHAT_LOGIN_CODE_REQUIRED_MESSAGE = "微信登录凭证不能为空";

    /** 令牌解析失败提示 */
    String TOKEN_PARSE_FAILED_MESSAGE = "登录令牌解析失败，请重新登录";

    /** 令牌过期提示 */
    String TOKEN_EXPIRED_MESSAGE = "登录令牌已过期，请重新登录";

    /** 令牌密钥未配置提示 */
    String TOKEN_SECRET_MISSING_MESSAGE = "登录令牌密钥未配置";

    /** 令牌生成失败提示 */
    String TOKEN_GENERATION_FAILED_MESSAGE = "登录令牌生成失败";

    /** 令牌密钥生成失败提示 */
    String TOKEN_SECRET_KEY_FAILED_MESSAGE = "登录令牌密钥生成失败";

    /** 访客令牌有效期配置异常提示 */
    String VISITOR_TOKEN_EXPIRES_INVALID_MESSAGE = "访客登录令牌有效期配置异常";

    /** 手机号并发注册冲突提示 */
    String PHONE_REGISTRATION_CONFLICT_MESSAGE = "手机号注册状态已变化，请重试";
}
