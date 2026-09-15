package com.jxc.wefolio.service;

import com.jxc.wefolio.dict.PortfolioProfileLayoutDict;
import com.jxc.wefolio.exception.BusinessException;
import com.jxc.wefolio.message.PortfolioMessage;
import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

/** 个人资料卡布局、边框和留白规则，草稿、发布与访客渲染使用同一套兼容默认值。 */
public final class PortfolioProfileConfigSupport {
    /** 资料卡布局配置键。 */
    public static final String PROFILE_LAYOUT = "profileLayout";
    /** 资料卡边框开关配置键。 */
    public static final String PROFILE_BORDER = "profileBorder";
    /** 资料卡边框宽度配置键，单位 rpx。 */
    public static final String PROFILE_BORDER_WIDTH_RPX = "profileBorderWidthRpx";
    /** 资料卡边框颜色配置键。 */
    public static final String PROFILE_BORDER_COLOR = "profileBorderColor";
    /** 资料卡左右外侧留白配置键，单位 rpx。 */
    public static final String PROFILE_HORIZONTAL_MARGIN_RPX = "profileHorizontalMarginRpx";
    /** 资料卡上下外侧留白配置键，单位 rpx。 */
    public static final String PROFILE_VERTICAL_MARGIN_RPX = "profileVerticalMarginRpx";
    /** 自动主题色。 */
    private static final String AUTO = "AUTO";
    /** 自定义颜色仅接受六位十六进制值。 */
    private static final Pattern COLOR_PATTERN = Pattern.compile("#[0-9a-fA-F]{6}");
    /** 默认与最大边框宽度，单位 rpx。 */
    private static final int DEFAULT_BORDER_WIDTH_RPX = 1, MAX_BORDER_WIDTH_RPX = 12;
    /** 兼容原有边框卡片的左右与上下外侧留白，单位 rpx。 */
    private static final int DEFAULT_HORIZONTAL_MARGIN_RPX = 32, DEFAULT_VERTICAL_MARGIN_RPX = 0;
    /** 外侧留白上限，单位 rpx。 */
    private static final int MAX_MARGIN_RPX = 96;

    /** 纯规则工具类不允许实例化。 */
    private PortfolioProfileConfigSupport() { }

    /** 保留既有资料和展示字段，规范化卡片外观；显式空值和非法类型均拒绝。 */
    public static Map<String, Object> normalize(Map<String, Object> source) {
        Map<String, Object> result = new LinkedHashMap<>(source == null ? Map.of() : source);
        Object layout = result.getOrDefault(PROFILE_LAYOUT, PortfolioProfileLayoutDict.VERTICAL.getCode());
        if (PortfolioProfileLayoutDict.fromCode(layout) == null) {
            throw invalid();
        }
        result.put(PROFILE_LAYOUT, layout);
        Object border = result.getOrDefault(PROFILE_BORDER, false);
        if (!(border instanceof Boolean)) { throw invalid(); }
        result.put(PROFILE_BORDER, border);
        result.put(PROFILE_BORDER_WIDTH_RPX,
                integer(result.getOrDefault(PROFILE_BORDER_WIDTH_RPX, DEFAULT_BORDER_WIDTH_RPX),
                        DEFAULT_BORDER_WIDTH_RPX, MAX_BORDER_WIDTH_RPX));
        Object color = result.getOrDefault(PROFILE_BORDER_COLOR, AUTO);
        if (!(color instanceof String text) || (!AUTO.equals(text) && !COLOR_PATTERN.matcher(text).matches())) {
            throw invalid();
        }
        // 关闭边框时仍保留作者设置，再次开启时无需重新配置。
        result.put(PROFILE_BORDER_COLOR, text.toUpperCase(Locale.ROOT));
        result.put(PROFILE_HORIZONTAL_MARGIN_RPX,
                integer(result.getOrDefault(PROFILE_HORIZONTAL_MARGIN_RPX, DEFAULT_HORIZONTAL_MARGIN_RPX), 0, MAX_MARGIN_RPX));
        result.put(PROFILE_VERTICAL_MARGIN_RPX,
                integer(result.getOrDefault(PROFILE_VERTICAL_MARGIN_RPX, DEFAULT_VERTICAL_MARGIN_RPX), 0, MAX_MARGIN_RPX));
        return result;
    }

    /** 精确解析指定范围内的整数，边框宽度与外侧留白均拒绝字符串、小数和越界值。 */
    private static int integer(Object raw, int min, int max) {
        if (!(raw instanceof Number number)) { throw invalid(); }
        try {
            int value = new BigDecimal(number.toString()).intValueExact();
            if (value < min || value > max) { throw invalid(); }
            return value;
        } catch (NumberFormatException | ArithmeticException exception) { throw invalid(); }
    }

    /** 使用既有展示配置错误契约返回受控异常。 */
    private static BusinessException invalid() {
        return new BusinessException(PortfolioMessage.COMPONENT_DISPLAY_OPTIONS_INVALID);
    }
}
