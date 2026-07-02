package com.jxc.wefolio.dto;

import lombok.Data;

/**
 * 发布作品集请求。
 */
@Data
public class MinePortfolioPublishRequest {

    /** 待发布草稿版本 */
    private Integer draftRevision;

    /** 发布幂等键 */
    private String idempotencyKey;
}
