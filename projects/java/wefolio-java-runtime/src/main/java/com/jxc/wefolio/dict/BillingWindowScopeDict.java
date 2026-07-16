package com.jxc.wefolio.dict;

import java.util.Map;
import java.util.Optional;

/**
 * 访客扣费窗口作用域字典。
 */
public class BillingWindowScopeDict {

    /** 作品集作用域 */
    public static final DictValue PORTFOLIO = new DictValue("PORTFOLIO", "作品集");

    /** 作品作用域 */
    public static final DictValue WORK = new DictValue("WORK", "作品");

    /** 编码索引 */
    private static final Map<String, DictValue> CODE_MAP = Map.of(
            PORTFOLIO.getCode(), PORTFOLIO,
            WORK.getCode(), WORK
    );

    /** 积分场景对应作用域 */
    private static final Map<String, DictValue> SCENE_SCOPE_MAP = Map.of(
            PointSceneCodeDict.VISIT_PERSONAL_PORTFOLIO.getCode(), PORTFOLIO,
            PointSceneCodeDict.VIEW_PORTFOLIO_IMAGES.getCode(), WORK,
            PointSceneCodeDict.VIEW_PORTFOLIO_VIDEO.getCode(), WORK
    );

    private BillingWindowScopeDict() {
    }

    /**
     * 根据编码获取作用域。
     *
     * @param code 作用域编码
     * @return 作用域字典值
     */
    public static Optional<DictValue> fromCode(String code) {
        if (code == null) {
            return Optional.empty();
        }
        return Optional.ofNullable(CODE_MAP.get(code));
    }

    /**
     * 根据积分场景获取要求的作用域。
     *
     * @param sceneCode 积分场景编码
     * @return 作用域字典值
     */
    public static Optional<DictValue> fromSceneCode(String sceneCode) {
        if (sceneCode == null) {
            return Optional.empty();
        }
        return Optional.ofNullable(SCENE_SCOPE_MAP.get(sceneCode));
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
