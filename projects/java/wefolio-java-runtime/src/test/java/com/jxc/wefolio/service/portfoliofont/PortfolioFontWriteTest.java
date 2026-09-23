package com.jxc.wefolio.service.portfoliofont;

import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** 字体目录按个人或团队所属唯一码及作品集隔离。 */
class PortfolioFontWriteTest {
    /** 路径已包含完整字体子目录，调用方无需再追加 fonts。 */
    @Test void returnsCompleteOwnerFontPrefix() {
        assertThat(PortfolioFontWrite.prefix("WF1234", 12L)).isEqualTo("WF1234/others/fonts/12/");
        assertThat(PortfolioFontWrite.prefix("TM5678", 13L)).isEqualTo("TM5678/others/fonts/13/");
        assertThatThrownBy(() -> PortfolioFontWrite.prefix("../WF1234", 12L)).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> PortfolioFontWrite.prefix("WF1234", null)).isInstanceOf(IllegalStateException.class);
    }
}
