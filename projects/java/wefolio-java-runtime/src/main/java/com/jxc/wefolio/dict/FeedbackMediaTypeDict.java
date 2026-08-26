package com.jxc.wefolio.dict;

import java.util.Arrays;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 意见反馈附件媒体类型字典。
 */
public enum FeedbackMediaTypeDict {

    /** 图片附件。 */
    IMAGE("IMAGE", "图片"),

    /** 视频附件。 */
    VIDEO("VIDEO", "视频");

    /** 类型编码。 */
    private final String code;

    /** 展示名称。 */
    private final String displayName;

    /** 按类型编码建立的字典索引。 */
    private static final Map<String, FeedbackMediaTypeDict> CODE_MAP =
            Arrays.stream(values()).collect(Collectors.toMap(FeedbackMediaTypeDict::getCode, value -> value));

    /**
     * 创建意见反馈附件媒体类型。
     *
     * @param code 类型编码
     * @param displayName 展示名称
     */
    FeedbackMediaTypeDict(String code, String displayName) {
        this.code = code;
        this.displayName = displayName;
    }

    /**
     * 获取类型编码。
     *
     * @return 类型编码
     */
    public String getCode() {
        return code;
    }

    /**
     * 获取展示名称。
     *
     * @return 展示名称
     */
    public String getDisplayName() {
        return displayName;
    }

    /**
     * 根据编码查找附件媒体类型。
     *
     * @param code 类型编码
     * @return 媒体类型字典，未命中时返回 null
     */
    public static FeedbackMediaTypeDict fromCode(String code) {
        return CODE_MAP.get(code);
    }
}
