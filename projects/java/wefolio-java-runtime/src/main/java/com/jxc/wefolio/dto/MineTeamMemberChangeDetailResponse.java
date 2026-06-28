package com.jxc.wefolio.dto;

import lombok.Data;

/**
 * 团队成员信息变更详情响应。
 */
@Data
public class MineTeamMemberChangeDetailResponse {

    /** 变更请求 ID */
    private Long changeRequestId;

    /** 团队 ID */
    private Long teamId;

    /** 团队名称 */
    private String teamName;

    /** 团队图标 */
    private String teamAvatarUrl;

    /** 团队成员关系 ID */
    private Long memberId;

    /** 被修改成员用户 ID */
    private Long targetUserId;

    /** 被修改成员名称 */
    private String targetName;

    /** 发起人用户 ID */
    private Long requesterUserId;

    /** 发起人名称 */
    private String requesterName;

    /** 状态编码 */
    private String status;

    /** 状态文案 */
    private String statusText;

    /** 是否可响应 */
    private boolean canRespond;

    /** 修改前角色 */
    private String roleBefore;

    /** 修改前角色文案 */
    private String roleBeforeText;

    /** 修改后角色 */
    private String roleAfter;

    /** 修改后角色文案 */
    private String roleAfterText;

    /** 修改前团队身份 */
    private String professionBefore;

    /** 修改后团队身份 */
    private String professionAfter;

    /** 修改前是否允许引用作品集 */
    private boolean allowPortfolioBefore;

    /** 修改后是否允许引用作品集 */
    private boolean allowPortfolioAfter;

    /** 修改前是否允许引用主页资料 */
    private boolean allowProfileBefore;

    /** 修改后是否允许引用主页资料 */
    private boolean allowProfileAfter;

    /** 修改前是否允许引用作品素材 */
    private boolean allowWorksBefore;

    /** 修改后是否允许引用作品素材 */
    private boolean allowWorksAfter;

    /** 修改前权限文案 */
    private String permissionBeforeText;

    /** 修改后权限文案 */
    private String permissionAfterText;

    /** 发起时间文案 */
    private String requestedAtText;

    /** 响应时间文案 */
    private String respondedAtText;
}
