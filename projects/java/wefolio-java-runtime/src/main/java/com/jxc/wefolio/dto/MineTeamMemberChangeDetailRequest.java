package com.jxc.wefolio.dto;

import lombok.Data;

/**
 * 团队成员信息变更详情请求。
 */
@Data
public class MineTeamMemberChangeDetailRequest {

    /** 变更请求 ID */
    private Long changeRequestId;
}
