package com.jxc.wefolio.dict;

import java.util.Arrays;
import java.util.Map;
import java.util.stream.Collectors;


/**
 * 媒体类型字典
 */
public enum MediaTypeDict {

    IMAGE("IMAGE", "图片"),
    VIDEO("VIDEO", "视频"),
    ANIMATION("ANIMATION", "动图");

    private final String code;
    private final String displayName;

    private static final Map<String, MediaTypeDict> CODE_MAP =
        Arrays.stream(values()).collect(Collectors.toMap(MediaTypeDict::getCode, v -> v));

    MediaTypeDict(String code, String displayName) {
        this.code = code;
        this.displayName = displayName;
    }

    public String getCode() { return code; }
    public String getDisplayName() { return displayName; }

    public static MediaTypeDict fromCode(String code) {
        return CODE_MAP.get(code);
    }
}
