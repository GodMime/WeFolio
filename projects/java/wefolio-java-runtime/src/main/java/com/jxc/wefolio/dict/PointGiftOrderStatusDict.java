package com.jxc.wefolio.dict;

import java.util.Arrays;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 微信代币赠送订单状态字典。
 */
public enum PointGiftOrderStatusDict {

    READY("READY", "待执行"),
    RETRY_WAIT("RETRY_WAIT", "等待重试"),
    SUCCEEDED("SUCCEEDED", "赠送成功"),
    FAILED("FAILED", "自动处理终止");

    private final String code;
    private final String displayName;

    private static final Map<String, PointGiftOrderStatusDict> CODE_MAP =
            Arrays.stream(values()).collect(Collectors.toMap(PointGiftOrderStatusDict::getCode, value -> value));

    PointGiftOrderStatusDict(String code, String displayName) {
        this.code = code;
        this.displayName = displayName;
    }

    /** 获取状态编码。 */
    public String getCode() {
        return code;
    }

    /** 获取中文显示名称。 */
    public String getDisplayName() {
        return displayName;
    }

    /** 根据编码返回状态，不匹配时返回空。 */
    public static PointGiftOrderStatusDict fromCode(String code) {
        return CODE_MAP.get(code);
    }
}
