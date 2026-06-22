package com.jxc.wefolio.dict;

import java.util.Arrays;
import java.util.Map;
import java.util.stream.Collectors;


/**
 * 访问行为事件类型字典
 */
public enum VisitEventTypeDict {

    PORTFOLIO_OPENED("PORTFOLIO_OPENED", "打开作品集"),
        WORK_VIEWED("WORK_VIEWED", "查看作品"),
        VIDEO_PLAYED("VIDEO_PLAYED", "播放视频"),
        SCHEDULE_QUERIED("SCHEDULE_QUERIED", "查询档期"),
        QR_CODE_INTERACTED("QR_CODE_INTERACTED", "二维码交互"),
        MEMBER_PORTFOLIO_OPENED("MEMBER_PORTFOLIO_OPENED", "打开成员作品集"),
        CONTACT_FORM_EXPOSED("CONTACT_FORM_EXPOSED", "联系表单曝光"),
        CONTACT_LEAD_SUBMITTED("CONTACT_LEAD_SUBMITTED", "提交联系线索");

    private final String code;
    private final String displayName;

    private static final Map<String, VisitEventTypeDict> CODE_MAP =
        Arrays.stream(values()).collect(Collectors.toMap(VisitEventTypeDict::getCode, v -> v));

    VisitEventTypeDict(String code, String displayName) {
        this.code = code;
        this.displayName = displayName;
    }

    public String getCode() { return code; }
    public String getDisplayName() { return displayName; }

    public static VisitEventTypeDict fromCode(String code) {
        return CODE_MAP.get(code);
    }
}
