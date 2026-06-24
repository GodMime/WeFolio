package com.jxc.wefolio.dto;

import com.alibaba.fastjson2.annotation.JSONField;
import lombok.Data;

/**
 * 微信接口调用凭证响应
 */
@Data
public class WechatAccessTokenResponse {

    /** 接口调用凭证 */
    @JSONField(name = "access_token")
    private String accessToken;

    /** 凭证有效期，单位秒 */
    @JSONField(name = "expires_in")
    private Long expiresIn;

    /** 微信错误码，成功时为空或 0 */
    private Integer errcode;

    /** 微信错误信息 */
    private String errmsg;
}
