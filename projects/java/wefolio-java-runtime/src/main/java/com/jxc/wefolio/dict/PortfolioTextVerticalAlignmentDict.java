package com.jxc.wefolio.dict;

/** 普通文字垂直对齐。 */
public enum PortfolioTextVerticalAlignmentDict {
    /** 支持的配置值。 */
    TOP, CENTER, BOTTOM;

    /** 返回持久化编码。 */
    public String getCode() { return name(); }

    /** 严格反查，非法值返回空。 */
    public static PortfolioTextVerticalAlignmentDict fromCode(Object code) {
        if (!(code instanceof String text)) { return null; }
        for (PortfolioTextVerticalAlignmentDict value : values()) {
            if (value.name().equals(text)) { return value; }
        }
        return null;
    }
}
