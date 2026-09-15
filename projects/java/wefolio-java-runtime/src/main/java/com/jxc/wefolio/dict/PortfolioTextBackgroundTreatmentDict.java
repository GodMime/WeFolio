package com.jxc.wefolio.dict;

/** 文字背景处理方式。 */
public enum PortfolioTextBackgroundTreatmentDict {
    /** 支持的配置值。 */
    ORIGINAL, DARK_MASK, GRADIENT;

    /** 返回持久化编码。 */
    public String getCode() { return name(); }

    /** 严格反查，非法值返回空。 */
    public static PortfolioTextBackgroundTreatmentDict fromCode(Object code) {
        if (!(code instanceof String text)) { return null; }
        for (PortfolioTextBackgroundTreatmentDict value : values()) {
            if (value.name().equals(text)) { return value; }
        }
        return null;
    }
}
