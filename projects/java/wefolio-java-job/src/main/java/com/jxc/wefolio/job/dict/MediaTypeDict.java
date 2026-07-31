package com.jxc.wefolio.job.dict;

/**
 * 媒体类型字典。
 */
public enum MediaTypeDict {

    IMAGE("IMAGE"),
    VIDEO("VIDEO"),
    ANIMATION("ANIMATION");

    private final String code;

    MediaTypeDict(String code) {
        this.code = code;
    }

    public String getCode() {
        return code;
    }
}
