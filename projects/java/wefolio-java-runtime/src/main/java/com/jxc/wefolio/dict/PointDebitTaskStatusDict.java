package com.jxc.wefolio.dict;

import java.util.Arrays;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 微信代币扣币任务状态字典。
 */
public enum PointDebitTaskStatusDict {

    WAITING("WAITING", "等待执行"),
    RUNNING("RUNNING", "执行中"),
    WAITING_SESSION("WAITING_SESSION", "等待维护者会话"),
    RETRY_WAIT("RETRY_WAIT", "等待重试"),
    SUCCEEDED("SUCCEEDED", "全部结算成功"),
    PARTIAL("PARTIAL", "部分结算成功"),
    NO_BALANCE("NO_BALANCE", "微信余额为零"),
    FAILED("FAILED", "自动处理终止");

    private final String code;
    private final String displayName;

    private static final Map<String, PointDebitTaskStatusDict> CODE_MAP =
            Arrays.stream(values()).collect(Collectors.toMap(PointDebitTaskStatusDict::getCode, value -> value));

    PointDebitTaskStatusDict(String code, String displayName) {
        this.code = code;
        this.displayName = displayName;
    }

    /** 获取状态编码。 */
    public String getCode() {
        return code;
    }

    /** 获取中文显示名称。 */
    public String getDisplayName() {
        return displayName;
    }

    /** 根据编码返回状态，不匹配时返回空。 */
    public static PointDebitTaskStatusDict fromCode(String code) {
        return CODE_MAP.get(code);
    }
}
