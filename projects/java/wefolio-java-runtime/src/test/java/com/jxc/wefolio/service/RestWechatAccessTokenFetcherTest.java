package com.jxc.wefolio.service;

import com.jxc.wefolio.config.WechatMiniappProperties;
import com.jxc.wefolio.dto.WechatAccessTokenResponse;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

/** 微信 AccessToken 获取日志测试。 */
@ExtendWith(OutputCaptureExtension.class)
class RestWechatAccessTokenFetcherTest {

    /** REST 客户端应保持构造后不可重新赋值。 */
    @Test
    void restClientShouldBeFinal() throws NoSuchFieldException {
        Field field = RestWechatAccessTokenFetcher.class.getDeclaredField("restClient");

        assertThat(Modifier.isFinal(field.getModifiers())).isTrue();
    }

    /** 获取凭证时应记录完整脱敏交互日志，且不得泄漏密钥和凭证。 */
    @Test
    void logsSanitizedRequestAndResponse(CapturedOutput output) throws Exception {
        WechatMiniappProperties properties = new WechatMiniappProperties();
        properties.setAppId("wxa-test");
        properties.setAppSecret("app-secret-test");
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        RestWechatAccessTokenFetcher fetcher = new RestWechatAccessTokenFetcher(
                properties, new WechatInteractionLogSanitizer());
        injectRestClient(fetcher, builder.build());
        server.expect(requestTo("https://api.weixin.qq.com/cgi-bin/token?grant_type=client_credential"
                        + "&appid=wxa-test&secret=app-secret-test"))
                .andRespond(withSuccess(
                        "{\"access_token\":\"access-token-test\",\"expires_in\":7200,"
                                + "\"errcode\":0,\"errmsg\":\"ok\"}",
                        MediaType.APPLICATION_JSON));

        WechatAccessTokenResponse response = fetcher.fetch();

        assertThat(response.getAccessToken()).isEqualTo("access-token-test");
        assertThat(output).contains("微信交互请求 operation=获取AccessToken");
        assertThat(output).contains("method=GET");
        assertThat(output).contains("secret=***");
        assertThat(output).contains("微信交互响应 operation=获取AccessToken");
        assertThat(output).contains("httpStatus=200 response=");
        assertThat(output).contains("\"access_token\":\"***\"");
        assertThat(output).contains("elapsedMs=");
        assertThat(output).doesNotContain("app-secret-test");
        assertThat(output).doesNotContain("access-token-test");
        server.verify();
    }

    /** 注入与模拟服务器绑定的 REST 客户端。 */
    private void injectRestClient(RestWechatAccessTokenFetcher fetcher, RestClient restClient) throws Exception {
        Field field = RestWechatAccessTokenFetcher.class.getDeclaredField("restClient");
        field.setAccessible(true);
        field.set(fetcher, restClient);
    }
}
