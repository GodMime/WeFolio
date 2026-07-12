package com.jxc.wefolio.dto.teamportfolio;

import lombok.Data;

/**
 * 发布团队作品集请求。
 */
@Data
public class TeamPortfolioPublishRequest {

    /** 待发布草稿版本。 */
    private Integer draftRevision;

    /** 发布幂等键。 */
    private String idempotencyKey;
}
