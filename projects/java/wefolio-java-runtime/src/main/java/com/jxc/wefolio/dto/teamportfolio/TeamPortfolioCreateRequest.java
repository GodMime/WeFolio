package com.jxc.wefolio.dto.teamportfolio;

import lombok.Data;

/**
 * 创建标准团队作品集请求。
 */
@Data
public class TeamPortfolioCreateRequest {

    /** 初始团队作品集配置，可为空。 */
    private TeamPortfolioConfigDto config;
}
