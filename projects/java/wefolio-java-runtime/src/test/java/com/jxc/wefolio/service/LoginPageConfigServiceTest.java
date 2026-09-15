package com.jxc.wefolio.service;

import com.jxc.wefolio.config.LoginPageProperties;
import com.jxc.wefolio.dict.LoginTabDict;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.MethodSource;

import java.util.Locale;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/** 登录页配置服务测试，验证允许的标签与配置错误时的体验兜底。 */
class LoginPageConfigServiceTest {

    /** 未提供配置时应向前端返回体验标签。 */
    @Test
    void missingConfigurationShouldReturnExperience() {
        LoginPageConfigService service = new LoginPageConfigService(new LoginPageProperties());

        assertThat(service.getConfig().getDefaultTab()).isEqualTo(LoginTabDict.EXPERIENCE.getCode());
    }

    /** 两个合法配置值都应原样返回，允许运维控制默认入口。 */
    @ParameterizedTest
    @EnumSource(LoginTabDict.class)
    void supportedTabShouldBeReturned(LoginTabDict tab) {
        LoginPageProperties properties = new LoginPageProperties();
        properties.setDefaultTab(tab.getCode());
        LoginPageConfigService service = new LoginPageConfigService(properties);

        assertThat(service.getConfig().getDefaultTab()).isEqualTo(tab.getCode());
    }

    /** 空值、未知值、大小写或空格错误均应兜底体验，不阻断登录入口。 */
    @ParameterizedTest
    @MethodSource("invalidDefaultTabs")
    void invalidTabShouldFallBackToExperience(String defaultTab) {
        LoginPageProperties properties = new LoginPageProperties();
        properties.setDefaultTab(defaultTab);
        LoginPageConfigService service = new LoginPageConfigService(properties);

        assertThat(service.getConfig().getDefaultTab()).isEqualTo(LoginTabDict.EXPERIENCE.getCode());
    }

    /** 提供环境变量常见误配值，确保服务只接受严格匹配的标签标识。 */
    private static Stream<String> invalidDefaultTabs() {
        return Stream.of(null, "", " ", "unknown",
                LoginTabDict.MAINTAINER.getCode().toUpperCase(Locale.ROOT),
                " " + LoginTabDict.MAINTAINER.getCode() + " ");
    }
}
