package com.jxc.wefolio.dict;

import java.util.Arrays;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 团队成员信息变更确认状态字典。
 */
public enum TeamMemberChangeStatusDict {

    PENDING_CONFIRMATION("PENDING_CONFIRMATION", "待同意"),
    ACCEPTED("ACCEPTED", "已同意"),
    REJECTED("REJECTED", "已拒绝"),
    INVALIDATED("INVALIDATED", "已失效");

    /** 状态编码 */
    private final String code;

    /** 展示名称 */
    private final String displayName;

    /** 编码映射 */
    private static final Map<String, TeamMemberChangeStatusDict> CODE_MAP =
            Arrays.stream(values()).collect(Collectors.toMap(TeamMemberChangeStatusDict::getCode, v -> v));

    TeamMemberChangeStatusDict(String code, String displayName) {
        this.code = code;
        this.displayName = displayName;
    }

    public String getCode() {
        return code;
    }

    public String getDisplayName() {
        return displayName;
    }

    /**
     * 按编码读取字典项。
     *
     * @param code 状态编码
     * @return 字典项，不存在时返回 null
     */
    public static TeamMemberChangeStatusDict fromCode(String code) {
        return CODE_MAP.get(code);
    }
}
