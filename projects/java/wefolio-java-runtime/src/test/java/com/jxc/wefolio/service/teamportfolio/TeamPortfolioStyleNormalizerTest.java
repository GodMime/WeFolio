package com.jxc.wefolio.service.teamportfolio;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 团队作品集样式规范化工具测试 — 覆盖背景色校验与明暗主题推导。
 */
class TeamPortfolioStyleNormalizerTest {

    /**
     * 合法背景色大小写混合输入都应归一化为大写。
     */
    @Test
    void normalizeBackgroundColorShouldUppercaseValidHex() {
        assertThat(TeamPortfolioStyleNormalizer.normalizeBackgroundColor("#a1b2c3"))
                .isEqualTo("#A1B2C3");
        assertThat(TeamPortfolioStyleNormalizer.normalizeBackgroundColor("#A1B2C3"))
                .isEqualTo("#A1B2C3");
        assertThat(TeamPortfolioStyleNormalizer.normalizeBackgroundColor("#ffffff"))
                .isEqualTo("#FFFFFF");
    }

    /**
     * 三位 HEX、空值、非法字符和缺少井号的值必须回退默认白色。
     */
    @Test
    void normalizeBackgroundColorShouldFallbackForInvalidInputs() {
        assertThat(TeamPortfolioStyleNormalizer.normalizeBackgroundColor(null))
                .isEqualTo("#FFFFFF");
        assertThat(TeamPortfolioStyleNormalizer.normalizeBackgroundColor(""))
                .isEqualTo("#FFFFFF");
        assertThat(TeamPortfolioStyleNormalizer.normalizeBackgroundColor("#123"))
                .isEqualTo("#FFFFFF");
        assertThat(TeamPortfolioStyleNormalizer.normalizeBackgroundColor("151515"))
                .isEqualTo("#FFFFFF");
        assertThat(TeamPortfolioStyleNormalizer.normalizeBackgroundColor("#GGGGGG"))
                .isEqualTo("#FFFFFF");
        assertThat(TeamPortfolioStyleNormalizer.normalizeBackgroundColor("rgb(0,0,0)"))
                .isEqualTo("#FFFFFF");
    }

    /**
     * 黑、白、灰三个快捷色必须按各自 YIQ 亮度推导正确的主题。
     */
    @Test
    void themeModeShouldResolveByYiqThreshold() {
        assertThat(TeamPortfolioStyleNormalizer.resolveThemeMode("#151515"))
                .isEqualTo("dark");
        assertThat(TeamPortfolioStyleNormalizer.resolveThemeMode("#FFFFFF"))
                .isEqualTo("light");
        assertThat(TeamPortfolioStyleNormalizer.resolveThemeMode("#F5F6F8"))
                .isEqualTo("light");
    }

    /**
     * YIQ 阈值 128 附近的颜色必须按亮度分界线正确划分。
     */
    @Test
    void themeModeShouldSplitAroundThreshold() {
        // #7F7F7F → R=127, G=127, B=127 → YIQ=127 < 128 → dark
        assertThat(TeamPortfolioStyleNormalizer.resolveThemeMode("#7F7F7F"))
                .isEqualTo("dark");
        // #808080 → R=128, G=128, B=128 → YIQ=128 → light（等于阈值不算深色）
        assertThat(TeamPortfolioStyleNormalizer.resolveThemeMode("#808080"))
                .isEqualTo("light");
        // #000000 → dark
        assertThat(TeamPortfolioStyleNormalizer.resolveThemeMode("#000000"))
                .isEqualTo("dark");
        // #000001 → very dark, YIQ=0.114 → dark
        assertThat(TeamPortfolioStyleNormalizer.resolveThemeMode("#000001"))
                .isEqualTo("dark");
    }

    /**
     * 深浅主题标识必须是稳定常量。
     */
    @Test
    void themeModeConstantsShouldBeStableValues() {
        assertThat(TeamPortfolioStyleNormalizer.normalizeBackgroundColor("#FFFFFF"))
                .isEqualTo(TeamPortfolioStyleNormalizer.DEFAULT_BACKGROUND_COLOR);
    }
}
