package com.jxc.wefolio.message;

/** 小程序码基础设施的可重试错误文案。 */
public interface PortfolioMiniappCodeImageMessage {
    /** 生成失败。 */
    String GENERATION_FAILED = "小程序码生成失败，请稍后重试";
    /** 历史分享码或服务端参数不满足微信约束。 */
    String INVALID_PARAMETERS = "小程序码参数无效，请稍后重试";
    /** 已设置头像不可用时不得静默换成默认身份图片。 */
    String AVATAR_FAILED = "头像读取失败，请稍后重试";
    /** 超过未命中生成额度。 */
    String RATE_LIMITED = "生成过于频繁，请稍后重试";
}
