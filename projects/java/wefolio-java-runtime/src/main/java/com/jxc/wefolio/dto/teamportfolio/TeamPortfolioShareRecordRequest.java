package com.jxc.wefolio.dto.teamportfolio;

import lombok.Data;

/**
 * 团队作品集分享记录请求。
 */
@Data
public class TeamPortfolioShareRecordRequest {

    /** 分享渠道。 */
    private String shareChannel;

    /** 分享场景。 */
    private String shareScene;
}
