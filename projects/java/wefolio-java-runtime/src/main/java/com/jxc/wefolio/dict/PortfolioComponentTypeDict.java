package com.jxc.wefolio.dict;

import java.util.Arrays;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 标准作品集组件类型字典。
 */
public enum PortfolioComponentTypeDict {

    CAROUSEL("CAROUSEL", "轮播图", 2),
    PROFILE("PROFILE", "个人资料", 2),
    SCHEDULE_QUERY("SCHEDULE_QUERY", "档期查询", 2),
    WORK_GRID("WORK_GRID", "双列作品列表", 2),
    WORK_LIST("WORK_LIST", "单列作品列表", 2),
    SINGLE_WORK("SINGLE_WORK", "单个作品", 2),
    HYPERLINK("HYPERLINK", "超链接", 3),
    VIDEO_CAROUSEL("VIDEO_CAROUSEL", "视频轮播", 4),
    QR_CONTACT("QR_CONTACT", "二维码联系", 2),
    CONTACT_FORM("CONTACT_FORM", "预留联系信息", 2),
    TEXT_SECTION("TEXT_SECTION", "文字说明", 2),
    DIVIDER("DIVIDER", "分割线", 2),
    /** 独立区块样式的结构化文字说明。 */
    STRUCTURED_TEXT_SECTION("STRUCTURED_TEXT_SECTION", "结构化文字说明", 5);

    private final String code;
    private final String displayName;

    /** 组件首次可由编辑器识别的能力版本。 */
    private final int introducedAtRevision;

    private static final Map<String, PortfolioComponentTypeDict> CODE_MAP =
            Arrays.stream(values()).collect(Collectors.toMap(PortfolioComponentTypeDict::getCode, value -> value));

    PortfolioComponentTypeDict(String code, String displayName, int introducedAtRevision) {
        this.code = code;
        this.displayName = displayName;
        this.introducedAtRevision = introducedAtRevision;
    }

    public String getCode() {
        return code;
    }

    public String getDisplayName() {
        return displayName;
    }

    /**
     * 获取组件首次引入的编辑器能力版本。
     *
     * @return 首次引入版本
     */
    public int getIntroducedAtRevision() {
        return introducedAtRevision;
    }

    public static PortfolioComponentTypeDict fromCode(String code) {
        return CODE_MAP.get(code);
    }
}
