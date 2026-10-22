package com.jxc.wefolio.service;

/** 移动应用微信客户端 — 服务端校验 OAuth 凭证并返回可信跨应用身份。 */
public interface WechatMobileClient {

    /** 使用移动应用凭证交换 UnionID，禁止与小程序登录接口混用。 */
    String exchangeUnionId(String code);
}
