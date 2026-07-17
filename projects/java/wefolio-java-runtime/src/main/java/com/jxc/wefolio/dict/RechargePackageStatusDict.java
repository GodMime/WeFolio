package com.jxc.wefolio.dict;

import java.util.Arrays;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 充值套餐状态字典。
 */
public enum RechargePackageStatusDict {

    /** 启用。 */
    ACTIVE("ACTIVE", "启用"),

    /** 停用。 */
    DISABLED("DISABLED", "停用");

    /** 状态编码。 */
    private final String code;

    /** 展示名称。 */
    private final String displayName;

    /** 状态编码映射。 */
    private static final Map<String, RechargePackageStatusDict> CODE_MAP =
            Arrays.stream(values()).collect(Collectors.toMap(RechargePackageStatusDict::getCode, value -> value));

    RechargePackageStatusDict(String code, String displayName) {
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
     * 根据状态编码获取字典值。
     *
     * @param code 状态编码
     * @return 对应字典值，不存在时返回 {@code null}
     */
    public static RechargePackageStatusDict fromCode(String code) {
        return CODE_MAP.get(code);
    }
}
