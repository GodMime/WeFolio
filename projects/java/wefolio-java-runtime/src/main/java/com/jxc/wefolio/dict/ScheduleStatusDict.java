package com.jxc.wefolio.dict;

import java.util.Arrays;
import java.util.Map;
import java.util.stream.Collectors;


/**
 * 档期预约状态字典
 */
public enum ScheduleStatusDict {

    AVAILABLE("AVAILABLE", "空闲"),
        BOOKED("BOOKED", "已约"),
        TENTATIVE("TENTATIVE", "待定"),
        REST("REST", "休息");

    private final String code;
    private final String displayName;

    private static final Map<String, ScheduleStatusDict> CODE_MAP =
        Arrays.stream(values()).collect(Collectors.toMap(ScheduleStatusDict::getCode, v -> v));

    ScheduleStatusDict(String code, String displayName) {
        this.code = code;
        this.displayName = displayName;
    }

    public String getCode() { return code; }
    public String getDisplayName() { return displayName; }

    public static ScheduleStatusDict fromCode(String code) {
        return CODE_MAP.get(code);
    }
}
