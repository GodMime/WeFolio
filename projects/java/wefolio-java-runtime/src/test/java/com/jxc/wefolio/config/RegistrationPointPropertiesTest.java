package com.jxc.wefolio.config;

import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;

import static org.assertj.core.api.Assertions.assertThat;

/** 注册赠送积分配置测试。 */
class RegistrationPointPropertiesTest {

    /** 加载真实 application.yml 并执行配置绑定的轻量上下文。 */
    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withInitializer(new ConfigDataApplicationContextInitializer())
            .withUserConfiguration(TestConfiguration.class);

    /** 新用户与推荐人默认赠送积分应符合当前产品规则。 */
    @Test
    void defaultsShouldMatchRegistrationGiftRules() {
        RegistrationPointProperties properties = new RegistrationPointProperties();

        assertThat(properties.getNewUserGiftPoints()).isEqualTo(1000L);
        assertThat(properties.getReferralGiftPoints()).isEqualTo(500L);
    }

    /** application.yml 未被环境变量覆盖时应绑定当前产品规则。 */
    @Test
    void applicationYamlDefaultsShouldBindRegistrationGiftRules() {
        contextRunner.run(context -> {
            RegistrationPointProperties properties = context.getBean(RegistrationPointProperties.class);

            assertThat(properties.getNewUserGiftPoints()).isEqualTo(1000L);
            assertThat(properties.getReferralGiftPoints()).isEqualTo(500L);
        });
    }

    /** 注册配置绑定测试入口。 */
    @Configuration(proxyBeanMethods = false)
    @EnableConfigurationProperties(RegistrationPointProperties.class)
    static class TestConfiguration {
    }
}
