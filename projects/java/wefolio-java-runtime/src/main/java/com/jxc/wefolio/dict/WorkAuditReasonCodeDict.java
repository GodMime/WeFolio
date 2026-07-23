package com.jxc.wefolio.dict;

import java.util.Arrays;
import java.util.Map;
import java.util.stream.Collectors;

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

    private static final Map<String, WorkAuditReasonCodeDict> CODE_MAP =
            Arrays.stream(values()).collect(Collectors.toMap(WorkAuditReasonCodeDict::getCode, value -> value));

    private final String code;

    WorkAuditReasonCodeDict(String code) {
        this.code = code;
    }

    public String getCode() {
        return code;
    }

    /**
     * 按内部稳定代码解析风险类型。
     *
     * @param code 稳定风险代码
     * @return 风险类型，未知代码返回空
     */
    public static WorkAuditReasonCodeDict fromCode(String code) {
        return CODE_MAP.get(code);
    }
}
