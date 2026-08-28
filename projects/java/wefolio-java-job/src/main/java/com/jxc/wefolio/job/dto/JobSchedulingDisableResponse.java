package com.jxc.wefolio.job.dto;

import lombok.AllArgsConstructor;
import lombok.Data;

/**
 * 后台调度停用状态响应。
 */
@Data
@AllArgsConstructor
public class JobSchedulingDisableResponse {

    /** 当前生命周期状态。 */
    private String status;

    /** 尚未自然结束的活动工作数。 */
    private int activeTaskCount;

    /** 本次接口使用的配置等待秒数。 */
    private long timeoutSeconds;
}
