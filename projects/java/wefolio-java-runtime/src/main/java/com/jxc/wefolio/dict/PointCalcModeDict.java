package com.jxc.wefolio.dict;

import java.util.Arrays;
import java.util.Map;
import java.util.stream.Collectors;


/**
 * 积分计算模式字典
 */
public enum PointCalcModeDict {

    FIXED_PER_ACTION("FIXED_PER_ACTION", "单次固定"),
    ACCUMULATED_THRESHOLD("ACCUMULATED_THRESHOLD", "累计阶梯"),
    RECHARGE_PACKAGE("RECHARGE_PACKAGE", "充值档位"),
    MANUAL_ADJUSTMENT("MANUAL_ADJUSTMENT", "人工调整");

    private final String code;
    private final String displayName;

    private static final Map<String, PointCalcModeDict> CODE_MAP =
        Arrays.stream(values()).collect(Collectors.toMap(PointCalcModeDict::getCode, v -> v));

    PointCalcModeDict(String code, String displayName) {
        this.code = code;
        this.displayName = displayName;
    }

    public String getCode() { return code; }
    public String getDisplayName() { return displayName; }

    public static PointCalcModeDict fromCode(String code) {
        return CODE_MAP.get(code);
    }
}
