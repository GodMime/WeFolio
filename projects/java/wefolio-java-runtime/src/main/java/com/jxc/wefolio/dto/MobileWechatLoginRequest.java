package com.jxc.wefolio.dto;

/** 移动应用登录请求 — 只接受微信临时凭证，不接受客户端声明的账号身份。 */
public record MobileWechatLoginRequest(String code) {
}
