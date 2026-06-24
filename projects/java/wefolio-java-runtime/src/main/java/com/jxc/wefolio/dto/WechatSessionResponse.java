package com.jxc.wefolio.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;

/**
 * 微信 jscode2session 响应 — 用于换取 openid 与 session_key
 */
@Data
public class WechatSessionResponse {

    /** 用户唯一标识 */
    private String openid;

    /** 会话密钥 */
    @JsonProperty("session_key")
    private String sessionKey;

    /** 用户在开放平台下的唯一标识，未绑定开放平台时为空 */
    private String unionid;

    /** 微信错误码，成功时为空或 0 */
    private Integer errcode;

    /** 微信错误信息 */
    private String errmsg;
}
