package com.jxc.wefolio.dto;

import lombok.Data;

/**
 * 访客打开作品集请求。
 */
@Data
public class VisitorPortfolioOpenRequest {

    /** wx.login 返回的临时登录凭证 */
    private String loginCode;

    /** 来源类型 */
    private String sourceType;

    /** 打开事件幂等键 */
    private String idempotencyKey;
}
