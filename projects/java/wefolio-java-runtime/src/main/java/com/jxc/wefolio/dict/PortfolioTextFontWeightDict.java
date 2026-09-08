package com.jxc.wefolio.dict;

/** 结构化文字字重。 */
public enum PortfolioTextFontWeightDict {
    /** 支持的配置值。 */
    NORMAL, BOLD;

    /** 返回持久化编码。 */
    public String getCode() { return name(); }

    /** 严格反查，非法值返回空。 */
    public static PortfolioTextFontWeightDict fromCode(Object code) {
        if (!(code instanceof String text)) { return null; }
        for (PortfolioTextFontWeightDict value : values()) {
            if (value.name().equals(text)) { return value; }
        }
        return null;
    }
}
