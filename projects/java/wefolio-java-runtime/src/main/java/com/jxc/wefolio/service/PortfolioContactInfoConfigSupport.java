package com.jxc.wefolio.service;

import com.jxc.wefolio.exception.BusinessException;
import com.jxc.wefolio.message.MineProfileMessage;
import com.jxc.wefolio.message.PortfolioMessage;
import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

/** 个人资料联系文本校验，以及两类作品集联系快照的文本和边框规则。 */
public final class PortfolioContactInfoConfigSupport {
    /** 联系手机配置键。 */
    public static final String PHONE = "contactPhone";
    /** 联系微信配置键。 */
    public static final String WECHAT = "contactWechat";
    /** 联系信息边框开关配置键。 */
    public static final String CONTACT_BORDER = "contactBorder";
    /** 联系信息边框线宽配置键，单位 rpx。 */
    public static final String CONTACT_BORDER_WIDTH_RPX = "contactBorderWidthRpx";
    /** 联系信息边框颜色配置键。 */
    public static final String CONTACT_BORDER_COLOR = "contactBorderColor";
    /** 联系信息边框左右外侧留白配置键，单位 rpx。 */
    public static final String HORIZONTAL_MARGIN_RPX = "horizontalMarginRpx";
    /** 联系信息边框上下外侧留白配置键，单位 rpx。 */
    public static final String VERTICAL_MARGIN_RPX = "verticalMarginRpx";
    /** 新外观字段清单，供团队配置深拷贝保留显式空值以进行严格校验。 */
    public static final List<String> APPEARANCE_FIELDS = List.of(CONTACT_BORDER, CONTACT_BORDER_WIDTH_RPX,
            CONTACT_BORDER_COLOR, HORIZONTAL_MARGIN_RPX, VERTICAL_MARGIN_RPX);
    /** 自动跟随页面主题色。 */
    private static final String AUTO = "AUTO";
    /** 自定义颜色仅接受六位十六进制值。 */
    private static final Pattern COLOR_PATTERN = Pattern.compile("#[0-9a-fA-F]{6}");
    /** 默认与最大边框线宽，单位 rpx。 */
    private static final int DEFAULT_BORDER_WIDTH_RPX = 1, MAX_BORDER_WIDTH_RPX = 12;
    /** 历史联系信息没有额外外侧留白，默认保持零值。 */
    private static final int DEFAULT_MARGIN_RPX = 0, MAX_MARGIN_RPX = 96;
    /** 手机最大 Unicode 码点数。 */
    private static final int PHONE_LIMIT = 32;
    /** 微信最大 Unicode 码点数。 */
    private static final int WECHAT_LIMIT = 64;
    /** 工具类不实例化。 */
    private PortfolioContactInfoConfigSupport() { }
    /** 校验手机，保留明确空白输入的清空语义。 */
    public static String phone(String value) { return text(value, PHONE_LIMIT, MineProfileMessage.CONTACT_PHONE_LENGTH_INVALID); }
    /** 校验微信。 */
    public static String wechat(String value) { return text(value, WECHAT_LIMIT, MineProfileMessage.CONTACT_WECHAT_LENGTH_INVALID); }
    /** 联系文本与外观字段白名单规范化，草稿允许空内容，历史数据默认不显示边框。 */
    public static Map<String, Object> normalize(Map<String, Object> source) {
        Map<String, Object> safe = source == null ? Map.of() : source;
        Map<String, Object> result = new LinkedHashMap<>();
        result.put(PHONE, phone(value(safe.get(PHONE))));
        result.put(WECHAT, wechat(value(safe.get(WECHAT))));
        Object border = safe.getOrDefault(CONTACT_BORDER, false);
        if (!(border instanceof Boolean)) { throw invalidAppearance(); }
        result.put(CONTACT_BORDER, border);
        result.put(CONTACT_BORDER_WIDTH_RPX,
                integer(safe.getOrDefault(CONTACT_BORDER_WIDTH_RPX, DEFAULT_BORDER_WIDTH_RPX),
                        DEFAULT_BORDER_WIDTH_RPX, MAX_BORDER_WIDTH_RPX));
        Object color = safe.getOrDefault(CONTACT_BORDER_COLOR, AUTO);
        if (!(color instanceof String text) || (!AUTO.equals(text) && !COLOR_PATTERN.matcher(text).matches())) {
            throw invalidAppearance();
        }
        // 边框关闭时仍保存作者设置，重新开启时恢复线宽、颜色与外侧留白。
        result.put(CONTACT_BORDER_COLOR, text.toUpperCase(Locale.ROOT));
        result.put(HORIZONTAL_MARGIN_RPX,
                integer(safe.getOrDefault(HORIZONTAL_MARGIN_RPX, DEFAULT_MARGIN_RPX), DEFAULT_MARGIN_RPX, MAX_MARGIN_RPX));
        result.put(VERTICAL_MARGIN_RPX,
                integer(safe.getOrDefault(VERTICAL_MARGIN_RPX, DEFAULT_MARGIN_RPX), DEFAULT_MARGIN_RPX, MAX_MARGIN_RPX));
        return result;
    }
    /** 严格解析范围内的整数，拒绝空值、字符串、小数、非有限数和溢出值。 */
    private static int integer(Object raw, int min, int max) {
        if (!(raw instanceof Number number)) { throw invalidAppearance(); }
        try {
            int parsed = new BigDecimal(number.toString()).intValueExact();
            if (parsed < min || parsed > max) { throw invalidAppearance(); }
            return parsed;
        } catch (NumberFormatException | ArithmeticException exception) { throw invalidAppearance(); }
    }
    /** 外观非法值沿用组件展示配置的受控错误契约。 */
    private static BusinessException invalidAppearance() {
        return new BusinessException(PortfolioMessage.COMPONENT_DISPLAY_OPTIONS_INVALID);
    }
    /** 启用发布至少有一项联系文本。 */
    public static void validateForPublish(Map<String, Object> source) {
        Map<String, Object> normalized = normalize(source);
        if (((String) normalized.get(PHONE)).isBlank() && ((String) normalized.get(WECHAT)).isBlank()) {
            throw new BusinessException(PortfolioMessage.CONTACT_INFO_REQUIRED);
        }
    }
    /** 只接受字符串，拒绝对象等隐式文本转换。 */
    private static String value(Object value) {
        if (value != null && !(value instanceof String)) { throw new BusinessException(MineProfileMessage.CONTACT_FORMAT_INVALID); }
        return value == null ? "" : (String) value;
    }
    /** 拒绝原始输入中的换行和控制符，再去首尾空白并以码点计数。 */
    private static String text(String value, int limit, String lengthMessage) {
        String raw = value == null ? "" : value;
        if (raw.codePoints().anyMatch(code -> Character.isISOControl(code)
                || Character.getType(code) == Character.LINE_SEPARATOR
                || Character.getType(code) == Character.PARAGRAPH_SEPARATOR)) {
            throw new BusinessException(MineProfileMessage.CONTACT_FORMAT_INVALID);
        }
        String normalized = raw.strip();
        if (normalized.codePointCount(0, normalized.length()) > limit) { throw new BusinessException(lengthMessage); }
        return normalized;
    }
}
