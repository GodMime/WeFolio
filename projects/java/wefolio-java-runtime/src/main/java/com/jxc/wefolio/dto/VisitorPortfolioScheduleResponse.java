package com.jxc.wefolio.dto;

import lombok.Data;

import java.util.List;

/**
 * 访客档期查询响应。
 */
@Data
public class VisitorPortfolioScheduleResponse {

    /** 档期列表 */
    private List<Item> schedules;

    /**
     * 访客可见档期项。
     */
    @Data
    public static class Item {

        /** 日期 */
        private String date;

        /** 档位名称 */
        private String slotName;

        /** 开始时间 */
        private String startTime;

        /** 结束时间 */
        private String endTime;

        /** 展示颜色 */
        private String color;

        /** 状态编码 */
        private String status;

        /** 状态文案 */
        private String statusText;

        /** 状态色调 */
        private String statusTone;

        /** 内部联系人，访客端始终为空 */
        private String contactName;

        /** 内部联系电话，访客端始终为空 */
        private String contactPhone;

        /** 内部备注，访客端始终为空 */
        private String note;
    }
}
