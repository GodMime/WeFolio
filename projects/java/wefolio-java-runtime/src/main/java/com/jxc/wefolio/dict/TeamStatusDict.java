package com.jxc.wefolio.dict;

import java.util.Arrays;
import java.util.Map;
import java.util.stream.Collectors;


/**
 * 团队状态字典
 */
public enum TeamStatusDict {

    ACTIVE("ACTIVE", "正常"),
        DISSOLVED("DISSOLVED", "已解散");

    private final String code;
    private final String displayName;

    private static final Map<String, TeamStatusDict> CODE_MAP =
        Arrays.stream(values()).collect(Collectors.toMap(TeamStatusDict::getCode, v -> v));

    TeamStatusDict(String code, String displayName) {
        this.code = code;
        this.displayName = displayName;
    }

    public String getCode() { return code; }
    public String getDisplayName() { return displayName; }

    public static TeamStatusDict fromCode(String code) {
        return CODE_MAP.get(code);
    }
}
