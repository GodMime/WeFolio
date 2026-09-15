package com.jxc.wefolio.dict;

/** 文字水平对齐。 */
public enum PortfolioTextAlignmentDict {
    /** 支持的配置值。 */
    LEFT, CENTER, RIGHT;

    /** 返回持久化编码。 */
    public String getCode() { return name(); }

    /** 严格反查，非法值返回空。 */
    public static PortfolioTextAlignmentDict fromCode(Object code) {
        if (!(code instanceof String text)) { return null; }
        for (PortfolioTextAlignmentDict value : values()) {
            if (value.name().equals(text)) { return value; }
        }
        return null;
    }
}
