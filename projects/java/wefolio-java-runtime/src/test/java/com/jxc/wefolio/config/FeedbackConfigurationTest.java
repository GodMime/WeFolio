package com.jxc.wefolio.config;

import org.apache.hc.client5.http.config.RequestConfig;
import org.apache.hc.client5.http.impl.classic.CloseableHttpClient;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.Bean;
import org.springframework.http.client.HttpComponentsClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

import java.io.IOException;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

/** 问题反馈 Apache HTTP 客户端配置测试。 */
class FeedbackConfigurationTest {

    /** 客户端构建必须显式关闭 Apache 自动重试。 */
    @Test
    void clientConfigurationExplicitlyDisablesAutomaticRetries() throws IOException {
        String source = Files.readString(Path.of(
                "src/main/java/com/jxc/wefolio/config/FeedbackConfiguration.java"));

        assertThat(source).contains(".disableAutomaticRetries()");
    }

    /** 请求配置必须精确应用连接和响应超时。 */
    @Test
    void requestConfigUsesExactConfiguredTimeouts() {
        RequestConfig requestConfig = FeedbackConfiguration.createRequestConfig(validProperties());

        assertThat(requestConfig.getConnectionRequestTimeout().toDuration())
                .isEqualTo(Duration.ofSeconds(2));
        assertThat(requestConfig.getConnectTimeout().toDuration()).isEqualTo(Duration.ofSeconds(2));
        assertThat(requestConfig.getResponseTimeout().toDuration()).isEqualTo(Duration.ofSeconds(3));
    }

    /** HTTP 客户端必须是可关闭的独立 Bean，RestClient 必须使用 Apache 请求工厂。 */
    @Test
    void exposesCloseableClientBeanAndApacheRequestFactory() throws NoSuchMethodException {
        FeedbackConfiguration configuration = new FeedbackConfiguration();
        Method clientBeanMethod = FeedbackConfiguration.class.getMethod(
                "feedbackFeishuHttpClient", FeedbackProperties.class);
        Bean bean = clientBeanMethod.getAnnotation(Bean.class);

        assertThat(FeedbackConfiguration.FEEDBACK_HTTP_CLIENT_BEAN_NAME)
                .isNotEqualTo(FeedbackConfiguration.FEEDBACK_REST_CLIENT_BEAN_NAME);
        assertThat(bean).isNotNull();
        assertThat(bean.destroyMethod()).isEqualTo("close");
        try (CloseableHttpClient httpClient = configuration.feedbackFeishuHttpClient(validProperties())) {
            assertThat(FeedbackConfiguration.createRequestFactory(httpClient))
                    .isInstanceOf(HttpComponentsClientHttpRequestFactory.class);
            RestClient restClient = configuration.feedbackFeishuRestClient(httpClient);
            assertThat(restClient).isNotNull();
        } catch (Exception exception) {
            throw new AssertionError("关闭反馈 HTTP 客户端失败", exception);
        }
    }

    /** 构造使用固定超时的反馈配置。 */
    private FeedbackProperties validProperties() {
        FeedbackProperties properties = new FeedbackProperties();
        properties.setConnectTimeout(Duration.ofSeconds(2));
        properties.setReadTimeout(Duration.ofSeconds(3));
        return properties;
    }
}
