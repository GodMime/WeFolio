package com.jxc.wefolio.config;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;

import static org.assertj.core.api.Assertions.assertThat;

/** AI 启动绑定边界：启用时只能投递到指定 TokenHub 模型，关闭时支持先部署。 */
class PortfolioAiPropertiesTest {
    /** 不访问数据库和外部服务的真实配置绑定上下文。 */
    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withInitializer(new ConfigDataApplicationContextInitializer())
            .withUserConfiguration(TestConfiguration.class);

    /** 配置文件默认模型和采样参数与 TokenHub 接入要求保持一致。 */
    @Test
    void defaultsBindTokenHubModelAndRecommendedTemperature() {
        contextRunner.withPropertyValues("portfolio-ai.enabled=true").run(context -> {
            assertThat(context).hasNotFailed();
            PortfolioAiProperties properties = context.getBean(PortfolioAiProperties.class);
            assertThat(properties.getBaseUrl()).isEqualTo("https://tokenhub.tencentmaas.com/v1");
            assertThat(properties.getModel()).isEqualTo("glm-5.3-flash");
            assertThat(properties.getTemperature()).isEqualTo(1.0);
            assertThat(properties.getMaxTokens()).isEqualTo(8192);
        });
    }

    /** 启用时拒绝旧供应商、相似主机、附加路径和鉴权信息，防止任务冻结错误端点。 */
    @ParameterizedTest
    @ValueSource(strings = {"https://api.deepseek.com", "https://tokenhub.tencentmaas.com.evil.test/v1",
            "https://tokenhub.tencentmaas.com/v1/chat/completions", "https://user@tokenhub.tencentmaas.com/v1",
            "https://tokenhub.tencentmaas.com/v1?token=value", "http://tokenhub.tencentmaas.com/v1"})
    void enabledConfigurationRejectsUnsupportedEndpoint(String endpoint) {
        contextRunner.withPropertyValues("portfolio-ai.enabled=true", "portfolio-ai.base-url=" + endpoint)
                .run(context -> assertThat(context).hasFailed());
    }

    /** 旧供应商模型与同系列其他模型均不能误入指定 Flash 接入。 */
    @ParameterizedTest
    @ValueSource(strings = {"deepseek-flash", "glm-5.3", ""})
    void enabledConfigurationRejectsUnsupportedModel(String model) {
        contextRunner.withPropertyValues("portfolio-ai.enabled=true", "portfolio-ai.model=" + model)
                .run(context -> assertThat(context).hasFailed());
    }

    /** 非有限值和越界采样温度应在启动时拒绝，不能生成必然被 Job 拒绝的任务。 */
    @ParameterizedTest
    @ValueSource(strings = {"-0.1", "2.1", "NaN", "Infinity", "-Infinity"})
    void enabledConfigurationRejectsInvalidTemperature(String temperature) {
        contextRunner.withPropertyValues("portfolio-ai.enabled=true", "portfolio-ai.temperature=" + temperature)
                .run(context -> assertThat(context).hasFailed());
    }

    /** 输出预算与客户端保持同一上限，关闭前不能接收无法调用模型的任务。 */
    @ParameterizedTest
    @ValueSource(ints = {-1, 0, 8193, Integer.MAX_VALUE})
    void enabledConfigurationRejectsInvalidOutputBudget(int maxTokens) {
        contextRunner.withPropertyValues("portfolio-ai.enabled=true", "portfolio-ai.max-tokens=" + maxTokens)
                .run(context -> assertThat(context).hasFailed());
    }

    /** 采样温度与输出预算的上下边界都能完成启动绑定。 */
    @ParameterizedTest
    @CsvSource({"0.0,1", "2.0,8192"})
    void enabledConfigurationAcceptsSamplingBudgetBoundaries(double temperature, int maxTokens) {
        contextRunner.withPropertyValues("portfolio-ai.enabled=true", "portfolio-ai.temperature=" + temperature,
                        "portfolio-ai.max-tokens=" + maxTokens)
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    PortfolioAiProperties properties = context.getBean(PortfolioAiProperties.class);
                    assertThat(properties.getTemperature()).isEqualTo(temperature);
                    assertThat(properties.getMaxTokens()).isEqualTo(maxTokens);
                });
    }

    /** 官方根地址末尾斜杠不改变供应商身份，允许既有环境变量书写习惯。 */
    @Test
    void enabledConfigurationAllowsOfficialEndpointTrailingSlash() {
        contextRunner.withPropertyValues("portfolio-ai.enabled=true",
                        "portfolio-ai.base-url=" + PortfolioAiProperties.TOKENHUB_BASE_URL + "/")
                .run(context -> assertThat(context).hasNotFailed());
    }

    /** 开关关闭时允许保留旧配置发布，避免影响保存和已存在任务查询。 */
    @Test
    void disabledConfigurationAllowsLegacyEnvironmentValues() {
        contextRunner.withPropertyValues("portfolio-ai.enabled=false", "portfolio-ai.base-url=https://api.deepseek.com",
                        "portfolio-ai.model=deepseek-flash")
                .run(context -> assertThat(context).hasNotFailed());
    }

    /** 只注册 AI 属性的测试配置入口。 */
    @Configuration(proxyBeanMethods = false)
    @EnableConfigurationProperties(PortfolioAiProperties.class)
    static class TestConfiguration { }
}
