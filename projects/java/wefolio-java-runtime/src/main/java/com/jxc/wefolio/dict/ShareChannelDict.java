package com.jxc.wefolio.dict;

import java.util.Arrays;
import java.util.Map;
import java.util.stream.Collectors;


/**
 * 分享渠道字典
 */
public enum ShareChannelDict {

    WECHAT_CARD("WECHAT_CARD", "微信卡片"),
        QR_CODE("QR_CODE", "二维码"),
        COPIED_PATH("COPIED_PATH", "复制链接");

    private final String code;
    private final String displayName;

    private static final Map<String, ShareChannelDict> CODE_MAP =
        Arrays.stream(values()).collect(Collectors.toMap(ShareChannelDict::getCode, v -> v));

    ShareChannelDict(String code, String displayName) {
        this.code = code;
        this.displayName = displayName;
    }

    public String getCode() { return code; }
    public String getDisplayName() { return displayName; }

    public static ShareChannelDict fromCode(String code) {
        return CODE_MAP.get(code);
    }
}
