package com.jxc.wefolio.dto;

import lombok.Data;

/**
 * 访客打开作品集请求。
 */
@Data
public class VisitorPortfolioOpenRequest {

    /** wx.login 返回的临时登录凭证 */
    private String loginCode;

    /** 朋友圈单页模式匿名会话标识 */
    private String anonymousSessionId;

    /** 来源类型 */
    private String sourceType;

    /** 打开事件幂等键 */
    private String idempotencyKey;
}
