package com.jxc.wefolio.dict;

import java.util.Arrays;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 团队作品集组件类型字典。
 */
public enum TeamPortfolioComponentTypeDict {

    /** 团队资料 */
    TEAM_PROFILE("TEAM_PROFILE", "团队资料"),
    /** 轮播图 */
    CAROUSEL("CAROUSEL", "轮播图"),
    /** 分割线 */
    DIVIDER("DIVIDER", "分割线"),
    /** 双列成员作品集 */
    MEMBER_PORTFOLIO_GRID("MEMBER_PORTFOLIO_GRID", "双列作品集"),
    /** 单列成员作品集 */
    MEMBER_PORTFOLIO_LIST("MEMBER_PORTFOLIO_LIST", "单列作品集"),
    /** 文字说明 */
    TEXT_SECTION("TEXT_SECTION", "文字说明"),
    /** 档期查询 */
    SCHEDULE_QUERY("SCHEDULE_QUERY", "档期查询"),
    /** 预留联系信息 */
    CONTACT_FORM("CONTACT_FORM", "预留联系信息"),
    /** 二维码联系 */
    QR_CONTACT("QR_CONTACT", "二维码联系");

    /** 组件编码 */
    private final String code;

    /** 展示名称 */
    private final String displayName;

    /** 编码映射 */
    private static final Map<String, TeamPortfolioComponentTypeDict> CODE_MAP =
            Arrays.stream(values()).collect(Collectors.toMap(TeamPortfolioComponentTypeDict::getCode, value -> value));

    TeamPortfolioComponentTypeDict(String code, String displayName) {
        this.code = code;
        this.displayName = displayName;
    }

    /**
     * 获取组件编码。
     *
     * @return 组件编码
     */
    public String getCode() {
        return code;
    }

    /**
     * 获取展示名称。
     *
     * @return 展示名称
     */
    public String getDisplayName() {
        return displayName;
    }

    /**
     * 按编码读取组件类型。
     *
     * @param code 组件编码
     * @return 组件类型，不存在时返回 null
     */
    public static TeamPortfolioComponentTypeDict fromCode(String code) {
        return CODE_MAP.get(code);
    }
}
