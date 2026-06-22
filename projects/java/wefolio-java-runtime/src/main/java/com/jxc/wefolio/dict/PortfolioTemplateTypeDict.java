package com.jxc.wefolio.dict;

import java.util.Arrays;
import java.util.Map;
import java.util.stream.Collectors;


/**
 * 作品集模板类型字典
 */
public enum PortfolioTemplateTypeDict {

    STANDARD("STANDARD", "标准"),
        ADVANCED("ADVANCED", "高级");

    private final String code;
    private final String displayName;

    private static final Map<String, PortfolioTemplateTypeDict> CODE_MAP =
        Arrays.stream(values()).collect(Collectors.toMap(PortfolioTemplateTypeDict::getCode, v -> v));

    PortfolioTemplateTypeDict(String code, String displayName) {
        this.code = code;
        this.displayName = displayName;
    }

    public String getCode() { return code; }
    public String getDisplayName() { return displayName; }

    public static PortfolioTemplateTypeDict fromCode(String code) {
        return CODE_MAP.get(code);
    }
}
