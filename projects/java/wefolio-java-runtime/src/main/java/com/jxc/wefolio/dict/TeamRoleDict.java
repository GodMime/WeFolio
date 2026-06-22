package com.jxc.wefolio.dict;

import java.util.Arrays;
import java.util.Map;
import java.util.stream.Collectors;


/**
 * 团队成员角色字典
 */
public enum TeamRoleDict {

    OWNER("OWNER", "拥有者"),
        MANAGER("MANAGER", "管理者"),
        MEMBER("MEMBER", "普通成员");

    private final String code;
    private final String displayName;

    private static final Map<String, TeamRoleDict> CODE_MAP =
        Arrays.stream(values()).collect(Collectors.toMap(TeamRoleDict::getCode, v -> v));

    TeamRoleDict(String code, String displayName) {
        this.code = code;
        this.displayName = displayName;
    }

    public String getCode() { return code; }
    public String getDisplayName() { return displayName; }

    public static TeamRoleDict fromCode(String code) {
        return CODE_MAP.get(code);
    }
}
