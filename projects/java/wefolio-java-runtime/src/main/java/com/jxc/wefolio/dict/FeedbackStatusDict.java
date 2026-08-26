package com.jxc.wefolio.dict;

import java.util.Arrays;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 意见反馈处理状态字典。
 */
public enum FeedbackStatusDict {

    /** 团队正在处理反馈。 */
    PROCESSING("PROCESSING", "处理中"),

    /** 等待用户再次提交反馈信息。 */
    WAITING_FOLLOW_UP("WAITING_FOLLOW_UP", "待再次反馈"),

    /** 反馈已处理完成。 */
    RESOLVED("RESOLVED", "已处理");

    /** 状态编码。 */
    private final String code;

    /** 展示名称。 */
    private final String displayName;

    /** 按状态编码建立的字典索引。 */
    private static final Map<String, FeedbackStatusDict> CODE_MAP =
            Arrays.stream(values()).collect(Collectors.toMap(FeedbackStatusDict::getCode, value -> value));

    /**
     * 创建意见反馈处理状态。
     *
     * @param code 状态编码
     * @param displayName 展示名称
     */
    FeedbackStatusDict(String code, String displayName) {
        this.code = code;
        this.displayName = displayName;
    }

    /**
     * 获取状态编码。
     *
     * @return 状态编码
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
     * 根据编码查找反馈处理状态。
     *
     * @param code 状态编码
     * @return 状态字典，未命中时返回 null
     */
    public static FeedbackStatusDict fromCode(String code) {
        return CODE_MAP.get(code);
    }
}
