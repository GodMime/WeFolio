package com.jxc.wefolio.constant;

/**
 * 作品集文字说明排版常量。
 */
public final class PortfolioTextTypographyConstants {

    /** 最小字号，单位 rpx。 */
    public static final int FONT_SIZE_MIN_RPX = 20;

    /** 最大字号，单位 rpx。 */
    public static final int FONT_SIZE_MAX_RPX = 48;

    /** 新建文字说明组件默认字号，单位 rpx。 */
    public static final int NEW_COMPONENT_FONT_SIZE_RPX = 28;

    /** 旧个人文字说明兼容字号，单位 rpx。 */
    public static final int LEGACY_PERSONAL_FONT_SIZE_RPX = 26;

    /**
     * 旧团队文字说明兼容字号，单位 rpx。
     * <p>
     * 旧组件字号来自继承样式，32rpx 是对现有视觉效果的近似值。
     */
    public static final int LEGACY_TEAM_FONT_SIZE_RPX = 32;

    /**
     * 禁止实例化常量类。
     */
    private PortfolioTextTypographyConstants() {
    }
}
