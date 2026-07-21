package com.jxc.wefolio.dict;

import java.util.Arrays;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 团队档期查询结果状态字典。
 */
public enum TeamScheduleResultStatusDict {

    /** 全部成员空闲 */
    TEAM_AVAILABLE("TEAM_AVAILABLE", "全部空闲", true),
    /** 部分成员可约 */
    TEAM_PARTIAL_AVAILABLE("TEAM_PARTIAL_AVAILABLE", "部分成员可约", true),
    /** 团队已满 */
    TEAM_FULL("TEAM_FULL", "已满", false);

    /** 状态编码 */
    private final String code;

    /** 展示名称 */
    private final String displayName;

    /** 是否可约 */
    private final boolean available;

    /** 编码映射 */
    private static final Map<String, TeamScheduleResultStatusDict> CODE_MAP =
            Arrays.stream(values()).collect(Collectors.toMap(TeamScheduleResultStatusDict::getCode, value -> value));

    TeamScheduleResultStatusDict(String code, String displayName, boolean available) {
        this.code = code;
        this.displayName = displayName;
        this.available = available;
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
     * 获取是否可约。
     *
     * @return 是否可约
     */
    public boolean getAvailable() {
        return available;
    }

    /**
     * 按编码读取团队档期查询结果状态。
     *
     * @param code 状态编码
     * @return 团队档期查询结果状态，不存在时返回 null
     */
    public static TeamScheduleResultStatusDict fromCode(String code) {
        return CODE_MAP.get(code);
    }
}
