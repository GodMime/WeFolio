package com.jxc.wefolio.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;

/**
 * 微信接口调用凭证响应
 */
@Data
public class WechatAccessTokenResponse {

    /** 接口调用凭证 */
    @JsonProperty("access_token")
    private String accessToken;

    /** 凭证有效期，单位秒 */
    @JsonProperty("expires_in")
    private Long expiresIn;

    /** 微信错误码，成功时为空或 0 */
    private Integer errcode;

    /** 微信错误信息 */
    private String errmsg;
}
