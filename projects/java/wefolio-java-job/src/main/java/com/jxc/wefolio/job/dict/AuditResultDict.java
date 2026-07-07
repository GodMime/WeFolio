package com.jxc.wefolio.job.dict;

/**
 * 审核结果字典。
 */
public enum AuditResultDict {

    PASS("PASS"),
    BLOCK("BLOCK"),
    REVIEW("REVIEW"),
    UNKNOWN("UNKNOWN");

    private final String code;

    AuditResultDict(String code) {
        this.code = code;
    }

    public String getCode() {
        return code;
    }
}
