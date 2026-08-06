package com.jxc.wefolio.dict;

import java.util.Arrays;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 作品集文字说明字体字典。
 */
public enum PortfolioTextFontFamilyDict {

    /** 使用设备系统默认字体。 */
    SYSTEM("SYSTEM", "系统默认"),

    /** 使用微信内置字体。 */
    WECHAT_SANS_SS("WECHAT_SANS_SS", "微信字体");

    /** 稳定配置值。 */
    private final String code;

    /** 中文显示名称。 */
    private final String displayName;

    /** 按稳定配置值查询的字典。 */
    private static final Map<String, PortfolioTextFontFamilyDict> CODE_MAP =
            Arrays.stream(values()).collect(Collectors.toMap(
                    PortfolioTextFontFamilyDict::getCode,
                    value -> value
            ));

    /**
     * 构造字体字典项。
     *
     * @param code 稳定配置值
     * @param displayName 中文显示名称
     */
    PortfolioTextFontFamilyDict(String code, String displayName) {
        this.code = code;
        this.displayName = displayName;
    }

    /**
     * 获取稳定配置值。
     *
     * @return 稳定配置值
     */
    public String getCode() {
        return code;
    }

    /**
     * 获取中文显示名称。
     *
     * @return 中文显示名称
     */
    public String getDisplayName() {
        return displayName;
    }

    /**
     * 按稳定配置值查询字体。
     *
     * @param code 稳定配置值
     * @return 字体字典项，未知值返回 {@code null}
     */
    public static PortfolioTextFontFamilyDict fromCode(String code) {
        return CODE_MAP.get(code);
    }
}
