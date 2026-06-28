package com.jxc.wefolio.dict;

import java.util.Arrays;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 系统消息动作类型字典。
 */
public enum MessageActionTypeDict {

    NONE("NONE", "无动作"),
    TEAM_INVITATION("TEAM_INVITATION", "团队邀请处理"),
    TEAM_MEMBER_CHANGE("TEAM_MEMBER_CHANGE", "团队成员信息变更处理"),
    POINT_RECHARGE("POINT_RECHARGE", "积分充值"),
    PAGE_NAVIGATION("PAGE_NAVIGATION", "页面跳转");

    /** 动作编码 */
    private final String code;

    /** 展示名称 */
    private final String displayName;

    /** 编码映射 */
    private static final Map<String, MessageActionTypeDict> CODE_MAP =
            Arrays.stream(values()).collect(Collectors.toMap(MessageActionTypeDict::getCode, v -> v));

    MessageActionTypeDict(String code, String displayName) {
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
     * @param code 动作编码
     * @return 字典项，不存在时返回 null
     */
    public static MessageActionTypeDict fromCode(String code) {
        return CODE_MAP.get(code);
    }
}
