package com.jxc.wefolio.dto;

import lombok.Data;

/**
 * 档位定义保存请求。
 */
@Data
public class ScheduleSlotDefinitionRequest {

    /** 档位名称 */
    private String name;

    /** 开始时间，格式 HH:mm */
    private String startTime;

    /** 结束时间，格式 HH:mm */
    private String endTime;

    /** 十六进制颜色 */
    private String color;

    /** 启用状态，可为空，新增时默认启用 */
    private String status;
}
