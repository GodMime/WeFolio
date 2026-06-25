package com.jxc.wefolio.dto;

import lombok.Data;

/**
 * 维护者微信登录响应 — 返回小程序本地保存的维护者访问令牌。
 */
@Data
public class MaintainerWechatLoginResponse {

    /** 令牌类型 */
    private String tokenType;

    /** 访问令牌 */
    private String token;

    /** 当前登录用户 ID */
    private Long userId;

    /** 访问令牌有效期秒数 */
    private Long expiresInSeconds;
}
