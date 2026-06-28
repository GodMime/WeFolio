package com.jxc.wefolio.dto;

import lombok.Data;

/**
 * 团队拥有者转让请求。
 */
@Data
public class MineTeamOwnerTransferRequest {

    /** 团队 ID */
    private Long teamId;

    /** 新拥有者团队成员关系 ID */
    private Long memberId;
}
