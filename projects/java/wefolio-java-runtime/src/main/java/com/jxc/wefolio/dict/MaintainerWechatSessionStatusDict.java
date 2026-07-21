package com.jxc.wefolio.dict;

import java.util.Arrays;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 维护者微信会话状态字典。
 */
public enum MaintainerWechatSessionStatusDict {

    AVAILABLE("AVAILABLE", "可用"),
    INVALID("INVALID", "已失效");

    private final String code;
    private final String displayName;

    private static final Map<String, MaintainerWechatSessionStatusDict> CODE_MAP =
            Arrays.stream(values()).collect(Collectors.toMap(MaintainerWechatSessionStatusDict::getCode, value -> value));

    MaintainerWechatSessionStatusDict(String code, String displayName) {
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
    public static MaintainerWechatSessionStatusDict fromCode(String code) {
        return CODE_MAP.get(code);
    }
}
