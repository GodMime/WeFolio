package com.jxc.wefolio.dto;

import lombok.Data;

/**
 * 登录态响应 — 告知小程序当前 token 是否有效
 */
@Data
public class AuthSessionResponse {

    /** 是否已登录 */
    private boolean authenticated;

    /** 当前登录用户 ID，未登录时为空 */
    private Long userId;

    /** 小程序前台维护微信会话的节流检查间隔秒数 */
    private Long wechatSessionCheckIntervalSeconds;

    /** 服务端微信会话需要刷新；新增可选字段，旧客户端可忽略且不影响维护者令牌有效性。 */
    private Boolean wechatSessionRefreshRequired;
}
