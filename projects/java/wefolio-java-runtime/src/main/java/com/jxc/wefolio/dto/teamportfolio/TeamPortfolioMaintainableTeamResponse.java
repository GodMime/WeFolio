package com.jxc.wefolio.dto.teamportfolio;

import lombok.Data;

/**
 * 可维护团队作品集的团队响应。
 */
@Data
public class TeamPortfolioMaintainableTeamResponse {

    /** 团队 ID */
    private Long teamId;

    /** 团队名称 */
    private String teamName;

    /** 团队头像地址 */
    private String avatarUrl;

    /** 当前用户团队角色 */
    private String currentRole;
}
