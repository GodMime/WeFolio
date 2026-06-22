package com.jxc.wefolio.dict;

import java.util.Arrays;
import java.util.Map;
import java.util.stream.Collectors;


/**
 * 作品集归属类型字典
 */
public enum PortfolioOwnerTypeDict {

    USER("USER", "用户"),
        TEAM("TEAM", "团队");

    private final String code;
    private final String displayName;

    private static final Map<String, PortfolioOwnerTypeDict> CODE_MAP =
        Arrays.stream(values()).collect(Collectors.toMap(PortfolioOwnerTypeDict::getCode, v -> v));

    PortfolioOwnerTypeDict(String code, String displayName) {
        this.code = code;
        this.displayName = displayName;
    }

    public String getCode() { return code; }
    public String getDisplayName() { return displayName; }

    public static PortfolioOwnerTypeDict fromCode(String code) {
        return CODE_MAP.get(code);
    }
}
