package com.jxc.wefolio.dict;

import java.util.Arrays;
import java.util.Map;
import java.util.stream.Collectors;

/** 登录页标签字典，与小程序标签标识保持一致。 */
public enum LoginTabDict {

    /** 本地体验入口。 */
    EXPERIENCE("experience", "体验"),

    /** 维护者登录或注册入口。 */
    MAINTAINER("maintainer", "登录 / 注册");

    /** 小程序使用的标签标识。 */
    private final String code;

    /** 标签展示名称。 */
    private final String displayName;

    /** 根据标签标识查询的字典索引。 */
    private static final Map<String, LoginTabDict> CODE_MAP = Arrays.stream(values())
            .collect(Collectors.toMap(LoginTabDict::getCode, value -> value));

    /** 创建登录页标签，保存标识与展示名称。 */
    LoginTabDict(String code, String displayName) {
        this.code = code;
        this.displayName = displayName;
    }

    /** 获取小程序使用的标签标识。 */
    public String getCode() {
        return code;
    }

    /** 获取标签展示名称。 */
    public String getDisplayName() {
        return displayName;
    }

    /** 根据标签标识查找字典值；空值或未知标识返回 null。 */
    public static LoginTabDict fromCode(String code) {
        return CODE_MAP.get(code);
    }
}
