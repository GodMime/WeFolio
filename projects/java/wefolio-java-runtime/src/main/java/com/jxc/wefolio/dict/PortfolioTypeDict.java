package com.jxc.wefolio.dict;

import java.util.Arrays;
import java.util.Map;
import java.util.stream.Collectors;


/**
 * 作品集类型字典（个人/团队）
 */
public enum PortfolioTypeDict {

    PERSONAL("PERSONAL", "个人"),
        TEAM("TEAM", "团队");

    private final String code;
    private final String displayName;

    private static final Map<String, PortfolioTypeDict> CODE_MAP =
        Arrays.stream(values()).collect(Collectors.toMap(PortfolioTypeDict::getCode, v -> v));

    PortfolioTypeDict(String code, String displayName) {
        this.code = code;
        this.displayName = displayName;
    }

    public String getCode() { return code; }
    public String getDisplayName() { return displayName; }

    public static PortfolioTypeDict fromCode(String code) {
        return CODE_MAP.get(code);
    }
}
