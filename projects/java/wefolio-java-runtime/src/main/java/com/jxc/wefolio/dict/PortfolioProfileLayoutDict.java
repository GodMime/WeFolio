package com.jxc.wefolio.dict;

/** 个人资料卡布局：纵向排列或头像与资料横向排列。 */
public enum PortfolioProfileLayoutDict {
    /** 纵向布局，兼容既有资料卡。 */
    VERTICAL,
    /** 横向布局，头像位于资料左侧。 */
    HORIZONTAL;

    /** 返回持久化编码。 */
    public String getCode() { return name(); }

    /** 严格反查布局编码，非法值返回空。 */
    public static PortfolioProfileLayoutDict fromCode(Object code) {
        for (PortfolioProfileLayoutDict value : values()) {
            if (value.name().equals(code)) { return value; }
        }
        return null;
    }
}
