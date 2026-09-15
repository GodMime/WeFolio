package com.jxc.wefolio.dict;

/** 结构化文字区块类型。 */
public enum PortfolioTextBlockTypeDict {
    /** 支持的配置值。 */
    EYEBROW, TITLE, PARAGRAPH, LIST, HINT, SPACER;

    /** 返回持久化编码。 */
    public String getCode() { return name(); }

    /** 严格反查，非法值返回空。 */
    public static PortfolioTextBlockTypeDict fromCode(Object code) {
        if (!(code instanceof String text)) { return null; }
        for (PortfolioTextBlockTypeDict value : values()) {
            if (value.name().equals(text)) { return value; }
        }
        return null;
    }
}
