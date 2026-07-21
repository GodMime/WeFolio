package com.jxc.wefolio.service.payment;

import com.jxc.wefolio.config.WechatMiniappProperties;
import com.jxc.wefolio.config.WechatVirtualPaymentProperties;
import com.jxc.wefolio.service.WechatAccessTokenService;
import com.jxc.wefolio.service.WechatInteractionLogSanitizer;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import java.lang.reflect.Field;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

/**
 * 微信虚拟支付 REST 客户端测试。
 */
@ExtendWith(OutputCaptureExtension.class)
class RestWechatVirtualPaymentClientTest {

    /** 微信查单状态 2 表示已支付，应标准化为本地可识别的支付成功状态。 */
    @Test
    void shouldNormalizeWechatPaidOrderStatus(CapturedOutput output) throws Exception {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        WechatAccessTokenService tokenService = mock(WechatAccessTokenService.class);
        when(tokenService.getAccessToken()).thenReturn("token-test");
        RestWechatVirtualPaymentClient client = new RestWechatVirtualPaymentClient(
                miniappProperties(),
                virtualPaymentProperties(),
                tokenService,
                new WechatVirtualPaymentSigner(),
                new WechatVirtualPaymentErrorClassifier(),
                new WechatInteractionLogSanitizer()
        );
        injectRestClient(client, builder.build());
        String body = "{\"appid\":\"wxa-test\",\"offer_id\":\"offer-test\","
                + "\"openid\":\"openid-test\",\"ts\":1720000000,\"zone_id\":\"1\","
                + "\"env\":0,\"user_ip\":\"127.0.0.1\",\"order_id\":\"WFR202607190001\"}";
        String paySignature = new WechatVirtualPaymentSigner().paySignature(
                "app-key-test", "/xpay/query_order", body);
        String userSignature = new WechatVirtualPaymentSigner().userSignature("session-test", body);
        server.expect(requestTo("https://api.weixin.qq.com/xpay/query_order?access_token=token-test"
                        + "&pay_sig=" + paySignature + "&signature=" + userSignature))
                .andExpect(content().string(body))
                .andRespond(withSuccess(
                        "{\"errcode\":0,\"errmsg\":\"ok\",\"order\":{"
                                + "\"order_id\":\"WFR202607190001\",\"status\":2,"
                                + "\"paid_fee\":100,\"wx_order_id\":\"WXORDER001\"}}",
                        MediaType.APPLICATION_JSON));

        WechatVirtualPaymentResult result = client.queryOrder(new WechatQueryOrderRequest(
                7L, "WFR202607190001", "openid-test", "session-test", "127.0.0.1",
                "WFR202607190001", 1720000000L));

        assertThat(result.orderStatus()).isEqualTo("PAID");
        assertThat(result.payAmount()).isEqualTo(100L);
        assertThat(result.remoteOrderId()).isEqualTo("WFR202607190001");
        assertThat(output).contains("微信交互请求 operation=查询订单");
        assertThat(output).contains("referenceNo=WFR202607190001 userId=7 method=POST path=/xpay/query_order");
        assertThat(output).contains("\"openid\":\"***test\"");
        assertThat(output).contains("微信交互响应 operation=查询订单");
        assertThat(output).contains("httpStatus=200 response=");
        assertThat(output).contains("elapsedMs=");
        assertThat(output).contains("retryCount=0");
        assertThat(output).doesNotContain("openid-test");
        assertThat(output).doesNotContain("session-test");
        assertThat(output).doesNotContain("token-test");
        server.verify();
    }

    /** AccessToken 失效时应条件刷新，并使用完全相同正文原样重放一次。 */
    @Test
    void shouldRefreshRejectedAccessTokenAndReplaySameRequestOnce(CapturedOutput output) throws Exception {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        WechatAccessTokenService tokenService = mock(WechatAccessTokenService.class);
        when(tokenService.getAccessToken()).thenReturn("token-old");
        when(tokenService.refreshAfterRejected("token-old")).thenReturn("token-new");
        RestWechatVirtualPaymentClient client = new RestWechatVirtualPaymentClient(
                miniappProperties(),
                virtualPaymentProperties(),
                tokenService,
                new WechatVirtualPaymentSigner(),
                new WechatVirtualPaymentErrorClassifier(),
                new WechatInteractionLogSanitizer()
        );
        injectRestClient(client, builder.build());
        String body = "{\"appid\":\"wxa-test\",\"offer_id\":\"offer-test\",\"openid\":\"openid-test\","
                + "\"ts\":1720000000,\"zone_id\":\"1\",\"env\":0,\"user_ip\":\"127.0.0.1\",\"amount\":10,"
                + "\"order_id\":\"WXORDER001\",\"currency_type\":\"CNY\"}";
        String paySignature = new WechatVirtualPaymentSigner().paySignature(
                "app-key-test", "/xpay/currency_pay", body);
        String userSignature = new WechatVirtualPaymentSigner().userSignature("session-test", body);
        server.expect(requestTo("https://api.weixin.qq.com/xpay/currency_pay?access_token=token-old"
                        + "&pay_sig=" + paySignature + "&signature=" + userSignature))
                .andExpect(content().string(body))
                .andRespond(withSuccess("{\"errcode\":40014,\"errmsg\":\"invalid token\"}", MediaType.APPLICATION_JSON));
        server.expect(requestTo("https://api.weixin.qq.com/xpay/currency_pay?access_token=token-new"
                        + "&pay_sig=" + paySignature + "&signature=" + userSignature))
                .andExpect(content().string(body))
                .andRespond(withSuccess(
                        "{\"errcode\":0,\"balance\":90,\"present_balance\":15,\"used_present_amount\":3}",
                        MediaType.APPLICATION_JSON));

        WechatVirtualPaymentResult result = client.currencyPay(new WechatCurrencyPayRequest(
                7L, "TASK001", "openid-test", "session-test", "127.0.0.1",
                "WXORDER001", 10L, 1720000000L));

        assertThat(result.errorType()).isEqualTo(WechatVirtualPaymentErrorType.SUCCESS);
        assertThat(result.balance()).isEqualTo(90L);
        assertThat(result.presentBalance()).isEqualTo(15L);
        assertThat(result.usedPresentAmount()).isEqualTo(3L);
        assertThat(output).contains("微信交互响应 operation=扣减代币");
        assertThat(output).contains("retryCount=0");
        assertThat(output).contains("retryCount=1");
        assertThat(output).doesNotContain("token-old");
        assertThat(output).doesNotContain("token-new");
        assertThat(output).doesNotContain("session-test");
        verify(tokenService).refreshAfterRejected("token-old");
        server.verify();
    }

    /** 注入与模拟服务器绑定的客户端。 */
    private void injectRestClient(RestWechatVirtualPaymentClient client, RestClient restClient) throws Exception {
        Field field = RestWechatVirtualPaymentClient.class.getDeclaredField("restClient");
        field.setAccessible(true);
        field.set(client, restClient);
    }

    /** @return 微信小程序测试配置。 */
    private WechatMiniappProperties miniappProperties() {
        WechatMiniappProperties properties = new WechatMiniappProperties();
        properties.setAppId("wxa-test");
        properties.setAppSecret("secret-test");
        return properties;
    }

    /** @return 虚拟支付测试配置。 */
    private WechatVirtualPaymentProperties virtualPaymentProperties() {
        WechatVirtualPaymentProperties properties = new WechatVirtualPaymentProperties();
        properties.setOfferId("offer-test");
        properties.setAppKey("app-key-test");
        return properties;
    }
}
