package com.jxc.wefolio.dict;

import java.util.Map;
import java.util.Optional;

/**
 * 积分规则状态字典 — ACTIVE 启用 / DISABLED 停用。
 */
public class PointRuleStatusDict {

    /** 启用 */
    public static final DictValue ACTIVE = new DictValue("ACTIVE", "启用");

    /** 停用 */
    public static final DictValue DISABLED = new DictValue("DISABLED", "停用");

    /** 状态编码索引 */
    private static final Map<String, DictValue> CODE_MAP = Map.of(
            ACTIVE.getCode(), ACTIVE,
            DISABLED.getCode(), DISABLED
    );

    private PointRuleStatusDict() {
    }

    /**
     * 根据编码获取字典值。
     *
     * @param code 状态编码
     * @return 字典值
     */
    public static Optional<DictValue> fromCode(String code) {
        if (code == null) {
            return Optional.empty();
        }
        return Optional.ofNullable(CODE_MAP.get(code));
    }

    /**
     * 字典值。
     *
     * @param code 编码
     * @param displayName 中文展示名称
     */
    public record DictValue(String code, String displayName) {

        public String getCode() {
            return code;
        }

        public String getDisplayName() {
            return displayName;
        }
    }
}
