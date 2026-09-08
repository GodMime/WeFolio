package com.jxc.wefolio.common;

import com.jxc.wefolio.exception.BusinessException;
import com.jxc.wefolio.message.PortfolioTextMessage;

import java.util.Locale;
import java.util.regex.Pattern;

/** 个人及团队普通文字的颜色校验与历史展示兼容规则。 */
public final class PortfolioTextColorSupport {

    /** 普通文字颜色配置键。 */
    public static final String COLOR_CONFIG_KEY = "color";

    /** 由客户端根据主题及背景决定颜色的语义值。 */
    public static final String AUTO = "AUTO";

    /** 仅接受六位十六进制颜色，防止任意 CSS 注入。 */
    private static final Pattern HEX_COLOR_PATTERN = Pattern.compile("#[0-9a-fA-F]{6}");

    /** 纯规则工具类不允许实例化。 */
    private PortfolioTextColorSupport() { }

    /** 保存时兼容缺失或空值，其余颜色严格校验并统一十六进制大小写。 */
    public static String normalize(Object source) {
        if (source == null || AUTO.equals(source)) {
            return AUTO;
        }
        if (source instanceof String color && HEX_COLOR_PATTERN.matcher(color).matches()) {
            return color.toUpperCase(Locale.ROOT);
        }
        throw new BusinessException(PortfolioTextMessage.COLOR_INVALID);
    }

    /** 历史非法颜色安全回退自动，避免旧配置中被忽略的字段阻断正文展示。 */
    public static String forRender(Object source) {
        try {
            return normalize(source);
        } catch (BusinessException exception) {
            return AUTO;
        }
    }
}
