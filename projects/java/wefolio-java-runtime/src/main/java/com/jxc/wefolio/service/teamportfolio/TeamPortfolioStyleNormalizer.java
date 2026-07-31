package com.jxc.wefolio.service.teamportfolio;

import com.jxc.wefolio.dto.teamportfolio.TeamPortfolioConfigDto;

import java.util.regex.Pattern;

/**
 * 团队作品集样式规范化工具 — 提供背景色校验与明暗主题推导。
 * <p>
 * 本工具类将样式相关的规范化逻辑从 {@link TeamPortfolioConfigValidator} 和
 * {@link TeamPortfolioRenderService} 收敛到单一入口，避免多处理解不一致。
 */
public final class TeamPortfolioStyleNormalizer {

    /** 默认页面背景色 */
    public static final String DEFAULT_BACKGROUND_COLOR = TeamPortfolioConfigDto.DEFAULT_BACKGROUND_COLOR;

    /** 浅色主题标识 */
    static final String THEME_MODE_LIGHT = "light";

    /** 深色主题标识 */
    static final String THEME_MODE_DARK = "dark";

    /** 页面背景色格式，大写规范化前允许大小写混合输入 */
    private static final Pattern BACKGROUND_COLOR_PATTERN = Pattern.compile("^#[0-9A-Fa-f]{6}$");

    /** YIQ 明暗模式阈值，低于此值视为深色 */
    private static final int YIQ_THEME_THRESHOLD = 128;

    /**
     * 工具类不允许实例化。
     */
    private TeamPortfolioStyleNormalizer() {
    }

    /**
     * 规范化背景色，非法或缺失值回退默认白色。
     *
     * @param backgroundColor 原始背景色，可能为 {@code null}
     * @return 规范化大写 HEX 色值
     */
    public static String normalizeBackgroundColor(String backgroundColor) {
        if (backgroundColor == null || !BACKGROUND_COLOR_PATTERN.matcher(backgroundColor).matches()) {
            return DEFAULT_BACKGROUND_COLOR;
        }
        return backgroundColor.toUpperCase();
    }

    /**
     * 根据 YIQ 亮度公式推导当前背景色的明暗主题。
     *
     * @param backgroundColor 已规范化的背景色
     * @return {@value #THEME_MODE_LIGHT} 或 {@value #THEME_MODE_DARK}
     */
    public static String resolveThemeMode(String backgroundColor) {
        int red = Integer.parseInt(backgroundColor.substring(1, 3), 16);
        int green = Integer.parseInt(backgroundColor.substring(3, 5), 16);
        int blue = Integer.parseInt(backgroundColor.substring(5, 7), 16);
        int yiq = (red * 299 + green * 587 + blue * 114) / 1000;
        return yiq < YIQ_THEME_THRESHOLD ? THEME_MODE_DARK : THEME_MODE_LIGHT;
    }
}
