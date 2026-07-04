package com.jxc.wefolio.dto;

import lombok.Data;

/**
 * 作品集档期查询提交响应。
 */
@Data
public class PortfolioScheduleQueryResponse {

    /** 查询日期，格式 yyyy-MM-dd */
    private String queriedDate;

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

    /** 是否可约 */
    private boolean available;

    /** 结果提示 */
    private String message;
}
