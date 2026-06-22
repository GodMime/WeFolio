package com.jxc.wefolio.dict;

import java.util.Arrays;
import java.util.Map;
import java.util.stream.Collectors;


/**
 * 访问来源类型字典
 */
public enum VisitSourceTypeDict {

    WECHAT_SHARE_CARD("WECHAT_SHARE_CARD", "分享卡片"),
        QR_CODE("QR_CODE", "二维码"),
        TEAM_PORTFOLIO("TEAM_PORTFOLIO", "团队作品集跳转"),
        PERSONAL_PORTFOLIO("PERSONAL_PORTFOLIO", "个人作品集跳转"),
        UNKNOWN("UNKNOWN", "未知");

    private final String code;
    private final String displayName;

    private static final Map<String, VisitSourceTypeDict> CODE_MAP =
        Arrays.stream(values()).collect(Collectors.toMap(VisitSourceTypeDict::getCode, v -> v));

    VisitSourceTypeDict(String code, String displayName) {
        this.code = code;
        this.displayName = displayName;
    }

    public String getCode() { return code; }
    public String getDisplayName() { return displayName; }

    public static VisitSourceTypeDict fromCode(String code) {
        return CODE_MAP.get(code);
    }
}
