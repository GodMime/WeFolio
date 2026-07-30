package com.jxc.wefolio.job.dto;

import lombok.AllArgsConstructor;
import lombok.Data;

/**
 * 历史用户目录修复异步提交响应。
 */
@Data
@AllArgsConstructor
public class UserStorageFolderRepairExecutionResponse {

    /** 是否接受本次提交 */
    private boolean accepted;

    /** 当前执行 ID */
    private String executionId;

    /** ACCEPTED 或 ALREADY_RUNNING */
    private String executionStatus;
}
