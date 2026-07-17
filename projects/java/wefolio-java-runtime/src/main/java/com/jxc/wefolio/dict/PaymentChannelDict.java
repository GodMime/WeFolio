package com.jxc.wefolio.dict;

import java.util.Arrays;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 支付渠道字典。
 */
public enum PaymentChannelDict {

    /** 微信支付。 */
    WECHAT_PAY("WECHAT_PAY", "微信支付");

    /** 渠道编码。 */
    private final String code;

    /** 展示名称。 */
    private final String displayName;

    /** 渠道编码映射。 */
    private static final Map<String, PaymentChannelDict> CODE_MAP =
            Arrays.stream(values()).collect(Collectors.toMap(PaymentChannelDict::getCode, value -> value));

    PaymentChannelDict(String code, String displayName) {
        this.code = code;
        this.displayName = displayName;
    }

    public String getCode() {
        return code;
    }

    public String getDisplayName() {
        return displayName;
    }

    /**
     * 根据渠道编码获取字典值。
     *
     * @param code 渠道编码
     * @return 对应字典值，不存在时返回 {@code null}
     */
    public static PaymentChannelDict fromCode(String code) {
        return CODE_MAP.get(code);
    }
}
