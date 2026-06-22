package com.jxc.wefolio.dict;

import java.util.Arrays;
import java.util.Map;
import java.util.stream.Collectors;


/**
 * 登录认证类型字典
 */
public enum AuthTypeDict {

    WECHAT_MINI_APP("WECHAT_MINI_APP", "微信小程序"),
        PHONE_OTP("PHONE_OTP", "手机验证码"),
        PHONE_PASSWORD("PHONE_PASSWORD", "手机密码");

    private final String code;
    private final String displayName;

    private static final Map<String, AuthTypeDict> CODE_MAP =
        Arrays.stream(values()).collect(Collectors.toMap(AuthTypeDict::getCode, v -> v));

    AuthTypeDict(String code, String displayName) {
        this.code = code;
        this.displayName = displayName;
    }

    public String getCode() { return code; }
    public String getDisplayName() { return displayName; }

    public static AuthTypeDict fromCode(String code) {
        return CODE_MAP.get(code);
    }
}
