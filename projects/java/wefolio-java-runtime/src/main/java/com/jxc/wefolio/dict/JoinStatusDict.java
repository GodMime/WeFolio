package com.jxc.wefolio.dict;

import java.util.Arrays;
import java.util.Map;
import java.util.stream.Collectors;


/**
 * 团队成员加入状态字典
 */
public enum JoinStatusDict {

    PENDING_CONFIRMATION("PENDING_CONFIRMATION", "待确认"),
        JOINED("JOINED", "已加入"),
        REJECTED("REJECTED", "已拒绝"),
        REMOVED("REMOVED", "已移除");

    private final String code;
    private final String displayName;

    private static final Map<String, JoinStatusDict> CODE_MAP =
        Arrays.stream(values()).collect(Collectors.toMap(JoinStatusDict::getCode, v -> v));

    JoinStatusDict(String code, String displayName) {
        this.code = code;
        this.displayName = displayName;
    }

    public String getCode() { return code; }
    public String getDisplayName() { return displayName; }

    public static JoinStatusDict fromCode(String code) {
        return CODE_MAP.get(code);
    }
}
