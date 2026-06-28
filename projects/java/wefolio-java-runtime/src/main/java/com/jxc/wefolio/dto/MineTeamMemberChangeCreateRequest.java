package com.jxc.wefolio.dto;

import lombok.Data;

/**
 * 团队成员信息变更发起请求。
 */
@Data
public class MineTeamMemberChangeCreateRequest {

    /** 团队 ID */
    private Long teamId;

    /** 团队成员关系 ID */
    private Long memberId;

    /** 修改后的团队角色 */
    private String role;

    /** 修改后的团队身份 */
    private String profession;

    /** 是否允许团队引用个人作品集 */
    private Boolean allowPortfolio;

    /** 是否允许团队引用头像资料 */
    private Boolean allowProfile;

    /** 是否允许团队引用个人作品素材 */
    private Boolean allowWorks;
}
