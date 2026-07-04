package com.jxc.wefolio.dto;

import lombok.Data;

import java.util.ArrayList;
import java.util.List;

/**
 * 作品集档期查询组件月历选项响应。
 */
@Data
public class PortfolioScheduleOptionsResponse {

    /** 当前月份，格式 yyyy-MM */
    private String yearMonth;

    /** 启用档位定义 */
    private List<SlotDefinitionItem> slotDefinitions = new ArrayList<>();

    /** 月历日期格子 */
    private List<MonthDayItem> days = new ArrayList<>();

    /** 当月访客可见档期 */
    private List<ScheduleItem> schedules = new ArrayList<>();

    /**
     * 档位定义项。
     */
    @Data
    public static class SlotDefinitionItem {

        /** 档位定义 ID */
        private Long id;

        /** 档位名称 */
        private String name;

        /** 开始时间，格式 HH:mm */
        private String startTime;

        /** 结束时间，格式 HH:mm */
        private String endTime;

        /** 展示颜色 */
        private String color;
    }

    /**
     * 月历日期格子。
     */
    @Data
    public static class MonthDayItem {

        /** 日期，格式 yyyy-MM-dd */
        private String date;

        /** 日数字 */
        private int dayNumber;

        /** 是否属于当前月份 */
        private boolean currentMonth;

        /** 当天档期颜色 */
        private List<String> colors = new ArrayList<>();

        /** 当天档期数量 */
        private int count;
    }

    /**
     * 访客可见档期项。
     */
    @Data
    public static class ScheduleItem {

        /** 日期，格式 yyyy-MM-dd */
        private String date;

        /** 档位定义 ID */
        private Long slotDefinitionId;

        /** 档位名称 */
        private String slotName;

        /** 开始时间，格式 HH:mm */
        private String startTime;

        /** 结束时间，格式 HH:mm */
        private String endTime;

        /** 展示颜色 */
        private String color;

        /** 状态编码 */
        private String status;

        /** 状态文案 */
        private String statusText;

        /** 状态色调 */
        private String statusTone;
    }
}
