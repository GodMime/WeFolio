package com.jxc.wefolio.dto;

import lombok.Data;

import java.util.List;

/**
 * 访问记录响应 — 汇总维护者客资访问统计、趋势和明细列表。
 */
@Data
public class MineVisitRecordsResponse {

    /** 团队范围是否完整。 */
    private Boolean scopeComplete;

    /** 团队范围降级原因，完整时为空字符串。 */
    private String scopeReason;

    /** 顶部访问统计 */
    private Summary summary;

    /** 近 7 日访问趋势 */
    private Trend trend;

    /** 最近访问明细 */
    private List<Record> records;

    /**
     * 顶部访问统计。
     */
    @Data
    public static class Summary {

        /** 累计访问次数 */
        private Long totalVisitCount;

        /** 今日访问次数 */
        private Long todayVisitCount;

        /** 累计查询档期次数 */
        private Long scheduleQueryCount;

        /** 累计预留信息次数 */
        private Long contactLeadCount;
    }

    /**
     * 访问趋势。
     */
    @Data
    public static class Trend {

        /** 趋势变化文案 */
        private String changeText;

        /** 每日趋势点 */
        private List<TrendPoint> points;
    }

    /**
     * 每日趋势点。
     */
    @Data
    public static class TrendPoint {

        /** 周几展示文案 */
        private String label;

        /** 日期，格式 yyyy-MM-dd */
        private String date;

        /** 当日访问次数 */
        private Long value;
    }

    /**
     * 访问明细。
     */
    @Data
    public static class Record {

        /** 访问汇总记录 ID */
        private Long id;

        /** 匿名访客短码 */
        private String visitorCode;

        /** 匿名访客展示名称 */
        private String visitorLabel;

        /** 访客头像占位字 */
        private String visitorInitial;

        /** 访客头像地址 */
        private String visitorAvatarUrl;

        /** 来源展示文案 */
        private String sourceText;

        /** 被访问作品集标题快照。 */
        private String portfolioTitle;

        /** 被访问作品集类型：PERSONAL 或 TEAM。 */
        private String portfolioType;

        /** 被访问作品集类型文案。 */
        private String portfolioTypeText;

        /** 当前维护者是否可标记该访问记录已跟进。 */
        private Boolean canMarkFollowed;

        /** 行为摘要文案 */
        private String summaryText;

        /** 跟进状态编码 */
        private String followStatus;

        /** 跟进状态展示文案 */
        private String followStatusText;

        /** 跟进状态颜色语义 */
        private String followTone;

        /** 最近访问时间文案 */
        private String lastVisitedText;
    }

    /**
     * 访问事件时间线。
     */
    @Data
    public static class EventTimeline {

        /** 访问汇总记录 ID */
        private Long recordId;

        /** 匿名访客展示名称 */
        private String visitorLabel;

        /** 访客头像占位字 */
        private String visitorInitial;

        /** 访客头像地址 */
        private String visitorAvatarUrl;

        /** 来源展示文案 */
        private String sourceText;

        /** 被访问作品集标题快照。 */
        private String portfolioTitle;

        /** 被访问作品集类型：PERSONAL 或 TEAM。 */
        private String portfolioType;

        /** 被访问作品集类型文案。 */
        private String portfolioTypeText;

        /** 跟进状态展示文案 */
        private String followStatusText;

        /** 跟进状态颜色语义 */
        private String followTone;

        /** 最近访问时间文案 */
        private String lastVisitedText;

        /** 当前页码，从 1 开始 */
        private Integer pageNo;

        /** 当前页大小 */
        private Integer pageSize;

        /** 是否还有下一页事件 */
        private Boolean hasMore;

        /** 具体访问事件 */
        private List<VisitEventItem> events;
    }

    /**
     * 访问事件展示项。
     */
    @Data
    public static class VisitEventItem {

        /** 访问事件 ID */
        private Long eventId;

        /** 事件类型编码 */
        private String eventType;

        /** 事件标题 */
        private String title;

        /** 事件补充说明 */
        private String detailText;

        /** 发生日期文案 */
        private String occurredDateText;

        /** 发生时间文案 */
        private String occurredTimeText;

        /** 事件颜色语义 */
        private String tone;
    }

    /**
     * 查询档期分页响应。
     */
    @Data
    public static class ScheduleQueryPage {

        /** 团队范围是否完整。 */
        private Boolean scopeComplete;

        /** 团队范围降级原因，完整时为空字符串。 */
        private String scopeReason;

        /** 当前页码，从 1 开始 */
        private Integer pageNo;

        /** 当前页大小 */
        private Integer pageSize;

        /** 是否还有下一页 */
        private Boolean hasMore;

        /** 查询档期明细 */
        private List<ScheduleQueryItem> items;
    }

    /**
     * 查询档期明细项。
     */
    @Data
    public static class ScheduleQueryItem {

        /** 来源表真实主键，个人与团队记录均保持正数。 */
        private Long id;

        /** 记录类型：PERSONAL 或 TEAM。 */
        private String recordType;

        /** 来源表真实主键；后端操作应与记录类型一起使用。 */
        private Long sourceRecordId;

        /** 前端只读列表复合键，例如 TEAM:18。 */
        private String scheduleQueryKey;

        /** 访客展示名称 */
        private String visitorLabel;

        /** 访客头像地址 */
        private String visitorAvatarUrl;

        /** 访客头像占位字 */
        private String visitorInitial;

        /** 来源作品集标题 */
        private String portfolioTitle;

        /** 查询日期文案 */
        private String queriedDateText;

        /** 档位与时间文案 */
        private String slotText;

        /** 查询结果状态编码 */
        private String resultStatus;

        /** 查询结果状态文案 */
        private String resultStatusText;

        /** 是否可约 */
        private Boolean available;

        /** 查询结果提示 */
        private String resultMessage;

        /** 来源文案 */
        private String sourceText;

        /** 创建时间文案 */
        private String createdTimeText;
    }

    /**
     * 预留信息分页响应。
     */
    @Data
    public static class ContactLeadPage {

        /** 当前页码，从 1 开始 */
        private Integer pageNo;

        /** 当前页大小 */
        private Integer pageSize;

        /** 是否还有下一页 */
        private Boolean hasMore;

        /** 预留信息明细 */
        private List<ContactLeadItem> items;
    }

    /**
     * 预留信息明细项。
     */
    @Data
    public static class ContactLeadItem {

        /** 线索 ID */
        private Long id;

        /** 联系人姓名 */
        private String contactName;

        /** 维护端展示手机号 */
        private String phone;

        /** 手机号尾号 */
        private String phoneLast4;

        /** 维护端展示微信号 */
        private String wechat;

        /** 微信号脱敏提示 */
        private String wechatMaskHint;

        /** 意向档期 */
        private String desiredSchedule;

        /** 需求描述 */
        private String needs;

        /** 来源作品集标题 */
        private String portfolioTitle;

        /** 作品集类型：PERSONAL 或 TEAM */
        private String portfolioType;

        /** 作品集类型文案 */
        private String portfolioTypeText;

        /** 来源文案 */
        private String sourceText;

        /** 跟进状态编码 */
        private String followStatus;

        /** 跟进状态文案 */
        private String followStatusText;

        /** 当前用户是否可以标记已跟进 */
        private Boolean canMarkFollowed;

        /** 提交时间文案 */
        private String submittedTimeText;
    }
}
