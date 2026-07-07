package com.jxc.wefolio.job.dict;

/**
 * 作品审核任务状态字典。
 */
public enum WorkAuditTaskStatusDict {

    PENDING("PENDING"),
    SUBMITTING("SUBMITTING"),
    SUBMITTED("SUBMITTED"),
    RUNNING("RUNNING"),
    QUERYING("QUERYING"),
    SUCCESS("SUCCESS"),
    FAILED("FAILED");

    private final String code;

    WorkAuditTaskStatusDict(String code) {
        this.code = code;
    }

    public String getCode() {
        return code;
    }
}
