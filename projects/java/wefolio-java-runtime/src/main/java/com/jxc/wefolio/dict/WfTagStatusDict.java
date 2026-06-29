package com.jxc.wefolio.dict;

import java.util.Arrays;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 作品标签状态字典。
 */
public enum WfTagStatusDict {

    /** 启用 */
    ACTIVE("ACTIVE", "启用"),

    /** 停用 */
    DISABLED("DISABLED", "停用");

    /** 状态编码 */
    private final String code;

    /** 展示名称 */
    private final String displayName;

    /** 编码索引 */
    private static final Map<String, WfTagStatusDict> CODE_MAP =
            Arrays.stream(values()).collect(Collectors.toMap(WfTagStatusDict::getCode, value -> value));

    WfTagStatusDict(String code, String displayName) {
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
     * 根据编码查找状态。
     *
     * @param code 状态编码
     * @return 状态字典，未命中时返回 null
     */
    public static WfTagStatusDict fromCode(String code) {
        return CODE_MAP.get(code);
    }
}
