package com.jxc.wefolio.job.dto;

import lombok.Data;

/**
 * 主动执行作品存储结算请求。
 */
@Data
public class WorkStorageBillingExecuteRequest {

    /** 可选账期，格式 yyyy-MM */
    private String billingMonth;
}
