package com.jxc.wefolio.dict;

import java.util.Arrays;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 团队成员档期状态字典。
 */
public enum TeamScheduleMemberStatusDict {

    /** 空闲 */
    AVAILABLE("AVAILABLE", "空闲"),
    /** 部分档期空闲 */
    PARTIAL_AVAILABLE("PARTIAL_AVAILABLE", "部分档期空闲"),
    /** 已满 */
    FULL("FULL", "已满");

    /** 状态编码 */
    private final String code;

    /** 展示名称 */
    private final String displayName;

    /** 编码映射 */
    private static final Map<String, TeamScheduleMemberStatusDict> CODE_MAP =
            Arrays.stream(values()).collect(Collectors.toMap(TeamScheduleMemberStatusDict::getCode, value -> value));

    TeamScheduleMemberStatusDict(String code, String displayName) {
        this.code = code;
        this.displayName = displayName;
    }

    /**
     * 获取状态编码。
     *
     * @return 状态编码
     */
    public String getCode() {
        return code;
    }

    /**
     * 获取展示名称。
     *
     * @return 展示名称
     */
    public String getDisplayName() {
        return displayName;
    }

    /**
     * 按编码读取成员档期状态。
     *
     * @param code 状态编码
     * @return 成员档期状态，不存在时返回 null
     */
    public static TeamScheduleMemberStatusDict fromCode(String code) {
        return CODE_MAP.get(code);
    }
}
