package com.jxc.wefolio.service;

import com.jxc.wefolio.dict.PortfolioProfileLayoutDict;
import com.jxc.wefolio.exception.BusinessException;
import com.jxc.wefolio.message.PortfolioMessage;
import org.junit.jupiter.api.Test;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** 个人资料卡布局、边框、外侧留白边界及旧版默认值测试。 */
class PortfolioProfileConfigSupportTest {
    /** 历史资料和展示开关原样保留，布局默认保持旧版纵向外观。 */
    @Test void keepsLegacyDataAndVerticalLayout() {
        Map<String, Object> source = Map.of("profile", Map.of("displayName", "竞成"), "visibleFields", Map.of("bio", false));
        assertThat(PortfolioProfileConfigSupport.normalize(source)).containsAllEntriesOf(source)
                .containsEntry(PortfolioProfileConfigSupport.PROFILE_LAYOUT, PortfolioProfileLayoutDict.VERTICAL.getCode())
                .containsEntry(PortfolioProfileConfigSupport.PROFILE_BORDER, false)
                .containsEntry(PortfolioProfileConfigSupport.PROFILE_BORDER_WIDTH_RPX, 1)
                .containsEntry(PortfolioProfileConfigSupport.PROFILE_BORDER_COLOR, "AUTO")
                .containsEntry(PortfolioProfileConfigSupport.PROFILE_HORIZONTAL_MARGIN_RPX, 32)
                .containsEntry(PortfolioProfileConfigSupport.PROFILE_VERTICAL_MARGIN_RPX, 0);
        assertThat(source).doesNotContainKey(PortfolioProfileConfigSupport.PROFILE_LAYOUT);
        assertThat(PortfolioProfileConfigSupport.normalize(null))
                .containsEntry(PortfolioProfileConfigSupport.PROFILE_LAYOUT, PortfolioProfileLayoutDict.VERTICAL.getCode());
    }

    /** 两种已知布局均可保存，横向布局不改写个人资料。 */
    @Test void acceptsSupportedLayouts() {
        for (var layout : PortfolioProfileLayoutDict.values()) {
            Map<String, Object> source = Map.of(PortfolioProfileConfigSupport.PROFILE_LAYOUT, layout.getCode(),
                    "profile", Map.of("displayName", "竞成"));
            assertThat(PortfolioProfileConfigSupport.normalize(source)).containsAllEntriesOf(source);
        }
    }

    /** 新布局拒绝错误枚举和类型，显式空值也不得静默默认化。 */
    @Test void rejectsInvalidLayouts() {
        for (Object value : List.of("", "horizontal", 1, false, Map.of(), List.of())) {
            assertThatThrownBy(() -> PortfolioProfileConfigSupport.normalize(Map.of(PortfolioProfileConfigSupport.PROFILE_LAYOUT, value)))
                    .as("非法资料卡布局 %s", value).isInstanceOf(BusinessException.class)
                    .hasMessage(PortfolioMessage.COMPONENT_DISPLAY_OPTIONS_INVALID);
        }
        Map<String, Object> explicitNull = new LinkedHashMap<>(); explicitNull.put(PortfolioProfileConfigSupport.PROFILE_LAYOUT, null);
        assertThatThrownBy(() -> PortfolioProfileConfigSupport.normalize(explicitNull)).isInstanceOf(BusinessException.class)
                .hasMessage(PortfolioMessage.COMPONENT_DISPLAY_OPTIONS_INVALID);
    }

    /** 关闭和开启边框均保留自定义样式，宽度上下限和自动色合法且来源配置不变。 */
    @Test void preservesBorderStyleRegardlessOfToggle() {
        for (boolean enabled : List.of(false, true)) {
            for (int width : List.of(1, 12)) {
                Map<String, Object> source = Map.of(PortfolioProfileConfigSupport.PROFILE_BORDER, enabled,
                        PortfolioProfileConfigSupport.PROFILE_BORDER_WIDTH_RPX, width,
                        PortfolioProfileConfigSupport.PROFILE_BORDER_COLOR, "#a1b2c3");
                assertThat(PortfolioProfileConfigSupport.normalize(source))
                        .containsEntry(PortfolioProfileConfigSupport.PROFILE_BORDER, enabled)
                        .containsEntry(PortfolioProfileConfigSupport.PROFILE_BORDER_WIDTH_RPX, width)
                        .containsEntry(PortfolioProfileConfigSupport.PROFILE_BORDER_COLOR, "#A1B2C3");
                assertThat(source).containsEntry(PortfolioProfileConfigSupport.PROFILE_BORDER_COLOR, "#a1b2c3");
            }
        }
        assertThat(PortfolioProfileConfigSupport.normalize(Map.of(PortfolioProfileConfigSupport.PROFILE_BORDER, true,
                PortfolioProfileConfigSupport.PROFILE_BORDER_COLOR, "AUTO")))
                .containsEntry(PortfolioProfileConfigSupport.PROFILE_BORDER_COLOR, "AUTO");
    }

    /** 边框关闭时仍严格拒绝非法开关、宽度和颜色，显式空值不得静默默认化。 */
    @Test void rejectsMalformedBordersEvenWhenDisabled() {
        Map<String, List<Object>> invalidValues = Map.of(
                PortfolioProfileConfigSupport.PROFILE_BORDER, List.of("false", 0, 1, Map.of()),
                PortfolioProfileConfigSupport.PROFILE_BORDER_WIDTH_RPX,
                List.of(-1, 0, 13, 1.5, "2", false, Double.NaN, Double.POSITIVE_INFINITY, Long.MAX_VALUE),
                PortfolioProfileConfigSupport.PROFILE_BORDER_COLOR,
                List.of("", "#FFF", "#12345678", "#GGGGGG", "black", "auto", " #123456", 0, false));
        invalidValues.forEach((field, values) -> {
            for (Object value : values) {
                Map<String, Object> source = new LinkedHashMap<>(Map.of(PortfolioProfileConfigSupport.PROFILE_BORDER, false));
                source.put(field, value);
                assertThatThrownBy(() -> PortfolioProfileConfigSupport.normalize(source))
                        .as("非法资料卡 %s = %s", field, value).isInstanceOf(BusinessException.class)
                        .hasMessage(PortfolioMessage.COMPONENT_DISPLAY_OPTIONS_INVALID);
            }
            Map<String, Object> explicitNull = new LinkedHashMap<>(); explicitNull.put(field, null);
            assertThatThrownBy(() -> PortfolioProfileConfigSupport.normalize(explicitNull))
                    .as("显式空值 %s", field).isInstanceOf(BusinessException.class)
                    .hasMessage(PortfolioMessage.COMPONENT_DISPLAY_OPTIONS_INVALID);
        });
    }

    /** 留白两个方向独立保存并接受上下限，关闭边框也保留留白且不改写来源配置。 */
    @Test void preservesOuterMarginsRegardlessOfBorderToggle() {
        for (boolean border : List.of(false, true)) {
            Map<String, Object> source = Map.of(PortfolioProfileConfigSupport.PROFILE_BORDER, border,
                    PortfolioProfileConfigSupport.PROFILE_HORIZONTAL_MARGIN_RPX, 96,
                    PortfolioProfileConfigSupport.PROFILE_VERTICAL_MARGIN_RPX, 24);
            Map<String, Object> normalized = PortfolioProfileConfigSupport.normalize(source);
            assertThat(normalized).containsEntry(PortfolioProfileConfigSupport.PROFILE_BORDER, border)
                    .containsEntry(PortfolioProfileConfigSupport.PROFILE_HORIZONTAL_MARGIN_RPX, 96)
                    .containsEntry(PortfolioProfileConfigSupport.PROFILE_VERTICAL_MARGIN_RPX, 24);
            normalized.put(PortfolioProfileConfigSupport.PROFILE_HORIZONTAL_MARGIN_RPX, 0);
            normalized.put(PortfolioProfileConfigSupport.PROFILE_VERTICAL_MARGIN_RPX, 96);
            assertThat(PortfolioProfileConfigSupport.normalize(normalized))
                    .containsEntry(PortfolioProfileConfigSupport.PROFILE_HORIZONTAL_MARGIN_RPX, 0)
                    .containsEntry(PortfolioProfileConfigSupport.PROFILE_VERTICAL_MARGIN_RPX, 96);
            assertThat(source).containsEntry(PortfolioProfileConfigSupport.PROFILE_HORIZONTAL_MARGIN_RPX, 96)
                    .containsEntry(PortfolioProfileConfigSupport.PROFILE_VERTICAL_MARGIN_RPX, 24);
        }
    }

    /** 留白严格拒绝越界、非整数、错误类型和显式空值，不因边框关闭而绕过校验。 */
    @Test void rejectsMalformedOuterMarginsEvenWhenBorderDisabled() {
        for (String field : List.of(PortfolioProfileConfigSupport.PROFILE_HORIZONTAL_MARGIN_RPX,
                PortfolioProfileConfigSupport.PROFILE_VERTICAL_MARGIN_RPX)) {
            for (Object value : new Object[]{-1, 97, 1.5, "2", false, Double.NaN, Double.POSITIVE_INFINITY, Long.MAX_VALUE, null}) {
                Map<String, Object> source = new LinkedHashMap<>(Map.of(PortfolioProfileConfigSupport.PROFILE_BORDER, false));
                source.put(field, value);
                assertThatThrownBy(() -> PortfolioProfileConfigSupport.normalize(source))
                        .as("非法资料卡 %s = %s", field, value).isInstanceOf(BusinessException.class)
                        .hasMessage(PortfolioMessage.COMPONENT_DISPLAY_OPTIONS_INVALID);
            }
        }
    }
}
