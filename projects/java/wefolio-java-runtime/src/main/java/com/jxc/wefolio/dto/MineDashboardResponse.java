package com.jxc.wefolio.dto;

import lombok.Data;

import java.util.List;

/**
 * 我的首页响应 — 汇总维护者身份、积分和业务指标
 */
@Data
public class MineDashboardResponse {

    /** 身份资料摘要 */
    private Profile profile;

    /** 积分摘要 */
    private PointSummary point;

    /** 首页业务指标 */
    private Metrics metrics;

    /**
     * 身份资料摘要
     */
    @Data
    public static class Profile {

        /** 用户 ID */
        private Long userId;

        /** 个人唯一码 */
        private String uniqueCode;

        /** 页面展示名称 */
        private String displayName;

        /** 昵称、姓名或艺名 */
        private String nickname;

        /** 头像地址 */
        private String avatarUrl;

        /** 职业身份 */
        private String profession;

        /** 城市或服务区域 */
        private String city;

        /** 核心标签 */
        private List<String> tags;
    }

    /**
     * 积分摘要
     */
    @Data
    public static class PointSummary {

        /** 当前积分余额 */
        private Long balance;

        /** 今日已消耗积分 */
        private Long todayConsumed;

        /** 累计充值积分 */
        private Long totalRecharged;

        /** 累计消耗积分 */
        private Long totalConsumed;

        /** 是否低于低余额提醒阈值 */
        private boolean lowBalance;
    }

    /**
     * 首页业务指标
     */
    @Data
    public static class Metrics {

        /** 作品素材数量 */
        private Long workCount;

        /** 已发布作品集数量 */
        private Long publishedPortfolioCount;

        /** 近 7 日访问次数 */
        private Long recentVisitCount;
    }
}
