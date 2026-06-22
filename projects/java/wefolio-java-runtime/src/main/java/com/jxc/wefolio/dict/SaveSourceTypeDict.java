package com.jxc.wefolio.dict;

import java.util.Arrays;
import java.util.Map;
import java.util.stream.Collectors;


/**
 * 作品集保存来源字典
 */
public enum SaveSourceTypeDict {

    MANUAL("MANUAL", "手工配置"),
        AI_GENERATED("AI_GENERATED", "AI生成"),
        RESTORED_FROM_HISTORY("RESTORED_FROM_HISTORY", "历史恢复");

    private final String code;
    private final String displayName;

    private static final Map<String, SaveSourceTypeDict> CODE_MAP =
        Arrays.stream(values()).collect(Collectors.toMap(SaveSourceTypeDict::getCode, v -> v));

    SaveSourceTypeDict(String code, String displayName) {
        this.code = code;
        this.displayName = displayName;
    }

    public String getCode() { return code; }
    public String getDisplayName() { return displayName; }

    public static SaveSourceTypeDict fromCode(String code) {
        return CODE_MAP.get(code);
    }
}
