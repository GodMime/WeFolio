package com.jxc.wefolio.service.teamportfolio.component.schedulequery;

import lombok.Data;

/**
 * 团队档期查询组件配置。
 */
@Data
public class TeamScheduleQueryComponentConfig {

    /** 组件标题。 */
    private String title;

    /** 组件描述。 */
    private String description;

    /** 展示方式。 */
    private String displayMode;

    /** 可查询日期范围。 */
    private QueryRange queryRange;

    /**
     * 可查询日期范围配置。
     */
    @Data
    public static class QueryRange {

        /** 查询范围类型。 */
        private String type;

        /** 可查询未来天数。 */
        private Integer futureDays;

        /** 固定范围开始日期。 */
        private String startDate;

        /** 固定范围结束日期。 */
        private String endDate;
    }
}
