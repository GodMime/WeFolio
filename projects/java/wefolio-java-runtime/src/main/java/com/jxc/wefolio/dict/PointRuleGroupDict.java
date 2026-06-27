package com.jxc.wefolio.dict;

import java.util.Arrays;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 积分规则分组字典 — 用于维护者端积分规则页分组展示。
 */
public enum PointRuleGroupDict {

    MAINTENANCE("MAINTENANCE", "维护"),
    VISITOR("VISITOR", "访客"),
    OTHER("OTHER", "其他");

    /** 分组编码 */
    private final String code;

    /** 展示名称 */
    private final String displayName;

    /** 编码映射 */
    private static final Map<String, PointRuleGroupDict> CODE_MAP =
            Arrays.stream(values()).collect(Collectors.toMap(PointRuleGroupDict::getCode, v -> v));

    PointRuleGroupDict(String code, String displayName) {
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
     * @param code 分组编码
     * @return 字典项，不存在时返回 null
     */
    public static PointRuleGroupDict fromCode(String code) {
        return CODE_MAP.get(code);
    }
}
