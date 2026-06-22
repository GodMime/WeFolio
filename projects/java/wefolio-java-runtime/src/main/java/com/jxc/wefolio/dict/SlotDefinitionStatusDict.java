package com.jxc.wefolio.dict;

import java.util.Arrays;
import java.util.Map;
import java.util.stream.Collectors;


/**
 * 档位定义状态字典
 */
public enum SlotDefinitionStatusDict {

    ACTIVE("ACTIVE", "启用"),
        DISABLED("DISABLED", "停用");

    private final String code;
    private final String displayName;

    private static final Map<String, SlotDefinitionStatusDict> CODE_MAP =
        Arrays.stream(values()).collect(Collectors.toMap(SlotDefinitionStatusDict::getCode, v -> v));

    SlotDefinitionStatusDict(String code, String displayName) {
        this.code = code;
        this.displayName = displayName;
    }

    public String getCode() { return code; }
    public String getDisplayName() { return displayName; }

    public static SlotDefinitionStatusDict fromCode(String code) {
        return CODE_MAP.get(code);
    }
}
