package com.jxc.wefolio.job.config;

import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.io.ClassPathResource;
import org.springframework.mock.env.MockEnvironment;

import java.io.IOException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** 实际 YAML 的供应商密钥绑定及启动校验，不访问真实环境或密钥文件。 */
class PortfolioAiPropertiesTest {
    /** 在隔离测试环境绑定应用中的真实配置段。 */
    private PortfolioAiProperties bind(MockEnvironment environment) throws IOException {
        for (var source : new YamlPropertySourceLoader().load("application", new ClassPathResource("application.yml"))) {
            environment.getPropertySources().addLast(source);
        }
        return Binder.get(environment).bind("portfolio-ai", PortfolioAiProperties.class).get();
    }

    /** 新密钥变量唯一生效，旧供应商密钥不进入配置。 */
    @Test
    void applicationBindsTokenHubDefaultsAndOnlyNewApiKey() throws IOException {
        PortfolioAiProperties properties = bind(new MockEnvironment()
                .withProperty("WEFOLIO_AI_ENABLED", "true")
                .withProperty("TOKENHUB_API_KEY", "test-tokenhub-key")
                .withProperty("DEEPSEEK_API_KEY", "test-legacy-key"));
        assertThat(properties.getBaseUrl()).isEqualTo("https://tokenhub.tencentmaas.com/v1");
        assertThat(properties.getModel()).isEqualTo("glm-5.3-flash");
        assertThat(properties.getApiKey()).isEqualTo("test-tokenhub-key");
        assertThatCode(properties::validate).doesNotThrowAnyException();
        assertThat(properties.toString()).doesNotContain("test-tokenhub-key", "test-legacy-key");
    }

    /** 启用后不能因旧密钥仍在部署环境中而继续启动。 */
    @Test
    void enabledWithOnlyLegacyKeyFailsBeforeScheduling() throws IOException {
        PortfolioAiProperties properties = bind(new MockEnvironment()
                .withProperty("WEFOLIO_AI_ENABLED", "true").withProperty("DEEPSEEK_API_KEY", "test-legacy-key"));
        assertThat(properties.getApiKey()).isEmpty();
        assertThatThrownBy(properties::validate).isInstanceOf(IllegalStateException.class)
                .hasMessage("启用高级 AI 前须配置 TOKENHUB_API_KEY");
    }

    /** 分阶段部署可先关闭，启用时必须切换到当前模型与官方端点。 */
    @Test
    void disabledLegacyConfigIsAllowedButEnabledLegacyEndpointOrModelFails() {
        PortfolioAiProperties properties = new PortfolioAiProperties();
        properties.setBaseUrl("https://api.deepseek.com"); properties.setModel("deepseek-flash");
        assertThatCode(properties::validate).doesNotThrowAnyException();
        properties.setEnabled(true); properties.setApiKey("test-tokenhub-key");
        assertThatThrownBy(properties::validate).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("腾讯云 TokenHub");
        properties.setBaseUrl(PortfolioAiProperties.TOKENHUB_BASE_URL);
        assertThatThrownBy(properties::validate).isInstanceOf(IllegalStateException.class);
        properties.setModel(PortfolioAiProperties.TOKENHUB_MODEL);
        properties.setBaseUrl(PortfolioAiProperties.TOKENHUB_BASE_URL + "/");
        assertThatCode(properties::validate).doesNotThrowAnyException();
    }
}
