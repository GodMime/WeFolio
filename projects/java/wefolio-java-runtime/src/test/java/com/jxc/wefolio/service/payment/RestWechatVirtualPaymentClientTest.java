package com.jxc.wefolio.service.payment;

import com.jxc.wefolio.config.WechatMiniappProperties;
import com.jxc.wefolio.config.WechatVirtualPaymentProperties;
import com.jxc.wefolio.service.WechatAccessTokenService;
import com.jxc.wefolio.service.WechatInteractionLogSanitizer;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.http.MediaType;
import org.springframework.http.HttpStatus;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import java.lang.reflect.Field;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;

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

    /** 无 errcode、非整数、负数或缺失余额都不能作为成功零余额。 */
    @ParameterizedTest
    @ValueSource(strings = {
            "{}", "null", "invalid-json", "{\"errcode\":null}",
            "{\"errcode\":0}", "{\"errcode\":0,\"balance\":1}",
            "{\"errcode\":0,\"balance\":-1,\"present_balance\":0}",
            "{\"errcode\":0,\"balance\":1.5,\"present_balance\":0}",
            "{\"errcode\":0,\"balance\":\"1\",\"present_balance\":0}",
            "{\"errcode\":0,\"balance\":true,\"present_balance\":0}",
            "{\"errcode\":0,\"balance\":1,\"present_balance\":2}",
            "{\"errcode\":0,\"balance\":9223372036854775808,\"present_balance\":0}",
            "{\"errcode\":0.1,\"balance\":0,\"present_balance\":0}"
    })
    void shouldRejectMalformedSuccessfulBalance(String response) throws Exception {
        assertThat(invokeWithResponse(response, false).errorType())
                .isEqualTo(WechatVirtualPaymentErrorType.UNKNOWN);
    }

    /** 明确的零余额合法；重复成功即使没有余额也必须保留原分类。 */
    @Test
    void shouldPreserveZeroBalanceAndDuplicateSuccess() throws Exception {
        assertThat(invokeWithResponse("{\"errcode\":0,\"balance\":0,\"present_balance\":0}", false)
                .errorType()).isEqualTo(WechatVirtualPaymentErrorType.SUCCESS);
        assertThat(invokeWithResponse("{\"errcode\":268490004}", true).errorType())
                .isEqualTo(WechatVirtualPaymentErrorType.DUPLICATE_SUCCESS);
    }

    /** 普通扣币成功必须返回赠送消耗字段，缺少时保留待恢复状态。 */
    @Test
    void shouldRejectPaySuccessWithoutUsedPresentAmount() throws Exception {
        assertThat(invokeWithResponse("{\"errcode\":0,\"balance\":1,\"present_balance\":0}", true)
                .errorType()).isEqualTo(WechatVirtualPaymentErrorType.UNKNOWN);
    }

    /** 官方及生产真实扣币成功形状没有 present_balance，不能因此被误判为 UNKNOWN。 */
    @Test
    void shouldAcceptPaySuccessWithoutPresentBalance() throws Exception {
        WechatVirtualPaymentResult result = invokeWithResponse(
                "{\"errcode\":0,\"order_id\":\"task\",\"balance\":90,\"used_present_amount\":3}", true);

        assertThat(result.errorType()).isEqualTo(WechatVirtualPaymentErrorType.SUCCESS);
        assertThat(result.balance()).isEqualTo(90L);
        assertThat(result.usedPresentAmount()).isEqualTo(3L);
        // 未提供的赠送余额不作为结算依据，处理器必须使用随后补查的完整余额。
    }

    /** UNKNOWN 与解析异常都进入结构化错误日志和同一个累计计数，不携带身份或请求。 */
    @Test
    void shouldCountUnknownResponsesFromMissingCodeAndMalformedPayload(CapturedOutput output) throws Exception {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        WechatAccessTokenService tokenService = mock(WechatAccessTokenService.class);
        when(tokenService.getAccessToken()).thenReturn("test-token");
        RestWechatVirtualPaymentClient client = new RestWechatVirtualPaymentClient(
                miniappProperties(), virtualPaymentProperties(), tokenService,
                new WechatVirtualPaymentSigner(), new WechatVirtualPaymentErrorClassifier(),
                new WechatInteractionLogSanitizer());
        injectRestClient(client, builder.build());
        server.expect(request -> {}).andRespond(withSuccess("{}", MediaType.APPLICATION_JSON));
        server.expect(request -> {}).andRespond(withSuccess("invalid-json", MediaType.APPLICATION_JSON));
        WechatBalanceQueryRequest request = new WechatBalanceQueryRequest(
                7L, "query", "openid-test", "session-test", "127.0.0.1", 1720000000L);

        assertThat(client.queryUserBalance(request).errorType()).isEqualTo(WechatVirtualPaymentErrorType.UNKNOWN);
        assertThat(client.queryUserBalance(request).errorType()).isEqualTo(WechatVirtualPaymentErrorType.UNKNOWN);

        assertThat(output).contains("event=WECHAT_VIRTUAL_PAYMENT_RESPONSE_UNKNOWN operation=查询余额 path=/xpay/query_user_balance")
                .contains("unknownResponseCount=1", "unknownResponseCount=2")
                .doesNotContain("openid-test", "session-test", "test-token");
        server.verify();
    }

    /** 真实 HTTP 适配不能把带业务码的 4xx 异常转成普通重试。 */
    @ParameterizedTest
    @CsvSource({
            "400,268490006,INSUFFICIENT_BALANCE",
            "403,268490009,SESSION_INVALID",
            "409,268490004,DUPLICATE_SUCCESS",
            "429,268490006,RATE_LIMITED",
            "503,268490009,TRANSIENT"
    })
    void shouldPreserveHttpAndBusinessErrorClassification(
            int status, int errorCode, WechatVirtualPaymentErrorType expected
    ) throws Exception {
        assertThat(invokeWithResponse("{\"errcode\":" + errorCode + "}", true,
                HttpStatus.valueOf(status)).errorType()).isEqualTo(expected);
    }

    /** 官方查单结构的现网/沙箱编号正确转换，旧字段与缺失字段保持兼容。 */
    @ParameterizedTest
    @MethodSource("orderEnvironments")
    void shouldNormalizeOfficialOrderEnvironment(String environmentFields, Integer expected) throws Exception {
        String fields = environmentFields.isEmpty() ? "" : "," + environmentFields;
        WechatVirtualPaymentResult result = invokeOrderWithResponse(
                "{\"errcode\":0,\"order\":{\"order_id\":\"ORDER\",\"status\":2,\"paid_fee\":100"
                        + fields + "}}");

        assertThat(result.errorType()).isEqualTo(WechatVirtualPaymentErrorType.SUCCESS);
        assertThat(result.orderStatus()).isEqualTo("PAID");
        assertThat(result.payAmount()).isEqualTo(100L);
        assertThat(result.remoteOrderId()).isEqualTo("ORDER");
        assertThat(result.environment()).isEqualTo(expected);
    }

    /** 官方环境字段、旧环境字段及两者一致的输入组合。 */
    private static Stream<Arguments> orderEnvironments() {
        return Stream.of(
                Arguments.of("\"env_type\":1", 0),
                Arguments.of("\"env_type\":2", 1),
                Arguments.of("\"env\":0", 0),
                Arguments.of("\"env\":1", 1),
                Arguments.of("", null),
                Arguments.of("\"env_type\":null", null),
                Arguments.of("\"env_type\":null,\"env\":1", 1),
                Arguments.of("\"env_type\":0", null),
                Arguments.of("\"env_type\":0,\"env\":0", 0),
                Arguments.of("\"env_type\":1,\"env\":0", 0),
                Arguments.of("\"env_type\":2,\"env\":1", 1));
    }

    /** 环境值非法或新旧字段矛盾时不能把订单当作已验证成功。 */
    @ParameterizedTest
    @ValueSource(strings = {
            "\"env_type\":3", "\"env_type\":-1",
            "\"env_type\":1.5", "\"env_type\":\"1\"",
            "\"env_type\":1,\"env\":1", "\"env_type\":2,\"env\":0"
    })
    void shouldRejectInvalidOrConflictingOrderEnvironment(String fields) throws Exception {
        WechatVirtualPaymentResult result = invokeOrderWithResponse(
                "{\"errcode\":0,\"order\":{\"status\":2,\"paid_fee\":100," + fields + "}}");

        assertThat(result.errorType()).isEqualTo(WechatVirtualPaymentErrorType.UNKNOWN);
    }

    /** 官方初始化示例的全零占位保持待支付，不误判环境或拒绝成功查单。 */
    @Test
    void shouldAcceptOfficialInitialOrderWithUndeclaredEnvironment() throws Exception {
        WechatVirtualPaymentResult result = invokeOrderWithResponse(
                "{\"errcode\":0,\"errmsg\":\"\",\"order\":{\"order_id\":\"\","
                        + "\"status\":0,\"paid_fee\":0,\"env_type\":0}}");

        assertThat(result.errorType()).isEqualTo(WechatVirtualPaymentErrorType.SUCCESS);
        assertThat(result.orderStatus()).isEqualTo("PENDING");
        assertThat(result.payAmount()).isZero();
        assertThat(result.environment()).isNull();
    }

    /** 官方用户退款完成状态 8 与订单已退款状态 5 使用相同退款语义。 */
    @Test
    void shouldRecognizeOfficialUserRefundCompletedStatus() throws Exception {
        WechatVirtualPaymentResult result = invokeOrderWithResponse(
                "{\"errcode\":0,\"order\":{\"order_id\":\"ORDER\",\"status\":8,"
                        + "\"paid_fee\":100,\"env_type\":1}}");

        assertThat(result.errorType()).isEqualTo(WechatVirtualPaymentErrorType.SUCCESS);
        assertThat(result.orderStatus()).isEqualTo("REFUND");
        assertThat(result.environment()).isZero();
    }

    /** 通过实际查单 HTTP 入口读取协议响应，不直接调用私有解析方法。 */
    private WechatVirtualPaymentResult invokeOrderWithResponse(String response) throws Exception {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        WechatAccessTokenService tokenService = mock(WechatAccessTokenService.class);
        when(tokenService.getAccessToken()).thenReturn("test-token");
        RestWechatVirtualPaymentClient client = new RestWechatVirtualPaymentClient(
                miniappProperties(), virtualPaymentProperties(), tokenService,
                new WechatVirtualPaymentSigner(), new WechatVirtualPaymentErrorClassifier(),
                new WechatInteractionLogSanitizer());
        injectRestClient(client, builder.build());
        server.expect(request -> {}).andRespond(withSuccess(response, MediaType.APPLICATION_JSON));
        WechatVirtualPaymentResult result = client.queryOrder(new WechatQueryOrderRequest(
                7L, "ORDER", "openid", "session", "127.0.0.1", "ORDER", 1720000000L));
        server.verify();
        return result;
    }

    /** 通过模拟 HTTP 成功应答验证实际解析分支。 */
    private WechatVirtualPaymentResult invokeWithResponse(String response, boolean pay) throws Exception {
        return invokeWithResponse(response, pay, HttpStatus.OK);
    }

    /** 通过模拟原始 HTTP 状态和报文验证客户端分类，不绕过传输层。 */
    private WechatVirtualPaymentResult invokeWithResponse(
            String response, boolean pay, HttpStatus status
    ) throws Exception {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        WechatAccessTokenService tokenService = mock(WechatAccessTokenService.class);
        when(tokenService.getAccessToken()).thenReturn("test-token");
        RestWechatVirtualPaymentClient client = new RestWechatVirtualPaymentClient(
                miniappProperties(), virtualPaymentProperties(), tokenService,
                new WechatVirtualPaymentSigner(), new WechatVirtualPaymentErrorClassifier(),
                new WechatInteractionLogSanitizer());
        injectRestClient(client, builder.build());
        server.expect(request -> {}).andRespond(withStatus(status).body(response).contentType(MediaType.APPLICATION_JSON));
        WechatVirtualPaymentResult result = pay
                ? client.currencyPay(new WechatCurrencyPayRequest(7L, "task", "openid", "session",
                        "127.0.0.1", "task", 1L, 1720000000L))
                : client.queryUserBalance(new WechatBalanceQueryRequest(7L, "query", "openid",
                        "session", "127.0.0.1", 1720000000L));
        server.verify();
        return result;
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
