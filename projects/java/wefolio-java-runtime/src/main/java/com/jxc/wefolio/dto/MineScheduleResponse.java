package com.jxc.wefolio.dto;

import lombok.Data;

import java.util.ArrayList;
import java.util.List;

/**
 * 我的档期响应片段。
 *
 * <p>维护者档期页面按档位定义、月历颜色标记和选中日期明细分别读取。</p>
 */
@Data
public class MineScheduleResponse {

    /** 档位定义列表，包含启用和停用定义 */
    private List<SlotDefinitionItem> slotDefinitions = new ArrayList<>();

    /** 月历概览 */
    private MonthOverview month = new MonthOverview();

    /** 当前选中日期档期明细 */
    private SelectedDateOverview selectedDate = new SelectedDateOverview();

    /**
     * 档位定义列表项。
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

        /** 十六进制颜色 */
        private String color;

        /** 是否系统默认定义 */
        private Integer isSystemDefault;

        /** 状态编码 */
        private String status;

        /** 状态文案 */
        private String statusText;

        /** 是否启用 */
        private boolean enabled;
    }

    /**
     * 月历概览。
     */
    @Data
    public static class MonthOverview {

        /** 当前月份，格式 yyyy-MM */
        private String yearMonth;

        /** 月历日期格子 */
        private List<MonthDayItem> days = new ArrayList<>();
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

        /** 是否属于当前查看月份 */
        private boolean currentMonth;

        /** 是否为当前选中日期 */
        private boolean selected;

        /** 当天档期颜色标记 */
        private List<String> colors = new ArrayList<>();

        /** 当天档期数量 */
        private int count;
    }

    /**
     * 选中日期概览。
     */
    @Data
    public static class SelectedDateOverview {

        /** 日期，格式 yyyy-MM-dd */
        private String date;

        /** 摘要文案 */
        private String summaryText;

        /** 档期明细 */
        private List<ScheduleItem> schedules = new ArrayList<>();
    }

    /**
     * 档期明细项。
     */
    @Data
    public static class ScheduleItem {

        /** 档期 ID */
        private Long id;

        /** 档期日期 */
        private String scheduleDate;

        /** 档位定义 ID */
        private Long slotDefinitionId;

        /** 档位名称快照 */
        private String slotName;

        /** 开始时间快照 */
        private String startTime;

        /** 结束时间快照 */
        private String endTime;

        /** 颜色快照 */
        private String color;

        /** 档期状态编码 */
        private String status;

        /** 档期状态文案 */
        private String statusText;

        /** 状态色调，供小程序拼样式类 */
        private String statusTone;

        /** 联系人姓名，仅维护者端可见 */
        private String contactName;

        /** 联系电话，仅维护者端可见 */
        private String contactPhone;

        /** 档期备注，仅维护者端可见 */
        private String note;

        /** 是否锁定档位快照 */
        private Integer lockedSnapshot;

        /** 明细描述文案 */
        private String descText;
    }
}
