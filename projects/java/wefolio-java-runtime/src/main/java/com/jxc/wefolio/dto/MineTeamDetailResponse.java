package com.jxc.wefolio.dto;

import lombok.Data;

import java.util.ArrayList;
import java.util.List;

/**
 * 我的团队维护详情响应。
 *
 * <p>对应小程序“团队维护”页面，包含团队资料表单和成员列表两块数据。</p>
 */
@Data
public class MineTeamDetailResponse {

    /** 团队资料 */
    private TeamInfo team = new TeamInfo();

    /** 团队成员 */
    private List<MemberItem> members = new ArrayList<>();

    /**
     * 团队资料。
     */
    @Data
    public static class TeamInfo {

        /** 团队 ID */
        private Long teamId;

        /** 团队唯一码 */
        private String uniqueCode;

        /** 团队名称 */
        private String name;

        /** 团队图标地址 */
        private String avatarUrl;

        /** 团队简介 */
        private String intro;

        /** 城市或服务区域 */
        private String city;

        /** 当前用户角色 */
        private String role;

        /** 当前用户角色文案 */
        private String roleText;

        /** 成员数 */
        private int memberCount;

        /** 成员数文案 */
        private String memberCountText;

        /** 当前用户是否可编辑团队资料 */
        private boolean canMaintain;

        /** 当前用户是否可维护团队成员 */
        private boolean canManageMembers;
    }

    /**
     * 团队成员列表项。
     *
     * <p>成员列表同时返回两类状态：</p>
     * <p>1. {@code joinStatus/joinStatusText/statusTone} 表示团队成员关系状态，
     * 例如已加入、待确认；小程序始终展示这个标签。</p>
     * <p>2. {@code userStatus/userStatusText/userStatusTone} 表示成员账号状态，
     * 例如账号已停用；小程序仅在非 ACTIVE 时额外展示账号状态标签。</p>
     * <p>{@code *Tone} 字段是前端样式 token，对应小程序中的
     * {@code .role-pill.teal/.amber/.muted}。</p>
     */
    @Data
    public static class MemberItem {

        /** 团队成员关系 ID */
        private Long memberId;

        /** 成员用户 ID */
        private Long userId;

        /** 成员个人唯一码 */
        private String uniqueCode;

        /** 成员昵称 */
        private String nickname;

        /** 页面展示名称 */
        private String displayName;

        /** 成员头像 */
        private String avatarUrl;

        /** 成员账号状态，来自用户表状态，如 ACTIVE、DISABLED；资料缺失时为空字符串 */
        private String userStatus;

        /** 成员账号状态文案，如“正常”“已停用”；前端非 ACTIVE 时展示 */
        private String userStatusText;

        /** 成员账号状态色调，供小程序展示停用标签，如 muted */
        private String userStatusTone;

        /** 团队内职业 */
        private String profession;

        /** 团队角色 */
        private String role;

        /** 团队角色文案 */
        private String roleText;

        /** 团队成员关系加入状态，如 JOINED、PENDING_CONFIRMATION */
        private String joinStatus;

        /** 团队成员关系加入状态文案，如“已加入”“待确认” */
        private String joinStatusText;

        /** 团队成员关系状态色调，供小程序成员状态标签选择样式，如 teal、amber、muted */
        private String statusTone;

        /** 是否允许团队引用个人作品集 */
        private boolean allowPortfolio;

        /** 是否允许团队引用头像资料 */
        private boolean allowProfile;

        /** 是否允许团队引用个人作品素材 */
        private boolean allowWorks;
    }
}
