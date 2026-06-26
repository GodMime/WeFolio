package com.jxc.wefolio.dict;

import java.util.Arrays;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 系统消息类型字典。
 */
public enum MessageTypeDict {

    POINT_LOW_BALANCE("POINT_LOW_BALANCE", "积分不足提醒"),
    TEAM_INVITATION("TEAM_INVITATION", "团队邀请"),
    TEAM_ROLE_CHANGED("TEAM_ROLE_CHANGED", "团队角色变更"),
    SYSTEM_NOTICE("SYSTEM_NOTICE", "系统公告");

    /** 类型编码 */
    private final String code;

    /** 展示名称 */
    private final String displayName;

    /** 编码映射 */
    private static final Map<String, MessageTypeDict> CODE_MAP =
            Arrays.stream(values()).collect(Collectors.toMap(MessageTypeDict::getCode, v -> v));

    MessageTypeDict(String code, String displayName) {
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
     * @param code 类型编码
     * @return 字典项，不存在时返回 null
     */
    public static MessageTypeDict fromCode(String code) {
        return CODE_MAP.get(code);
    }
}
