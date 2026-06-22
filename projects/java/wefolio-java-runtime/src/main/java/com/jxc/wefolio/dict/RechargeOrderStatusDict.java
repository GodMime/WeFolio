package com.jxc.wefolio.dict;

import java.util.Arrays;
import java.util.Map;
import java.util.stream.Collectors;


/**
 * 充值订单状态字典
 */
public enum RechargeOrderStatusDict {

    PENDING_PAYMENT("PENDING_PAYMENT", "待支付"),
        PAID("PAID", "已支付"),
        PAYMENT_FAILED("PAYMENT_FAILED", "支付失败"),
        CLOSED("CLOSED", "已关闭");

    private final String code;
    private final String displayName;

    private static final Map<String, RechargeOrderStatusDict> CODE_MAP =
        Arrays.stream(values()).collect(Collectors.toMap(RechargeOrderStatusDict::getCode, v -> v));

    RechargeOrderStatusDict(String code, String displayName) {
        this.code = code;
        this.displayName = displayName;
    }

    public String getCode() { return code; }
    public String getDisplayName() { return displayName; }

    public static RechargeOrderStatusDict fromCode(String code) {
        return CODE_MAP.get(code);
    }
}
