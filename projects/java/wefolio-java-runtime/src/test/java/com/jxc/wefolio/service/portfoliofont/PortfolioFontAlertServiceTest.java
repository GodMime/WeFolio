package com.jxc.wefolio.service.portfoliofont;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;
import com.jxc.wefolio.config.PortfolioFontProperties;
import org.apache.hc.client5.http.classic.methods.HttpPost;
import org.apache.hc.client5.http.config.Configurable;
import org.apache.hc.client5.http.config.RequestConfig;
import org.apache.hc.client5.http.impl.classic.CloseableHttpClient;
import org.apache.hc.client5.http.impl.classic.HttpRequestRetryExec;
import org.apache.hc.client5.http.impl.classic.MainClientExec;
import org.apache.hc.client5.http.impl.classic.RedirectExec;
import org.apache.hc.core5.http.ContentType;
import org.apache.hc.core5.http.io.HttpClientResponseHandler;
import org.apache.hc.core5.http.io.entity.EntityUtils;
import org.apache.hc.core5.http.io.entity.StringEntity;
import org.apache.hc.core5.http.message.BasicClassicHttpResponse;
import org.apache.hc.core5.util.Timeout;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.stubbing.Answer;
import org.springframework.test.util.ReflectionTestUtils;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

/** 通过内存 HTTP 传输验证告警协议与签名，不监听端口、不访问网络。 */
class PortfolioFontAlertServiceTest {
    /** 仅用于构造请求的保留测试域名，由内存客户端拦截。 */
    private static final String WEBHOOK_URL = "https://alerts.test.invalid/notify";
    /** 只在本测试中使用的签名密钥。 */
    private static final String WEBHOOK_SECRET = "local-test-secret";
    /** 测试节点标识。 */
    private static final String NODE_ID = "node-test";
    /** 测试构建标识。 */
    private static final String BUILD_ID = "test-build";
    /** 飞书签名算法。 */
    private static final String HMAC_ALGORITHM = "HmacSHA256";
    /** 机器人确认送达的响应。 */
    private static final String SUCCESS_RESPONSE = "{\"code\":0}";
    /** 机器人拒绝送达的响应。 */
    private static final String REJECTED_RESPONSE = "{\"code\":123}";

    /** 模拟传输，只执行生产代码的响应处理器。 */
    private CloseableHttpClient client;
    /** 仅检查配置的真实客户端，不通过它执行请求。 */
    private CloseableHttpClient configuredClient;
    /** 独立的测试告警配置。 */
    private PortfolioFontProperties properties;
    /** 使用内存客户端的待测服务。 */
    private PortfolioFontAlertService service;

    /** 构造后立即替换传输，真实客户端只用于检查配置，绝不执行请求。 */
    @BeforeEach
    void setUp() {
        client = mock(CloseableHttpClient.class);
        properties = new PortfolioFontProperties();
        properties.getAlert().setEnabled(true);
        properties.getAlert().setWebhookUrl(WEBHOOK_URL);
        properties.getAlert().setWebhookSecret(WEBHOOK_SECRET);
        properties.getAlert().setNodeId(NODE_ID);
        service = new PortfolioFontAlertService(properties);
        configuredClient = (CloseableHttpClient) ReflectionTestUtils.getField(service, "client");
        ReflectionTestUtils.setField(service, "client", client);
    }

    /** 关闭服务及只用于配置检查的客户端，防止影响后续测试。 */
    @AfterEach
    void tearDown() throws Exception {
        try {
            if (service != null) {
                service.close();
            }
        } finally {
            if (configuredClient != null) {
                configuredClient.close();
            }
        }
    }

    /** 签名与真实序列化 JSON 按飞书约定发送，接口确认成功才报告送达。 */
    @Test
    void sendsSignedNotificationOnceThroughInMemoryTransport() throws Exception {
        when(client.execute(any(HttpPost.class), any(HttpClientResponseHandler.class)))
                .thenAnswer(response(200, SUCCESS_RESPONSE));

        assertThat(service.notifyFailure(BUILD_ID, PortfolioFontSources.Failure.SOURCE_UNAVAILABLE.name())).isTrue();

        var requestCaptor = ArgumentCaptor.forClass(HttpPost.class);
        verify(client).execute(requestCaptor.capture(), any(HttpClientResponseHandler.class));
        verifyNoMoreInteractions(client);
        HttpPost request = requestCaptor.getValue();
        assertThat(request.getUri().toString()).isEqualTo(WEBHOOK_URL);
        assertThat(ContentType.parse(request.getEntity().getContentType()).getMimeType())
                .isEqualTo(ContentType.APPLICATION_JSON.getMimeType());
        JSONObject payload = JSON.parseObject(EntityUtils.toString(request.getEntity()));
        assertThat(payload.getString("msg_type")).isEqualTo("text");
        assertThat(payload.getJSONObject("content").getString("text"))
                .contains(NODE_ID, BUILD_ID, PortfolioFontSources.Failure.SOURCE_UNAVAILABLE.name());
        Mac mac = Mac.getInstance(HMAC_ALGORITHM);
        mac.init(new SecretKeySpec((payload.getString("timestamp") + "\n" + WEBHOOK_SECRET)
                .getBytes(StandardCharsets.UTF_8), HMAC_ALGORITHM));
        assertThat(payload.getString("sign"))
                .isEqualTo(Base64.getEncoder().encodeToString(mac.doFinal(new byte[0])));
    }

    /** HTTP 拒绝、机器人拒绝及重定向都只调用一次传输；关闭开关后没有投递。 */
    @Test
    void rejectedOrDisabledNotificationsNeverRetry() throws Exception {
        when(client.execute(any(HttpPost.class), any(HttpClientResponseHandler.class)))
                .thenAnswer(response(302, SUCCESS_RESPONSE))
                .thenAnswer(response(503, SUCCESS_RESPONSE))
                .thenAnswer(response(200, REJECTED_RESPONSE));

        assertThat(service.notifyFailure(BUILD_ID, PortfolioFontSources.Failure.TOOL_UNAVAILABLE.name())).isFalse();
        verify(client).execute(any(HttpPost.class), any(HttpClientResponseHandler.class));
        assertThat(service.notifyFailure(BUILD_ID, PortfolioFontSources.Failure.SOURCE_UNAVAILABLE.name())).isFalse();
        verify(client, times(2)).execute(any(HttpPost.class), any(HttpClientResponseHandler.class));
        assertThat(service.notifyFailure(BUILD_ID, PortfolioFontSources.Failure.SELF_TEST_FAILED.name())).isFalse();
        verify(client, times(3)).execute(any(HttpPost.class), any(HttpClientResponseHandler.class));
        properties.getAlert().setEnabled(false);
        assertThat(service.notifyFailure(BUILD_ID, PortfolioFontSources.Failure.TOOL_UNAVAILABLE.name())).isFalse();
        verifyNoMoreInteractions(client);
    }

    /** 验证真实构造流程禁用自动重试和重定向，并保留各网络阶段的超时上限。 */
    @Test
    @SuppressWarnings("deprecation")
    void clientConfigurationDisablesRetriesAndRedirectsAndBoundsTimeouts() {
        RequestConfig config = ((Configurable) configuredClient).getConfig();
        assertThat(config.getConnectionRequestTimeout()).isEqualTo(Timeout.ofMilliseconds(300));
        assertThat(config.getConnectTimeout()).isEqualTo(Timeout.ofMilliseconds(300));
        assertThat(config.getResponseTimeout()).isEqualTo(Timeout.ofMilliseconds(500));
        // 检查实际执行链，防止传输 mock 掩盖未来误开启自动重试或重定向。
        List<Object> handlers = new ArrayList<>();
        Object element = ReflectionTestUtils.getField(configuredClient, "execChain");
        while (element != null) {
            handlers.add(ReflectionTestUtils.getField(element, "handler"));
            element = ReflectionTestUtils.getField(element, "next");
        }
        assertThat(handlers).anyMatch(MainClientExec.class::isInstance)
                .noneMatch(HttpRequestRetryExec.class::isInstance)
                .noneMatch(RedirectExec.class::isInstance);
        verifyNoInteractions(client);
    }

    /** 用真实 HTTP 响应对象执行生产响应解析逻辑，仅替换底层网络传输。 */
    private static Answer<Boolean> response(int statusCode, String body) {
        return invocation -> {
            HttpClientResponseHandler<Boolean> handler = invocation.getArgument(1);
            try (var response = new BasicClassicHttpResponse(statusCode)) {
                response.setHeader("Location", "/unexpected");
                response.setEntity(new StringEntity(body, ContentType.APPLICATION_JSON));
                return handler.handleResponse(response);
            }
        };
    }
}
