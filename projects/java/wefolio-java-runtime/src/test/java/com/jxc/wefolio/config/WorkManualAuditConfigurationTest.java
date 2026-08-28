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

/** 作品人工审核飞书 HTTP 客户端配置测试。 */
class WorkManualAuditConfigurationTest {

    /** HTTP 客户端必须禁用自动重试和重定向。 */
    @Test
    void clientConfigurationDisablesRetriesAndRedirects() throws IOException {
        String source = Files.readString(Path.of(
                "src/main/java/com/jxc/wefolio/config/WorkManualAuditConfiguration.java"));

        assertThat(source)
                .contains(".disableAutomaticRetries()")
                .contains(".disableRedirectHandling()");
    }

    /** 请求配置必须精确使用配置的连接和读取超时。 */
    @Test
    void requestConfigUsesExactConfiguredTimeouts() {
        RequestConfig requestConfig = WorkManualAuditConfiguration.createRequestConfig(
                validProperties());

        assertThat(requestConfig.getConnectionRequestTimeout().toDuration())
                .isEqualTo(Duration.ofSeconds(2));
        assertThat(requestConfig.getConnectTimeout().toDuration())
                .isEqualTo(Duration.ofSeconds(2));
        assertThat(requestConfig.getResponseTimeout().toDuration())
                .isEqualTo(Duration.ofSeconds(3));
    }

    /** 配置必须暴露可关闭的独立 HTTP 客户端和 REST 客户端 Bean。 */
    @Test
    void exposesDedicatedCloseableClientAndRestClientBeans() throws NoSuchMethodException {
        WorkManualAuditConfiguration configuration = new WorkManualAuditConfiguration();
        Method method = WorkManualAuditConfiguration.class.getMethod(
                "workAuditFeishuHttpClient", WorkManualAuditProperties.class);
        Bean bean = method.getAnnotation(Bean.class);

        assertThat(WorkManualAuditConfiguration.WORK_AUDIT_HTTP_CLIENT_BEAN_NAME)
                .isNotEqualTo(WorkManualAuditConfiguration.WORK_AUDIT_REST_CLIENT_BEAN_NAME);
        assertThat(bean).isNotNull();
        assertThat(bean.destroyMethod()).isEqualTo("close");
        try (CloseableHttpClient httpClient = configuration.workAuditFeishuHttpClient(
                validProperties())) {
            assertThat(WorkManualAuditConfiguration.createRequestFactory(httpClient))
                    .isInstanceOf(HttpComponentsClientHttpRequestFactory.class);
            RestClient restClient = configuration.workAuditFeishuRestClient(httpClient);
            assertThat(restClient).isNotNull();
        } catch (Exception exception) {
            throw new AssertionError("关闭作品人工审核 HTTP 客户端失败", exception);
        }
    }

    /** 构建使用默认审核通知超时的有效配置。 */
    private WorkManualAuditProperties validProperties() {
        WorkManualAuditProperties properties = new WorkManualAuditProperties();
        properties.setConnectTimeout(Duration.ofSeconds(2));
        properties.setReadTimeout(Duration.ofSeconds(3));
        return properties;
    }
}
