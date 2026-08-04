package com.jxc.wefolio.dict;

import java.util.Arrays;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 标准作品集组件类型字典。
 */
public enum PortfolioComponentTypeDict {

    CAROUSEL("CAROUSEL", "轮播图"),
    PROFILE("PROFILE", "个人资料"),
    SCHEDULE_QUERY("SCHEDULE_QUERY", "档期查询"),
    WORK_GRID("WORK_GRID", "双列作品列表"),
    WORK_LIST("WORK_LIST", "单列作品列表"),
    SINGLE_WORK("SINGLE_WORK", "单个作品"),
    HYPERLINK("HYPERLINK", "超链接"),
    QR_CONTACT("QR_CONTACT", "二维码联系"),
    CONTACT_FORM("CONTACT_FORM", "预留联系信息"),
    TEXT_SECTION("TEXT_SECTION", "文字说明"),
    DIVIDER("DIVIDER", "分割线");

    private final String code;
    private final String displayName;

    private static final Map<String, PortfolioComponentTypeDict> CODE_MAP =
            Arrays.stream(values()).collect(Collectors.toMap(PortfolioComponentTypeDict::getCode, value -> value));

    PortfolioComponentTypeDict(String code, String displayName) {
        this.code = code;
        this.displayName = displayName;
    }

    public String getCode() {
        return code;
    }

    public String getDisplayName() {
        return displayName;
    }

    public static PortfolioComponentTypeDict fromCode(String code) {
        return CODE_MAP.get(code);
    }
}
