package com.jxc.wefolio.dto;

import lombok.Data;

import java.util.List;

/**
 * 访问记录响应 — 汇总维护者客资访问统计、趋势和明细列表。
 */
@Data
public class MineVisitRecordsResponse {

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

        /** 来源展示文案 */
        private String sourceText;

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
}
