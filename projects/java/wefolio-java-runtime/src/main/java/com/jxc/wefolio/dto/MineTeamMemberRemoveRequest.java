package com.jxc.wefolio.dto;

import lombok.Data;

/**
 * 团队成员移除请求。
 */
@Data
public class MineTeamMemberRemoveRequest {

    /** 团队 ID */
    private Long teamId;

    /** 团队成员关系 ID */
    private Long memberId;
}
