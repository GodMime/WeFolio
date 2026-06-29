package com.jxc.wefolio.dict;

import java.util.Arrays;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 作品上传任务状态字典。
 */
public enum WorkUploadTaskStatusDict {

    /** 已创建直传票据，等待小程序上传到 COS */
    CREATED("CREATED", "待上传"),

    /** COS 对象已校验通过，等待或正在确认入库 */
    UPLOADED("UPLOADED", "已上传"),

    /** 已创建作品并完成积分扣减 */
    CONFIRMED("CONFIRMED", "已确认"),

    /** 上传或确认失败 */
    FAILED("FAILED", "失败"),

    /** 上传票据已过期 */
    EXPIRED("EXPIRED", "已过期");

    /** 状态编码 */
    private final String code;

    /** 展示名称 */
    private final String displayName;

    /** 编码索引 */
    private static final Map<String, WorkUploadTaskStatusDict> CODE_MAP =
            Arrays.stream(values()).collect(Collectors.toMap(WorkUploadTaskStatusDict::getCode, value -> value));

    WorkUploadTaskStatusDict(String code, String displayName) {
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
     * 根据编码查找状态。
     *
     * @param code 状态编码
     * @return 状态字典，未命中时返回 null
     */
    public static WorkUploadTaskStatusDict fromCode(String code) {
        return CODE_MAP.get(code);
    }
}
