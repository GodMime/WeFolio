package com.jxc.wefolio.dict;

import java.util.Arrays;
import java.util.Map;
import java.util.stream.Collectors;


/**
 * 积分流水类型字典
 */
public enum PointTransactionTypeDict {

    RECHARGE("RECHARGE", "充值"),
    CONSUMPTION("CONSUMPTION", "消耗"),
    REFUND("REFUND", "回退"),
    GIFT("GIFT", "赠送");

    private final String code;
    private final String displayName;

    private static final Map<String, PointTransactionTypeDict> CODE_MAP =
        Arrays.stream(values()).collect(Collectors.toMap(PointTransactionTypeDict::getCode, v -> v));

    PointTransactionTypeDict(String code, String displayName) {
        this.code = code;
        this.displayName = displayName;
    }

    public String getCode() { return code; }
    public String getDisplayName() { return displayName; }

    public static PointTransactionTypeDict fromCode(String code) {
        return CODE_MAP.get(code);
    }
}
