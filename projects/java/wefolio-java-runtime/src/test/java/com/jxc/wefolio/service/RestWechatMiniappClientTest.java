package com.jxc.wefolio.service;

import com.jxc.wefolio.config.WechatMiniappProperties;
import com.jxc.wefolio.common.cache.LocalCacheService;
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
    void exchangeCodeParsesWechatTextPlainJsonResponse() throws Exception {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        RestWechatMiniappClient client = new RestWechatMiniappClient(properties(), new LocalCacheService());
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

    @Test
    void exchangePhoneCodeMasksSensitiveRemoteRequestAndResponseLogs(CapturedOutput output) throws Exception {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        RestWechatMiniappClient client = new RestWechatMiniappClient(properties(), new LocalCacheService());
        injectRestClient(client, builder.build());
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
        assertThat(output).contains("微信远端请求入参");
        assertThat(output).contains("serviceName=微信手机号服务");
        assertThat(output).contains("method=POST");
        assertThat(output).contains("url=https://api.weixin.qq.com/wxa/business/getuserphonenumber?access_token=tok***-123");
        assertThat(output).contains("requestBody={\"code\":\"pho***code\"}");
        assertThat(output).contains("微信远端响应出参");
        assertThat(output).contains("status=200");
        assertThat(output).contains("\"phoneNumber\":\"+86***8000\"");
        assertThat(output).doesNotContain("access_token=token-123");
        assertThat(output).doesNotContain("phone-code");
        assertThat(output).doesNotContain("+8613812348000");
        server.verify();
    }

    @Test
    void exchangePhoneCodeReusesCachedAccessToken() throws Exception {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        RestWechatMiniappClient client = new RestWechatMiniappClient(properties(), new LocalCacheService());
        injectRestClient(client, builder.build());
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
        RestWechatMiniappClient client = new RestWechatMiniappClient(properties(), new LocalCacheService());
        injectRestClient(client, builder.build());
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

    @Test
    void logMaskingFailureLogsStackTraceAndReturnsSafePlaceholder(CapturedOutput output) {
        RestWechatMiniappClient client = new RestWechatMiniappClient(properties(), new LocalCacheService());

        String maskedText = client.maskLogTextSafely(() -> {
            throw new IllegalStateException("mask boom");
        });

        assertThat(maskedText).isEqualTo("[日志脱敏失败]");
        assertThat(output).contains("微信日志脱敏失败");
        assertThat(output).contains("java.lang.IllegalStateException: mask boom");
    }

    private void injectRestClient(RestWechatMiniappClient client, RestClient restClient) throws Exception {
        Field field = RestWechatMiniappClient.class.getDeclaredField("restClient");
        field.setAccessible(true);
        field.set(client, restClient);
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
        RestWechatMiniappClient client = new RestWechatMiniappClient(nonVerboseProperties(), new LocalCacheService());
        injectRestClient(client, builder.build());
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

        // secret 无条件脱敏（access_token 获取的日志中也会包含 secret）
        assertThat(output).contains("secret=sec***hmac");
        // access_token 脱敏后保留头尾，便于比对
        assertThat(output).contains("access_token=tok***-123");
        assertThat(output).doesNotContain("access_token=token-123");
        // 一次性授权 code 脱敏
        assertThat(output).contains("requestBody={\"code\":\"pho***code\"}");
        assertThat(output).doesNotContain("phone-code");
        // 手机号脱敏
        assertThat(output).doesNotContain("+8613812348000");
        assertThat(output).doesNotContain("13812348000");
        assertThat(output).contains("\"phoneNumber\":\"+86***8000\"");
        assertThat(output).contains("\"purePhoneNumber\":\"138***8000\"");
        assertThat(output).contains("\"countryCode\":\"***\"");
        server.verify();
    }
}
