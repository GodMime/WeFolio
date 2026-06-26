package com.jxc.wefolio.dict;

import java.util.Arrays;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 系统消息分类字典。
 */
public enum MessageCategoryDict {

    POINT("POINT", "积分"),
    TEAM("TEAM", "团队"),
    SYSTEM("SYSTEM", "系统");

    /** 分类编码 */
    private final String code;

    /** 展示名称 */
    private final String displayName;

    /** 编码映射 */
    private static final Map<String, MessageCategoryDict> CODE_MAP =
            Arrays.stream(values()).collect(Collectors.toMap(MessageCategoryDict::getCode, v -> v));

    MessageCategoryDict(String code, String displayName) {
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
     * @param code 分类编码
     * @return 字典项，不存在时返回 null
     */
    public static MessageCategoryDict fromCode(String code) {
        return CODE_MAP.get(code);
    }
}
