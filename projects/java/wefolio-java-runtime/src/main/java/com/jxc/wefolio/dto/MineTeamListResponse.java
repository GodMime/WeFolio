package com.jxc.wefolio.dto;

import lombok.Data;

import java.util.ArrayList;
import java.util.List;

/**
 * 我的团队列表响应。
 *
 * <p>对应小程序“我的团队”页面，包含顶部摘要和卡片列表所需的全部展示字段。</p>
 */
@Data
public class MineTeamListResponse {

    /** 团队身份摘要 */
    private Summary summary = new Summary();

    /** 团队列表 */
    private List<TeamItem> teams = new ArrayList<>();

    /**
     * 团队身份摘要。
     */
    @Data
    public static class Summary {

        /** 当前用户已加入的团队数 */
        private int joinedCount;

        /** 当前用户作为拥有者的团队数 */
        private int ownerCount;

        /** 当前用户可进入维护模式的团队数，拥有者和管理者均计入 */
        private int manageableCount;

        /** 摘要文案，前端直接展示，减少小程序端重复拼装规则 */
        private String summaryText;
    }

    /**
     * 团队列表项。
     */
    @Data
    public static class TeamItem {

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

        /** 当前用户团队角色 */
        private String role;

        /** 当前用户团队角色文案 */
        private String roleText;

        /** 成员数 */
        private int memberCount;

        /** 成员数文案 */
        private String memberCountText;

        /** 当前用户是否可维护团队资料 */
        private boolean canMaintain;

        /** 操作文案，如“维护”或“查看” */
        private String maintainText;

        /** 更新时间文案，如“最近更新 06-18” */
        private String updatedText;
    }
}
