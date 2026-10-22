package com.jxc.wefolio.service;

import com.jxc.wefolio.config.WechatMobileProperties;
import com.jxc.wefolio.message.WechatMobileMessage;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;
import static org.hamcrest.Matchers.startsWith;

/** 移动应用微信接口契约及敏感日志回归测试。 */
@ExtendWith(OutputCaptureExtension.class)
class RestWechatMobileClientTest {
    /** 只连接移动应用 OAuth 地址，并隐藏所有授权凭证。 */
    @Test
    void exchangesMobileCodeAndRedactsCredentials(CapturedOutput output) {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        server.expect(requestTo("https://api.weixin.qq.com/sns/oauth2/access_token?appid=wx-test-mobile&secret=private-secret&code=one-time-code&grant_type=authorization_code"))
                .andRespond(withSuccess("{\"openid\":\"mobile-open-id\",\"access_token\":\"private-token\",\"unionid\":\"verified-union\"}", MediaType.TEXT_PLAIN));
        assertThat(new RestWechatMobileClient(properties(), builder.build()).exchangeUnionId("one-time-code"))
                .isEqualTo("verified-union");
        assertThat(output).doesNotContain("private-secret", "one-time-code", "private-token", "mobile-open-id", "verified-union");
        server.verify();
    }

    /** 外部错误或缺少身份必须拒绝，不接受不完整成功响应。 */
    @Test
    void rejectsWechatErrorsAndIncompleteResponses() {
        for (String response : new String[]{"{\"errcode\":40029}", "{}", "not-json",
                "{\"unionid\":\"untrusted-only\"}"}) {
            RestClient.Builder builder = RestClient.builder();
            MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
            server.expect(requestTo(startsWith("https://api.weixin.qq.com/sns/oauth2/access_token")))
                    .andRespond(withSuccess(response, MediaType.TEXT_PLAIN));
            assertThatThrownBy(() -> new RestWechatMobileClient(properties(), builder.build()).exchangeUnionId("code"))
                    .hasMessage(WechatMobileMessage.EXCHANGE_FAILED);
            server.verify();
        }
    }

    /** URI 异常中即使带有完整密钥，也不能出现在响应或日志中。 */
    @Test
    void exceptionsCannotLeakRequestUri(CapturedOutput output) {
        RestClient client = RestClient.builder().requestFactory((uri, method) -> {
            throw new IllegalArgumentException("Rejected " + uri);
        }).build();
        assertThatThrownBy(() -> new RestWechatMobileClient(properties(), client).exchangeUnionId("sensitive-code"))
                .hasMessage(WechatMobileMessage.EXCHANGE_FAILED).hasNoCause();
        assertThat(output).doesNotContain("private-secret", "sensitive-code");
    }

    /** 创建仅用于模拟请求的假配置。 */
    private WechatMobileProperties properties() {
        WechatMobileProperties result = new WechatMobileProperties();
        result.setEnabled(true); result.setAppId("wx-test-mobile"); result.setAppSecret("private-secret");
        return result;
    }
}
