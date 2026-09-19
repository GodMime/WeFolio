package com.jxc.wefolio.message;

/** 小程序码应用编排相关业务错误提示。 */
public interface PortfolioMiniappCodeApplicationMessage {

    /** 请求未携带有效身份。 */
    String LOGIN_REQUIRED = "用户未登录";

    /** 成图期间公开资料、发布版本或分享码发生变化。 */
    String SNAPSHOT_CHANGED = "作品集或资料已更新，请重新生成小程序码";
}
