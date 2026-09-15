package com.jxc.wefolio.config;

import com.jxc.wefolio.dict.LoginTabDict;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.env.SystemEnvironmentPropertySource;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** 登录页配置绑定测试，覆盖真实 YAML 的默认值与部署环境变量覆盖。 */
class LoginPagePropertiesTest {

    /** 部署环境控制登录页默认标签的变量名。 */
    private static final String DEFAULT_TAB_ENVIRONMENT_VARIABLE = "WEFOLIO_LOGIN_DEFAULT_TAB";

    /** YAML 中的登录页默认标签配置键。 */
    private static final String DEFAULT_TAB_PROPERTY = "wefolio.login.default-tab";

    /** 配置对象未绑定外部参数时也应默认展示体验标签。 */
    @Test
    void propertiesShouldDefaultToExperience() {
        assertThat(new LoginPageProperties().getDefaultTab()).isEqualTo(LoginTabDict.EXPERIENCE.getCode());
    }

    /** 未设置环境变量时，真实 YAML 应提供体验标签并绑定到配置对象。 */
    @Test
    void applicationYamlShouldDefaultToExperience() {
        contextRunner(Map.of()).run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context.getEnvironment().getProperty(DEFAULT_TAB_PROPERTY))
                    .isEqualTo(LoginTabDict.EXPERIENCE.getCode());
            assertThat(context.getBean(LoginPageProperties.class).getDefaultTab())
                    .isEqualTo(LoginTabDict.EXPERIENCE.getCode());
        });
    }

    /** 运维可通过环境变量切换两个默认标签，且无需修改 YAML。 */
    @ParameterizedTest
    @EnumSource(LoginTabDict.class)
    void environmentVariableShouldOverrideApplicationYaml(LoginTabDict tab) {
        contextRunner(Map.of(DEFAULT_TAB_ENVIRONMENT_VARIABLE, tab.getCode())).run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context.getEnvironment().getProperty(DEFAULT_TAB_PROPERTY)).isEqualTo(tab.getCode());
            assertThat(context.getBean(LoginPageProperties.class).getDefaultTab()).isEqualTo(tab.getCode());
        });
    }

    /** 隔离宿主环境，加载真实 application.yml 并执行 Spring 配置绑定。 */
    private ApplicationContextRunner contextRunner(Map<String, Object> environmentVariables) {
        return new ApplicationContextRunner()
                .withInitializer(context -> {
                    context.getEnvironment().getPropertySources()
                            .remove(StandardEnvironment.SYSTEM_PROPERTIES_PROPERTY_SOURCE_NAME);
                    context.getEnvironment().getPropertySources().replace(
                            StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME,
                            new SystemEnvironmentPropertySource(
                                    StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME,
                                    environmentVariables));
                })
                .withInitializer(new ConfigDataApplicationContextInitializer())
                .withUserConfiguration(TestConfiguration.class);
    }

    /** 仅注册登录页配置，避免启动数据库与远端客户端。 */
    @Configuration(proxyBeanMethods = false)
    @EnableConfigurationProperties(LoginPageProperties.class)
    static class TestConfiguration {
    }
}
