package com.jxc.wefolio.service;

import com.jxc.wefolio.config.WechatMiniappProperties;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

import java.net.http.HttpClient;

/** 微信 HTTP/1.1 REST 客户端工厂。 */
final class WechatRestClientFactory {

    /** 工具类禁止实例化。 */
    private WechatRestClientFactory() {
    }

    /**
     * 创建带连接和读取超时的微信 REST 客户端。
     *
     * @param properties 微信小程序配置
     * @return REST 客户端
     */
    static RestClient create(WechatMiniappProperties properties) {
        return RestClient.builder().requestFactory(createRequestFactory(properties)).build();
    }

    /**
     * 创建带连接和读取超时的请求工厂。
     *
     * @param properties 微信小程序配置
     * @return JDK HTTP 请求工厂
     */
    static JdkClientHttpRequestFactory createRequestFactory(WechatMiniappProperties properties) {
        HttpClient httpClient = HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_1_1)
                .connectTimeout(properties.getConnectTimeout())
                .build();
        JdkClientHttpRequestFactory requestFactory = new JdkClientHttpRequestFactory(httpClient);
        requestFactory.setReadTimeout(properties.getReadTimeout());
        return requestFactory;
    }
}
