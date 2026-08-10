package com.jxc.wefolio.service;

import com.jxc.wefolio.config.WechatMiniappProperties;
import com.jxc.wefolio.common.cache.LocalCacheService;
import com.jxc.wefolio.common.lock.TestDistributedLockExecutor;
import com.jxc.wefolio.config.LocalCacheProperties;
import com.jxc.wefolio.dto.WechatPhoneNumberResponse;
import com.jxc.wefolio.exception.BusinessException;
import com.jxc.wefolio.dto.WechatSessionResponse;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import java.lang.reflect.Field;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

@ExtendWith(OutputCaptureExtension.class)
class RestWechatMiniappClientTest {

    @Test
    void exchangeCodeParsesWechatTextPlainJsonResponse(CapturedOutput output) throws Exception {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        RestWechatMiniappClient client = createClient(properties(), builder.build());
        server.expect(requestTo(
                        "https://api.weixin.qq.com/sns/jscode2session?appid=wxa-test&secret=secret-for-hmac&js_code=wx-code&grant_type=authorization_code"
                ))
                .andRespond(withSuccess(
                        "{\"openid\":\"openid-123\",\"session_key\":\"session-key\"}",
                        MediaType.TEXT_PLAIN
                ));

        WechatSessionResponse response = client.exchangeCode("wx-code");

        assertThat(response.getOpenid()).isEqualTo("openid-123");
        assertThat(output).contains("微信交互请求 operation=微信登录服务");
        assertThat(output).contains("method=GET");
        assertThat(output).contains("secret=***");
        assertThat(output).contains("js_code=***");
        assertThat(output).contains("微信交互响应 operation=微信登录服务");
        assertThat(output).contains("\"openid\":\"***-123\"");
        assertThat(output).contains("\"session_key\":\"***\"");
        assertThat(output).contains("elapsedMs=");
        assertThat(output).doesNotContain("secret-for-hmac");
        assertThat(output).doesNotContain("wx-code");
        assertThat(output).doesNotContain("openid-123");
        assertThat(output).doesNotContain("session-key");
        server.verify();
    }

    @Test
    void exchangePhoneCodeMasksSensitiveRemoteRequestAndResponseLogs(CapturedOutput output) throws Exception {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        RestWechatMiniappClient client = createClient(properties(), builder.build());
        server.expect(requestTo(
                        "https://api.weixin.qq.com/cgi-bin/token?grant_type=client_credential&appid=wxa-test&secret=secret-for-hmac"
                ))
                .andRespond(withSuccess(
                        "{\"access_token\":\"token-123\",\"expires_in\":7200}",
                        MediaType.APPLICATION_JSON
                ));
        server.expect(requestTo(
                        "https://api.weixin.qq.com/wxa/business/getuserphonenumber?access_token=token-123"
                ))
                .andExpect(content().json("{\"code\":\"phone-code\"}"))
                .andRespond(withSuccess(
                        "{\"errcode\":0,\"phone_info\":{\"phoneNumber\":\"+8613812348000\",\"purePhoneNumber\":\"13812348000\",\"countryCode\":\"86\"}}",
                        MediaType.APPLICATION_JSON
                ));

        WechatPhoneNumberResponse.PhoneInfo phoneInfo = client.exchangePhoneCode("phone-code");

        assertThat(phoneInfo.getPhoneNumber()).isEqualTo("+8613812348000");
        assertThat(output).contains("微信交互请求 operation=微信手机号服务");
        assertThat(output).contains("method=POST");
        assertThat(output).contains("path=https://api.weixin.qq.com/wxa/business/getuserphonenumber?access_token=***");
        assertThat(output).contains("request={\"code\":\"***\"}");
        assertThat(output).contains("微信交互响应 operation=微信手机号服务");
        assertThat(output).contains("httpStatus=200");
        assertThat(output).contains("\"phoneNumber\":\"***8000\"");
        assertThat(output).contains("elapsedMs=");
        assertThat(output).doesNotContain("access_token=token-123");
        assertThat(output).doesNotContain("phone-code");
        assertThat(output).doesNotContain("+8613812348000");
        server.verify();
    }

    @Test
    void exchangePhoneCodeReusesCachedAccessToken() throws Exception {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        RestWechatMiniappClient client = createClient(properties(), builder.build());
        server.expect(requestTo(
                        "https://api.weixin.qq.com/cgi-bin/token?grant_type=client_credential&appid=wxa-test&secret=secret-for-hmac"
                ))
                .andRespond(withSuccess(
                        "{\"access_token\":\"token-123\",\"expires_in\":7200}",
                        MediaType.APPLICATION_JSON
                ));
        server.expect(requestTo(
                        "https://api.weixin.qq.com/wxa/business/getuserphonenumber?access_token=token-123"
                ))
                .andExpect(content().json("{\"code\":\"phone-code-a\"}"))
                .andRespond(withSuccess(
                        "{\"errcode\":0,\"phone_info\":{\"phoneNumber\":\"+8613812348000\",\"purePhoneNumber\":\"13812348000\",\"countryCode\":\"86\"}}",
                        MediaType.APPLICATION_JSON
                ));
        server.expect(requestTo(
                        "https://api.weixin.qq.com/wxa/business/getuserphonenumber?access_token=token-123"
                ))
                .andExpect(content().json("{\"code\":\"phone-code-b\"}"))
                .andRespond(withSuccess(
                        "{\"errcode\":0,\"phone_info\":{\"phoneNumber\":\"+8613812348001\",\"purePhoneNumber\":\"13812348001\",\"countryCode\":\"86\"}}",
                        MediaType.APPLICATION_JSON
                ));

        WechatPhoneNumberResponse.PhoneInfo firstPhoneInfo = client.exchangePhoneCode("phone-code-a");
        WechatPhoneNumberResponse.PhoneInfo secondPhoneInfo = client.exchangePhoneCode("phone-code-b");

        assertThat(firstPhoneInfo.getPhoneNumber()).isEqualTo("+8613812348000");
        assertThat(secondPhoneInfo.getPhoneNumber()).isEqualTo("+8613812348001");
        server.verify();
    }

    @Test
    void exchangePhoneCodeReportsWechatHttpStatusErrors() throws Exception {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        RestWechatMiniappClient client = createClient(properties(), builder.build());
        server.expect(requestTo(
                        "https://api.weixin.qq.com/cgi-bin/token?grant_type=client_credential&appid=wxa-test&secret=secret-for-hmac"
                ))
                .andRespond(withSuccess(
                        "{\"access_token\":\"token-123\",\"expires_in\":7200}",
                        MediaType.APPLICATION_JSON
                ));
        server.expect(requestTo(
                        "https://api.weixin.qq.com/wxa/business/getuserphonenumber?access_token=token-123"
                ))
                .andRespond(withStatus(HttpStatus.PRECONDITION_FAILED));

        assertThatThrownBy(() -> client.exchangePhoneCode("phone-code"))
                .isInstanceOf(BusinessException.class)
                .hasMessage("微信手机号服务请求失败：HTTP 412 Precondition Failed");

        server.verify();
    }

    private void injectRestClient(RestWechatMiniappClient client, RestClient restClient) throws Exception {
        Field field = RestWechatMiniappClient.class.getDeclaredField("restClient");
        field.setAccessible(true);
        field.set(client, restClient);
    }

    /**
     * 使用同一个模拟 REST 客户端构造凭证获取器和小程序客户端。
     *
     * @param properties 微信配置
     * @param restClient 模拟 REST 客户端
     * @return 小程序客户端
     */
    private RestWechatMiniappClient createClient(
            WechatMiniappProperties properties,
            RestClient restClient
    ) throws Exception {
        RestWechatAccessTokenFetcher fetcher = new RestWechatAccessTokenFetcher(
                properties, new WechatInteractionLogSanitizer());
        Field fetcherRestClient = RestWechatAccessTokenFetcher.class.getDeclaredField("restClient");
        fetcherRestClient.setAccessible(true);
        fetcherRestClient.set(fetcher, restClient);
        WechatAccessTokenService tokenService = new WechatAccessTokenService(
                properties,
                new LocalCacheService(new LocalCacheProperties()),
                fetcher,
                new TestDistributedLockExecutor()
        );
        RestWechatMiniappClient client = new RestWechatMiniappClient(
                properties, tokenService, new WechatInteractionLogSanitizer());
        injectRestClient(client, restClient);
        return client;
    }

    /**
     * 构造 verbose 模式配置，用于验证详细日志仍会基础脱敏。
     *
     * @return 微信小程序配置
     */
    private WechatMiniappProperties properties() {
        WechatMiniappProperties properties = new WechatMiniappProperties();
        properties.setAppId("wxa-test");
        properties.setAppSecret("secret-for-hmac");
        properties.setLogVerbose(true);
        return properties;
    }

    /**
     * 构造非 verbose 模式配置，用于验证脱敏行为。
     *
     * @return 微信小程序配置
     */
    private WechatMiniappProperties nonVerboseProperties() {
        WechatMiniappProperties properties = new WechatMiniappProperties();
        properties.setAppId("wxa-test");
        properties.setAppSecret("secret-for-hmac");
        properties.setLogVerbose(false);
        return properties;
    }

    /**
     * 非 verbose 模式下，日志中 access_token 和手机号应被脱敏。
     */
    @Test
    void masksSensitiveFieldsInLogsWhenNotVerbose(CapturedOutput output) throws Exception {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        RestWechatMiniappClient client = createClient(nonVerboseProperties(), builder.build());
        server.expect(requestTo(
                        "https://api.weixin.qq.com/cgi-bin/token?grant_type=client_credential&appid=wxa-test&secret=secret-for-hmac"
                ))
                .andRespond(withSuccess(
                        "{\"access_token\":\"token-123\",\"expires_in\":7200}",
                        MediaType.APPLICATION_JSON
                ));
        server.expect(requestTo(
                        "https://api.weixin.qq.com/wxa/business/getuserphonenumber?access_token=token-123"
                ))
                .andExpect(content().json("{\"code\":\"phone-code\"}"))
                .andRespond(withSuccess(
                        "{\"errcode\":0,\"phone_info\":{\"phoneNumber\":\"+8613812348000\",\"purePhoneNumber\":\"13812348000\",\"countryCode\":\"86\"}}",
                        MediaType.APPLICATION_JSON
                ));

        client.exchangePhoneCode("phone-code");

        // 凭证获取器不记录 secret，即使脱敏后也不输出配置密钥
        assertThat(output).doesNotContain("secret-for-hmac");
        // access_token 完全脱敏
        assertThat(output).contains("access_token=***");
        assertThat(output).doesNotContain("access_token=token-123");
        // 一次性授权 code 脱敏
        assertThat(output).contains("request={\"code\":\"***\"}");
        assertThat(output).doesNotContain("phone-code");
        // 手机号脱敏
        assertThat(output).doesNotContain("+8613812348000");
        assertThat(output).doesNotContain("13812348000");
        assertThat(output).contains("\"phoneNumber\":\"***8000\"");
        assertThat(output).contains("\"purePhoneNumber\":\"***8000\"");
        assertThat(output).contains("\"countryCode\":\"***\"");
        server.verify();
    }
}
