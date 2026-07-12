package com.jxc.wefolio.dto.teamportfolio;

import lombok.Data;

/**
 * 保存团队作品集草稿请求。
 */
@Data
public class TeamPortfolioDraftSaveRequest {

    /** 草稿配置。 */
    private TeamPortfolioConfigDto config;

    /** 客户端读取到的草稿版本。 */
    private Integer clientRevision;

    /** 保存幂等键。 */
    private String idempotencyKey;
}
