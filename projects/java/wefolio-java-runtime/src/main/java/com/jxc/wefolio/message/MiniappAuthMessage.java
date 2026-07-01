package com.jxc.wefolio.message;

/**
 * 小程序登录报错信息 — 统一维护登录令牌和微信注册相关提示。
 */
public interface MiniappAuthMessage {

    /** 令牌解析失败提示 */
    String TOKEN_PARSE_FAILED_MESSAGE = "登录令牌解析失败，请重新登录";

    /** 令牌过期提示 */
    String TOKEN_EXPIRED_MESSAGE = "登录令牌已过期，请重新登录";

    /** 令牌密钥未配置提示 */
    String TOKEN_SECRET_MISSING_MESSAGE = "登录令牌密钥未配置";

    /** 手机号并发注册冲突提示 */
    String PHONE_REGISTRATION_CONFLICT_MESSAGE = "手机号注册状态已变化，请重试";
}
