package com.jxc.wefolio.dto.teamportfolio;

import lombok.Data;

/**
 * 访客打开标准团队作品集请求。
 */
@Data
public class VisitorTeamPortfolioOpenRequest {

    /** wx.login 返回的临时登录凭证。 */
    private String loginCode;

    /** 朋友圈单页模式匿名会话标识。 */
    private String anonymousSessionId;

    /** 访问来源类型。 */
    private String sourceType;

    /** 打开事件幂等键。 */
    private String idempotencyKey;
}
