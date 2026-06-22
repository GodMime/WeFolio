package com.jxc.wefolio.dict;

import java.util.Arrays;
import java.util.Map;
import java.util.stream.Collectors;


/**
 * 用户/通用状态字典
 */
public enum UserStatusDict {

    ACTIVE("ACTIVE", "正常"),
        DISABLED("DISABLED", "禁用");

    private final String code;
    private final String displayName;

    private static final Map<String, UserStatusDict> CODE_MAP =
        Arrays.stream(values()).collect(Collectors.toMap(UserStatusDict::getCode, v -> v));

    UserStatusDict(String code, String displayName) {
        this.code = code;
        this.displayName = displayName;
    }

    public String getCode() { return code; }
    public String getDisplayName() { return displayName; }

    public static UserStatusDict fromCode(String code) {
        return CODE_MAP.get(code);
    }
}
