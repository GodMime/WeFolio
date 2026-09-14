package com.jxc.wefolio.common;

import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/** 分割线颜色的兼容校验、十六进制规范化与旧编辑器保存保护。 */
public final class PortfolioDividerColorSupport {

    /** 分割线颜色配置键。 */
    public static final String COLOR_CONFIG_KEY = "color";

    /** 个人编辑器首次支持自定义分割线颜色的版本。 */
    public static final int PERSONAL_HEX_COLOR_REVISION = 8;

    /** 团队编辑器首次支持自定义分割线颜色的版本。 */
    public static final int TEAM_HEX_COLOR_REVISION = 7;

    /** 历史枚举颜色继续原样保存，保留旧客户端的渲染语义。 */
    private static final Set<String> LEGACY_COLORS = Set.of("BLACK", "WHITE", "GRAY", "TRANSPARENT");

    /** 只接受六位十六进制颜色，避免引入任意样式表达式。 */
    private static final Pattern HEX_COLOR_PATTERN = Pattern.compile("#[0-9a-fA-F]{6}");

    /** 纯规则工具类不允许实例化。 */
    private PortfolioDividerColorSupport() { }

    /** 旧枚举和六位十六进制颜色均为有效颜色。 */
    public static boolean isSupported(String color) {
        return color != null && (LEGACY_COLORS.contains(color) || isHexColor(color));
    }

    /** 只统一新增十六进制颜色的大小写，不改写历史枚举。 */
    public static String normalize(String color) {
        return isHexColor(color) ? color.toUpperCase(Locale.ROOT) : color;
    }

    /** 只保护旧编辑器无法理解的已有十六进制颜色，允许高度与旧枚举继续编辑。 */
    public static void protectHexColor(Map<String, Object> target, Map<String, Object> existing,
            int incomingRevision, int introducedAtRevision) {
        if (target == null || existing == null || incomingRevision >= introducedAtRevision) {
            return;
        }
        Object savedColor = existing.get(COLOR_CONFIG_KEY);
        if (savedColor instanceof String color && isHexColor(color)) {
            target.put(COLOR_CONFIG_KEY, color);
        }
    }

    /** 判断颜色是否为新增的六位十六进制形式。 */
    private static boolean isHexColor(String color) {
        return color != null && HEX_COLOR_PATTERN.matcher(color).matches();
    }
}
