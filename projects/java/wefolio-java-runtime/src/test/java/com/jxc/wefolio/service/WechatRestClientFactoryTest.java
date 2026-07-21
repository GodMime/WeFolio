package com.jxc.wefolio.service;

import com.jxc.wefolio.config.WechatMiniappProperties;
import org.junit.jupiter.api.Test;
import org.springframework.http.client.JdkClientHttpRequestFactory;

import java.lang.reflect.Field;
import java.net.http.HttpClient;
import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

/** 微信 REST 客户端工厂测试。 */
class WechatRestClientFactoryTest {

    @Test
    void requestFactoryShouldApplyConfiguredTimeouts() throws ReflectiveOperationException {
        WechatMiniappProperties properties = new WechatMiniappProperties();
        properties.setConnectTimeout(Duration.ofSeconds(2));
        properties.setReadTimeout(Duration.ofMillis(750));

        JdkClientHttpRequestFactory requestFactory = WechatRestClientFactory.createRequestFactory(properties);

        HttpClient httpClient = readField(requestFactory, "httpClient", HttpClient.class);
        Duration readTimeout = readField(requestFactory, "readTimeout", Duration.class);
        assertThat(httpClient.connectTimeout()).contains(Duration.ofSeconds(2));
        assertThat(readTimeout).isEqualTo(Duration.ofMillis(750));
        assertThat(httpClient.version()).isEqualTo(HttpClient.Version.HTTP_1_1);
    }

    /** 读取请求工厂内部配置，避免测试依赖本地监听端口。 */
    private <T> T readField(Object target, String fieldName, Class<T> fieldType)
            throws ReflectiveOperationException {
        Field field = target.getClass().getDeclaredField(fieldName);
        field.setAccessible(true);
        return fieldType.cast(field.get(target));
    }
}
