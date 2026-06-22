package com.jxc.wefolio.dict;

import java.util.Arrays;
import java.util.Map;
import java.util.stream.Collectors;


/**
 * 支付渠道字典
 */
public enum PayChannelDict {

    WECHAT_PAY("WECHAT_PAY", "微信支付");

    private final String code;
    private final String displayName;

    private static final Map<String, PayChannelDict> CODE_MAP =
        Arrays.stream(values()).collect(Collectors.toMap(PayChannelDict::getCode, v -> v));

    PayChannelDict(String code, String displayName) {
        this.code = code;
        this.displayName = displayName;
    }

    public String getCode() { return code; }
    public String getDisplayName() { return displayName; }

    public static PayChannelDict fromCode(String code) {
        return CODE_MAP.get(code);
    }
}
