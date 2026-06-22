package com.jxc.wefolio.dict;

import java.util.Arrays;
import java.util.Map;
import java.util.stream.Collectors;


/**
 * 作品集状态字典
 */
public enum PortfolioStatusDict {

    ACTIVE("ACTIVE", "生效"),
        DISABLED("DISABLED", "停用");

    private final String code;
    private final String displayName;

    private static final Map<String, PortfolioStatusDict> CODE_MAP =
        Arrays.stream(values()).collect(Collectors.toMap(PortfolioStatusDict::getCode, v -> v));

    PortfolioStatusDict(String code, String displayName) {
        this.code = code;
        this.displayName = displayName;
    }

    public String getCode() { return code; }
    public String getDisplayName() { return displayName; }

    public static PortfolioStatusDict fromCode(String code) {
        return CODE_MAP.get(code);
    }
}
