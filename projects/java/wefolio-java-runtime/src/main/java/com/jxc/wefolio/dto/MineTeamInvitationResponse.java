package com.jxc.wefolio.dto;

import lombok.Data;

/**
 * 团队邀请响应。
 */
@Data
public class MineTeamInvitationResponse {

    /** 团队成员关系 ID */
    private Long memberId;

    /** 团队 ID */
    private Long teamId;

    /** 团队唯一码 */
    private String teamUniqueCode;

    /** 团队名称 */
    private String teamName;

    /** 团队图标地址 */
    private String teamAvatarUrl;

    /** 邀请人用户 ID */
    private Long inviterUserId;

    /** 邀请人展示名称 */
    private String inviterName;

    /** 团队角色 */
    private String role;

    /** 团队角色文案 */
    private String roleText;

    /** 团队身份 */
    private String profession;

    /** 是否允许团队引用个人作品集 */
    private boolean allowPortfolio;

    /** 是否允许团队引用头像资料 */
    private boolean allowProfile;

    /** 是否允许团队引用个人作品素材 */
    private boolean allowWorks;

    /** 邀请加入状态 */
    private String joinStatus;

    /** 邀请加入状态文案 */
    private String joinStatusText;

    /** 状态色调 */
    private String statusTone;

    /** 当前用户是否还可响应 */
    private boolean canRespond;
}
