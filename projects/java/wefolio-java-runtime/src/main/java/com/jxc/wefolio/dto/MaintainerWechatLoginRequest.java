package com.jxc.wefolio.dto;

import lombok.Data;

/**
 * 维护者微信登录请求 — 接收登录凭证、手机号授权凭证和用户填写资料。
 */
@Data
public class MaintainerWechatLoginRequest {

    /** wx.login 返回的临时登录凭证 */
    private String code;

    /** 微信资料昵称，可为空 */
    private String nickname;

    /** 微信资料头像，可为空 */
    private String avatarUrl;

    /** 手机号快速验证组件返回的 code，首次注册必填 */
    private String phoneCode;

    /** wx.pluginLogin 返回的插件用户标志凭证，首次注册用于换取 openpid */
    private String pluginLoginCode;

    /** 注册推荐码，可为空 */
    private String referralCode;
}
