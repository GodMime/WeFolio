package com.jxc.wefolio.dict;

import java.util.Arrays;
import java.util.Map;
import java.util.stream.Collectors;


/**
 * 档期预约状态字典
 */
public enum ScheduleStatusDict {

    BOOKED("BOOKED", "已约", "rose"),
    TENTATIVE("TENTATIVE", "待定", "amber"),
    REST("REST", "休息", "muted");

    private final String code;
    private final String displayName;
    private final String tone;

    private static final Map<String, ScheduleStatusDict> CODE_MAP =
        Arrays.stream(values()).collect(Collectors.toMap(ScheduleStatusDict::getCode, v -> v));

    ScheduleStatusDict(String code, String displayName, String tone) {
        this.code = code;
        this.displayName = displayName;
        this.tone = tone;
    }

    public String getCode() { return code; }
    public String getDisplayName() { return displayName; }
    public String getTone() { return tone; }

    public static ScheduleStatusDict fromCode(String code) {
        return CODE_MAP.get(code);
    }
}
