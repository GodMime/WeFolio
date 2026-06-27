package com.jxc.wefolio.dto;

import lombok.Data;

/**
 * 档位定义启停请求。
 */
@Data
public class ScheduleSlotDefinitionStatusRequest {

    /** 状态编码：ACTIVE 启用 / DISABLED 停用 */
    private String status;
}
