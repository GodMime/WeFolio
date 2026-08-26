package com.jxc.wefolio.dict;

import java.util.Arrays;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 意见反馈附件上传任务状态字典。
 */
public enum FeedbackUploadTaskStatusDict {

    /** 等待客户端完成上传并确认。 */
    PENDING("PENDING", "待上传"),

    /** 上传对象已确认并关联反馈。 */
    CONFIRMED("CONFIRMED", "已确认"),

    /** 上传任务已过期。 */
    EXPIRED("EXPIRED", "已过期");

    /** 状态编码。 */
    private final String code;

    /** 展示名称。 */
    private final String displayName;

    /** 按状态编码建立的字典索引。 */
    private static final Map<String, FeedbackUploadTaskStatusDict> CODE_MAP =
            Arrays.stream(values()).collect(Collectors.toMap(FeedbackUploadTaskStatusDict::getCode, value -> value));

    /**
     * 创建意见反馈附件上传任务状态。
     *
     * @param code 状态编码
     * @param displayName 展示名称
     */
    FeedbackUploadTaskStatusDict(String code, String displayName) {
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
     * 根据编码查找上传任务状态。
     *
     * @param code 状态编码
     * @return 上传任务状态字典，未命中时返回 null
     */
    public static FeedbackUploadTaskStatusDict fromCode(String code) {
        return CODE_MAP.get(code);
    }
}
