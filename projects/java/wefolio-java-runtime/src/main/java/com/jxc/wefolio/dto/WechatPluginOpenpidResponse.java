package com.jxc.wefolio.dto;

import lombok.Data;

/**
 * 微信插件 openpid 响应
 */
@Data
public class WechatPluginOpenpidResponse {

    /** 微信错误码，成功时为空或 0 */
    private Integer errcode;

    /** 微信错误信息 */
    private String errmsg;

    /** 插件用户唯一标识 */
    private String openpid;
}
