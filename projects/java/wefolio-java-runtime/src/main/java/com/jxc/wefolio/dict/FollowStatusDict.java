package com.jxc.wefolio.dict;

import java.util.Arrays;
import java.util.Map;
import java.util.stream.Collectors;


/**
 * 跟进状态字典
 */
public enum FollowStatusDict {

    NOT_FOLLOWED_UP("NOT_FOLLOWED_UP", "未跟进"),
    CONTACTED("CONTACTED", "已跟进"),
    DEAL_WON("DEAL_WON", "已成交"),
    INVALID("INVALID", "无效");

    private final String code;
    private final String displayName;

    private static final Map<String, FollowStatusDict> CODE_MAP =
        Arrays.stream(values()).collect(Collectors.toMap(FollowStatusDict::getCode, v -> v));

    FollowStatusDict(String code, String displayName) {
        this.code = code;
        this.displayName = displayName;
    }

    public String getCode() { return code; }
    public String getDisplayName() { return displayName; }

    public static FollowStatusDict fromCode(String code) {
        return CODE_MAP.get(code);
    }
}
