package com.jxc.wefolio.job.dict;

/**
 * 作品审核状态字典。
 */
public enum WorkAuditStatusDict {

    PENDING("PENDING"),
    AUDITING("AUDITING"),
    PASSED("PASSED"),
    REJECTED("REJECTED"),
    REVIEW_REQUIRED("REVIEW_REQUIRED"),
    FAILED("FAILED");

    private final String code;

    WorkAuditStatusDict(String code) {
        this.code = code;
    }

    public String getCode() {
        return code;
    }
}
