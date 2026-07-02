package com.jxc.wefolio.dict;

import java.util.Arrays;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 作品集发布状态字典。
 */
public enum PortfolioPublicationStatusDict {

    DRAFT_ONLY("DRAFT_ONLY", "仅草稿"),
    PUBLISHED("PUBLISHED", "已发布"),
    OFFLINE("OFFLINE", "已下线");

    private final String code;
    private final String displayName;

    private static final Map<String, PortfolioPublicationStatusDict> CODE_MAP =
            Arrays.stream(values()).collect(Collectors.toMap(PortfolioPublicationStatusDict::getCode, value -> value));

    PortfolioPublicationStatusDict(String code, String displayName) {
        this.code = code;
        this.displayName = displayName;
    }

    public String getCode() {
        return code;
    }

    public String getDisplayName() {
        return displayName;
    }

    public static PortfolioPublicationStatusDict fromCode(String code) {
        return CODE_MAP.get(code);
    }
}
