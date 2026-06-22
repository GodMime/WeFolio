package com.jxc.wefolio.dict;

import java.util.Arrays;
import java.util.Map;
import java.util.stream.Collectors;


/**
 * 作品处理状态字典
 */
public enum WorkStatusDict {

    ACTIVE("ACTIVE", "正常"),
        PROCESSING("PROCESSING", "处理中"),
        PROCESSING_FAILED("PROCESSING_FAILED", "处理失败");

    private final String code;
    private final String displayName;

    private static final Map<String, WorkStatusDict> CODE_MAP =
        Arrays.stream(values()).collect(Collectors.toMap(WorkStatusDict::getCode, v -> v));

    WorkStatusDict(String code, String displayName) {
        this.code = code;
        this.displayName = displayName;
    }

    public String getCode() { return code; }
    public String getDisplayName() { return displayName; }

    public static WorkStatusDict fromCode(String code) {
        return CODE_MAP.get(code);
    }
}
