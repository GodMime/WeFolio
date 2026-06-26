package com.jxc.wefolio.dict;

import java.util.Arrays;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 系统消息已读状态字典。
 */
public enum MessageReadStatusDict {

    UNREAD("UNREAD", "未读"),
    READ("READ", "已读");

    /** 状态编码 */
    private final String code;

    /** 展示名称 */
    private final String displayName;

    /** 编码映射 */
    private static final Map<String, MessageReadStatusDict> CODE_MAP =
            Arrays.stream(values()).collect(Collectors.toMap(MessageReadStatusDict::getCode, v -> v));

    MessageReadStatusDict(String code, String displayName) {
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
     * 按编码读取字典项。
     *
     * @param code 状态编码
     * @return 字典项，不存在时返回 null
     */
    public static MessageReadStatusDict fromCode(String code) {
        return CODE_MAP.get(code);
    }
}
