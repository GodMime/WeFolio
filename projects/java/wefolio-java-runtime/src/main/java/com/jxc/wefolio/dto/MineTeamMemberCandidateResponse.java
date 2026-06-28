package com.jxc.wefolio.dto;

import lombok.Data;

/**
 * 团队成员候选人响应。
 */
@Data
public class MineTeamMemberCandidateResponse {

    /** 用户 ID */
    private Long userId;

    /** 个人唯一码 */
    private String uniqueCode;

    /** 昵称 */
    private String nickname;

    /** 页面展示名称 */
    private String displayName;

    /** 头像地址 */
    private String avatarUrl;

    /** 候选人团队身份 */
    private String profession;

    /** 城市或服务区域 */
    private String city;

    /** 用户账号状态 */
    private String userStatus;

    /** 既有成员关系 ID */
    private Long memberId;

    /** 既有加入状态 */
    private String existingJoinStatus;

    /** 既有加入状态文案 */
    private String existingJoinStatusText;

    /** 是否可发起邀请 */
    private boolean canInvite;

    /** 可邀请状态说明 */
    private String reason;
}
