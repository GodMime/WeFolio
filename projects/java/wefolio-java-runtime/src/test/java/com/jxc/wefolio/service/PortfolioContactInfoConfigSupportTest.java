package com.jxc.wefolio.service;

import com.jxc.wefolio.exception.BusinessException;
import com.jxc.wefolio.message.PortfolioMessage;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** 联系信息边框默认值、关闭保留设置、参数边界和发布约束测试。 */
class PortfolioContactInfoConfigSupportTest {

    /** 历史联系快照默认无边框与外侧留白，保留文本处理及字段白名单。 */
    @Test
    void legacyContactKeepsItsAppearanceAndTextContract() {
        var source = Map.<String, Object>of(PortfolioContactInfoConfigSupport.PHONE, " 123 ", "unknown", true);
        assertThat(PortfolioContactInfoConfigSupport.normalize(source))
                .containsEntry(PortfolioContactInfoConfigSupport.PHONE, "123")
                .containsEntry(PortfolioContactInfoConfigSupport.WECHAT, "")
                .containsEntry(PortfolioContactInfoConfigSupport.CONTACT_BORDER, false)
                .containsEntry(PortfolioContactInfoConfigSupport.CONTACT_BORDER_WIDTH_RPX, 1)
                .containsEntry(PortfolioContactInfoConfigSupport.CONTACT_BORDER_COLOR, "AUTO")
                .containsEntry(PortfolioContactInfoConfigSupport.HORIZONTAL_MARGIN_RPX, 0)
                .containsEntry(PortfolioContactInfoConfigSupport.VERTICAL_MARGIN_RPX, 0)
                .doesNotContainKey("unknown");
        assertThat(source).containsEntry(PortfolioContactInfoConfigSupport.PHONE, " 123 ")
                .doesNotContainKey(PortfolioContactInfoConfigSupport.CONTACT_BORDER);
        assertThat(PortfolioContactInfoConfigSupport.normalize(null))
                .containsEntry(PortfolioContactInfoConfigSupport.CONTACT_BORDER, false);
    }

    /** 开关两种状态均保留边框设置，宽度和留白上下限均有效，颜色统一大写。 */
    @Test
    void borderTogglePreservesAppearanceSettingsAndAcceptsBoundaries() {
        for (boolean enabled : List.of(false, true)) {
            for (int width : List.of(1, 12)) {
                for (int horizontal : List.of(0, 96)) {
                    var source = Map.<String, Object>of(PortfolioContactInfoConfigSupport.CONTACT_BORDER, enabled,
                            PortfolioContactInfoConfigSupport.CONTACT_BORDER_WIDTH_RPX, width,
                            PortfolioContactInfoConfigSupport.CONTACT_BORDER_COLOR, "#a1b2c3",
                            PortfolioContactInfoConfigSupport.HORIZONTAL_MARGIN_RPX, horizontal,
                            PortfolioContactInfoConfigSupport.VERTICAL_MARGIN_RPX, 96 - horizontal);
                    assertThat(PortfolioContactInfoConfigSupport.normalize(source))
                            .containsEntry(PortfolioContactInfoConfigSupport.CONTACT_BORDER, enabled)
                            .containsEntry(PortfolioContactInfoConfigSupport.CONTACT_BORDER_WIDTH_RPX, width)
                            .containsEntry(PortfolioContactInfoConfigSupport.CONTACT_BORDER_COLOR, "#A1B2C3")
                            .containsEntry(PortfolioContactInfoConfigSupport.HORIZONTAL_MARGIN_RPX, horizontal)
                            .containsEntry(PortfolioContactInfoConfigSupport.VERTICAL_MARGIN_RPX, 96 - horizontal);
                    assertThat(source).containsEntry(PortfolioContactInfoConfigSupport.CONTACT_BORDER_COLOR, "#a1b2c3");
                }
            }
        }
        assertThat(PortfolioContactInfoConfigSupport.normalize(Map.of(PortfolioContactInfoConfigSupport.CONTACT_BORDER_COLOR, "AUTO")))
                .containsEntry(PortfolioContactInfoConfigSupport.CONTACT_BORDER_COLOR, "AUTO");
    }

    /** 即使边框关闭也拒绝错误类型、非法颜色、超出范围和非整数数字。 */
    @Test
    void rejectsMalformedAppearanceEvenWhenBorderIsDisabled() {
        Map<String, List<Object>> invalidValues = Map.of(
                PortfolioContactInfoConfigSupport.CONTACT_BORDER, List.of("false", 0, 1, Map.of()),
                PortfolioContactInfoConfigSupport.CONTACT_BORDER_WIDTH_RPX,
                List.of(-1, 0, 13, 1.5, "2", false, Double.NaN, Double.POSITIVE_INFINITY, Long.MAX_VALUE),
                PortfolioContactInfoConfigSupport.CONTACT_BORDER_COLOR,
                List.of("", "#FFF", "#12345678", "#GGGGGG", "black", "auto", " #123456", 0, false),
                PortfolioContactInfoConfigSupport.HORIZONTAL_MARGIN_RPX,
                List.of(-1, 97, 1.5, "2", false, Double.NaN, Double.POSITIVE_INFINITY, Long.MAX_VALUE),
                PortfolioContactInfoConfigSupport.VERTICAL_MARGIN_RPX,
                List.of(-1, 97, 1.5, "2", false, Double.NaN, Double.POSITIVE_INFINITY, Long.MAX_VALUE));
        invalidValues.forEach((field, values) -> {
            for (Object value : values) {
                var source = new LinkedHashMap<String, Object>();
                source.put(PortfolioContactInfoConfigSupport.CONTACT_BORDER, false);
                source.put(field, value);
                assertThatThrownBy(() -> PortfolioContactInfoConfigSupport.normalize(source))
                        .as("非法联系信息外观 %s = %s", field, value).isInstanceOf(BusinessException.class)
                        .hasMessage(PortfolioMessage.COMPONENT_DISPLAY_OPTIONS_INVALID);
            }
            var source = new LinkedHashMap<String, Object>();
            source.put(field, null);
            assertThatThrownBy(() -> PortfolioContactInfoConfigSupport.normalize(source))
                    .as("显式空值 %s", field).isInstanceOf(BusinessException.class)
                    .hasMessage(PortfolioMessage.COMPONENT_DISPLAY_OPTIONS_INVALID);
        });
    }

    /** 开启边框不能替代有效联系信息，原有发布校验仍要求至少一项文本。 */
    @Test
    void appearanceDoesNotSatisfyPublicationContentRequirement() {
        assertThatThrownBy(() -> PortfolioContactInfoConfigSupport.validateForPublish(
                Map.of(PortfolioContactInfoConfigSupport.CONTACT_BORDER, true)))
                .isInstanceOf(BusinessException.class).hasMessage(PortfolioMessage.CONTACT_INFO_REQUIRED);
        PortfolioContactInfoConfigSupport.validateForPublish(Map.of(PortfolioContactInfoConfigSupport.CONTACT_BORDER, true,
                PortfolioContactInfoConfigSupport.WECHAT, "小映"));
    }
}
