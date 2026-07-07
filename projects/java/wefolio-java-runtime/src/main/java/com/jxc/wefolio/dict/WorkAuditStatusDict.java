package com.jxc.wefolio.dict;

import java.util.Arrays;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 作品审核状态字典
 */
public enum WorkAuditStatusDict {

    PENDING("PENDING", "未审核"),
    AUDITING("AUDITING", "审核中"),
    PASSED("PASSED", "审核通过"),
    REJECTED("REJECTED", "确认违规"),
    REVIEW_REQUIRED("REVIEW_REQUIRED", "疑似违规"),
    FAILED("FAILED", "审核失败");

    private final String code;
    private final String displayName;

    private static final Map<String, WorkAuditStatusDict> CODE_MAP =
            Arrays.stream(values()).collect(Collectors.toMap(WorkAuditStatusDict::getCode, v -> v));

    WorkAuditStatusDict(String code, String displayName) {
        this.code = code;
        this.displayName = displayName;
    }

    public String getCode() {
        return code;
    }

    public String getDisplayName() {
        return displayName;
    }

    public static WorkAuditStatusDict fromCode(String code) {
        return CODE_MAP.get(code);
    }
}
