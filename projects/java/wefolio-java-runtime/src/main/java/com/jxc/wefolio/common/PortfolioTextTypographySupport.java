package com.jxc.wefolio.common;

import com.jxc.wefolio.constant.PortfolioTextTypographyConstants;
import com.jxc.wefolio.dict.PortfolioTextFontFamilyDict;

import java.math.BigDecimal;

/**
 * 作品集文字说明排版配置共享支持。
 */
public final class PortfolioTextTypographySupport {

    /** 字体配置键。 */
    public static final String FONT_FAMILY_CONFIG_KEY = "fontFamily";

    /** 字号配置键。 */
    public static final String FONT_SIZE_RPX_CONFIG_KEY = "fontSizeRpx";

    /**
     * 禁止实例化支持类。
     */
    private PortfolioTextTypographySupport() {
    }

    /**
     * 将原始值转换为严格匹配的受支持字体代码。
     *
     * @param value 原始字体配置
     * @return 受支持字体代码，类型错误、包含额外空白或未知值时返回 {@code null}
     */
    public static String asSupportedFontFamily(Object value) {
        if (!(value instanceof String fontFamily)
                || PortfolioTextFontFamilyDict.fromCode(fontFamily) == null) {
            return null;
        }
        return fontFamily;
    }

    /**
     * 将原始字号转换为精确整数。
     *
     * @param value 原始字号
     * @return 精确整数，类型错误、小数或溢出时返回 {@code null}
     */
    public static Integer asExactFontSizeRpx(Object value) {
        if (!(value instanceof Number number)) {
            return null;
        }
        try {
            return new BigDecimal(number.toString()).intValueExact();
        } catch (NumberFormatException | ArithmeticException exception) {
            return null;
        }
    }

    /**
     * 判断字号是否为允许范围内的整数。
     *
     * @param fontSizeRpx 字号
     * @return 是否有效
     */
    public static boolean isValidFontSizeRpx(Integer fontSizeRpx) {
        return fontSizeRpx != null
                && fontSizeRpx >= PortfolioTextTypographyConstants.FONT_SIZE_MIN_RPX
                && fontSizeRpx <= PortfolioTextTypographyConstants.FONT_SIZE_MAX_RPX;
    }
}
