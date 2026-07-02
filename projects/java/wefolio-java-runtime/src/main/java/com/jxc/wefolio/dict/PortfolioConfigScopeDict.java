package com.jxc.wefolio.dict;

import java.util.Arrays;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 作品集配置作用域字典。
 */
public enum PortfolioConfigScopeDict {

    DRAFT("DRAFT", "草稿"),
    PUBLISHED("PUBLISHED", "正式");

    private final String code;
    private final String displayName;

    private static final Map<String, PortfolioConfigScopeDict> CODE_MAP =
            Arrays.stream(values()).collect(Collectors.toMap(PortfolioConfigScopeDict::getCode, value -> value));

    PortfolioConfigScopeDict(String code, String displayName) {
        this.code = code;
        this.displayName = displayName;
    }

    public String getCode() {
        return code;
    }

    public String getDisplayName() {
        return displayName;
    }

    public static PortfolioConfigScopeDict fromCode(String code) {
        return CODE_MAP.get(code);
    }
}
