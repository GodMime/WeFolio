package com.jxc.wefolio.job.dict;

/**
 * 与审核供应商解耦的作品审核风险类型。
 */
public enum WorkAuditReasonCodeDict {

    PORN_CONTENT("PORN_CONTENT"),
    ADVERTISING_CONTENT("ADVERTISING_CONTENT"),
    LOW_QUALITY_CONTENT("LOW_QUALITY_CONTENT"),
    POLITICAL_CONTENT("POLITICAL_CONTENT"),
    TERRORISM_CONTENT("TERRORISM_CONTENT"),
    OTHER_UNSAFE_CONTENT("OTHER_UNSAFE_CONTENT"),
    AUDIT_SERVICE_ERROR("AUDIT_SERVICE_ERROR");

    private final String code;

    WorkAuditReasonCodeDict(String code) {
        this.code = code;
    }

    public String getCode() {
        return code;
    }
}
