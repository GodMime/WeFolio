package com.jxc.wefolio.job.dto;

import lombok.AllArgsConstructor;
import lombok.Data;

/**
 * 作品存储结算异步提交响应。
 */
@Data
@AllArgsConstructor
public class WorkStorageBillingExecutionResponse {

    /** 是否接受本次提交 */
    private boolean accepted;

    /** 当前执行 ID */
    private String executionId;

    /** 当前执行账期 */
    private String billingMonth;

    /** ACCEPTED 或 ALREADY_RUNNING */
    private String executionStatus;
}
