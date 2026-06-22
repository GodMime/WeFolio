package com.jxc.wefolio.dict;

import java.util.Arrays;
import java.util.Map;
import java.util.stream.Collectors;


/**
 * 作品集引用类型字典
 */
public enum ReferenceTypeDict {

    WORK("WORK", "作品"),
        MEMBER_PORTFOLIO("MEMBER_PORTFOLIO", "成员作品集"),
        USER_PROFILE("USER_PROFILE", "用户资料"),
        TEAM_PROFILE("TEAM_PROFILE", "团队资料"),
        SCHEDULE_COMPONENT("SCHEDULE_COMPONENT", "档期组件"),
        QR_CODE_ASSET("QR_CODE_ASSET", "二维码资源");

    private final String code;
    private final String displayName;

    private static final Map<String, ReferenceTypeDict> CODE_MAP =
        Arrays.stream(values()).collect(Collectors.toMap(ReferenceTypeDict::getCode, v -> v));

    ReferenceTypeDict(String code, String displayName) {
        this.code = code;
        this.displayName = displayName;
    }

    public String getCode() { return code; }
    public String getDisplayName() { return displayName; }

    public static ReferenceTypeDict fromCode(String code) {
        return CODE_MAP.get(code);
    }
}
