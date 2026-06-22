package com.jxc.wefolio.dict;

import java.util.Arrays;
import java.util.Map;
import java.util.stream.Collectors;


/**
 * AI 生成任务状态字典
 */
public enum AiTaskStatusDict {

    PENDING("PENDING", "待执行"),
        RUNNING("RUNNING", "执行中"),
        SUCCEEDED("SUCCEEDED", "成功"),
        FAILED("FAILED", "失败"),
        TIMED_OUT("TIMED_OUT", "超时");

    private final String code;
    private final String displayName;

    private static final Map<String, AiTaskStatusDict> CODE_MAP =
        Arrays.stream(values()).collect(Collectors.toMap(AiTaskStatusDict::getCode, v -> v));

    AiTaskStatusDict(String code, String displayName) {
        this.code = code;
        this.displayName = displayName;
    }

    public String getCode() { return code; }
    public String getDisplayName() { return displayName; }

    public static AiTaskStatusDict fromCode(String code) {
        return CODE_MAP.get(code);
    }
}
