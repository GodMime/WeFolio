package com.jxc.wefolio.config;

import org.apache.hc.client5.http.config.RequestConfig;
import org.apache.hc.client5.http.impl.classic.CloseableHttpClient;
import org.apache.hc.client5.http.impl.classic.HttpClients;
import org.apache.hc.core5.util.Timeout;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.HttpComponentsClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

/** 作品人工审核飞书 HTTP 客户端配置。 */
@Configuration
public class WorkManualAuditConfiguration {

    /** 作品审核飞书 Apache HTTP 客户端 Bean 名称。 */
    public static final String WORK_AUDIT_HTTP_CLIENT_BEAN_NAME =
            "workAuditFeishuHttpClient";

    /** 作品审核飞书 REST 客户端 Bean 名称。 */
    public static final String WORK_AUDIT_REST_CLIENT_BEAN_NAME =
            "workAuditFeishuRestClient";

    /**
     * 创建禁用自动重试和重定向的飞书 Apache classic HTTP/1.1 客户端。
     *
     * @param properties 作品人工审核配置
     * @return 可关闭的飞书 HTTP 客户端
     */
    @Bean(name = WORK_AUDIT_HTTP_CLIENT_BEAN_NAME, destroyMethod = "close")
    public CloseableHttpClient workAuditFeishuHttpClient(WorkManualAuditProperties properties) {
        return HttpClients.custom()
                .setDefaultRequestConfig(createRequestConfig(properties))
                .disableAutomaticRetries()
                .disableRedirectHandling()
                .build();
    }

    /**
     * 创建使用 Apache classic 请求工厂的作品审核飞书 REST 客户端。
     *
     * @param httpClient 飞书 Apache HTTP 客户端
     * @return 飞书 REST 客户端
     */
    @Bean(WORK_AUDIT_REST_CLIENT_BEAN_NAME)
    public RestClient workAuditFeishuRestClient(
            @Qualifier(WORK_AUDIT_HTTP_CLIENT_BEAN_NAME) CloseableHttpClient httpClient
    ) {
        return RestClient.builder().requestFactory(createRequestFactory(httpClient)).build();
    }

    /** 创建精确应用连接和响应超时的请求配置。 */
    @SuppressWarnings("deprecation")
    static RequestConfig createRequestConfig(WorkManualAuditProperties properties) {
        return RequestConfig.custom()
                .setConnectionRequestTimeout(Timeout.of(properties.getConnectTimeout()))
                .setConnectTimeout(Timeout.of(properties.getConnectTimeout()))
                .setResponseTimeout(Timeout.of(properties.getReadTimeout()))
                .build();
    }

    /** 创建 Apache classic HTTP 请求工厂。 */
    static HttpComponentsClientHttpRequestFactory createRequestFactory(
            CloseableHttpClient httpClient
    ) {
        return new HttpComponentsClientHttpRequestFactory(httpClient);
    }
}
