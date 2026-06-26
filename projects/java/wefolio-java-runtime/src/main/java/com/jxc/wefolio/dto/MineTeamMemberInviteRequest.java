package com.jxc.wefolio.dto;

import lombok.Data;

/**
 * 团队成员邀请请求。
 */
@Data
public class MineTeamMemberInviteRequest {

    /** 被邀请成员的个人唯一码 */
    private String uniqueCode;

    /** 团队角色，仅允许 MANAGER 或 MEMBER */
    private String role;

    /** 团队内展示职业身份 */
    private String profession;

    /** 是否允许团队引用个人作品集 */
    private Boolean allowPortfolio;

    /** 是否允许团队引用头像资料 */
    private Boolean allowProfile;

    /** 是否允许团队引用个人作品素材 */
    private Boolean allowWorks;
}
