package com.jxc.wefolio.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;

/**
 * 微信手机号快速验证响应
 */
@Data
public class WechatPhoneNumberResponse {

    /** 微信错误码，成功时为空或 0 */
    private Integer errcode;

    /** 微信错误信息 */
    private String errmsg;

    /** 用户手机号信息 */
    @JsonProperty("phone_info")
    private PhoneInfo phoneInfo;

    /**
     * 用户手机号信息
     */
    @Data
    public static class PhoneInfo {

        /** 用户绑定手机号，国外手机号会带区号 */
        private String phoneNumber;

        /** 不含区号手机号 */
        private String purePhoneNumber;

        /** 区号 */
        private String countryCode;
    }
}
