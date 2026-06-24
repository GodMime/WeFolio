package com.jxc.wefolio.service;

import com.jxc.wefolio.config.WechatMiniappProperties;
import com.jxc.wefolio.dto.WechatSessionResponse;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import java.lang.reflect.Field;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

class RestWechatMiniappClientTest {

    @Test
    void exchangeCodeParsesWechatTextPlainJsonResponse() throws Exception {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        RestWechatMiniappClient client = new RestWechatMiniappClient(properties());
        injectRestClient(client, builder.build());
        server.expect(requestTo(
                        "https://api.weixin.qq.com/sns/jscode2session?appid=wxa-test&secret=secret-for-hmac&js_code=wx-code&grant_type=authorization_code"
                ))
                .andRespond(withSuccess(
                        "{\"openid\":\"openid-123\",\"session_key\":\"session-key\"}",
                        MediaType.TEXT_PLAIN
                ));

        WechatSessionResponse response = client.exchangeCode("wx-code");

        assertThat(response.getOpenid()).isEqualTo("openid-123");
        server.verify();
    }

    private void injectRestClient(RestWechatMiniappClient client, RestClient restClient) throws Exception {
        Field field = RestWechatMiniappClient.class.getDeclaredField("restClient");
        field.setAccessible(true);
        field.set(client, restClient);
    }

    private WechatMiniappProperties properties() {
        WechatMiniappProperties properties = new WechatMiniappProperties();
        properties.setAppId("wxa-test");
        properties.setAppSecret("secret-for-hmac");
        return properties;
    }
}
