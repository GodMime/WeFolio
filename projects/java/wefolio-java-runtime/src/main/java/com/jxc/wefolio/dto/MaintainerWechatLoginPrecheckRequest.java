package com.jxc.wefolio.dto;

import lombok.Data;

/**
 * 维护者微信登录预检请求 — 接收预检专用微信临时登录凭证。
 */
@Data
public class MaintainerWechatLoginPrecheckRequest {

    /** wx.login 返回的预检专用临时登录凭证 */
    private String code;
}
