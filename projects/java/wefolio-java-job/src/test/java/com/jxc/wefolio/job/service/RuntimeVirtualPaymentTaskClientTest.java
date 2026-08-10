package com.jxc.wefolio.job.service;

import com.jxc.wefolio.job.config.AdminPointProperties;
import com.jxc.wefolio.job.config.RuntimeProperties;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.HttpServerErrorException;
import org.springframework.web.client.RestClient;

import java.net.http.HttpClient;
import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

/**
 * job 调用 runtime 微信虚拟支付单任务客户端测试。
 */
class RuntimeVirtualPaymentTaskClientTest {

    private static final String BASE_URL = "https://runtime.example.com";
    private static final String ADMIN_SECRET = "test-admin-secret";

    /** 专用客户端必须使用分发配置的连接和读取超时。 */
    @Test
    void requestFactoryShouldUseDispatchTimeout() {
        JdkClientHttpRequestFactory factory = RuntimeVirtualPaymentTaskClient
                .createRequestFactory(Duration.ofSeconds(30));
        HttpClient httpClient = (HttpClient) ReflectionTestUtils.getField(factory, "httpClient");

        assertThat(httpClient).isNotNull();
        assertThat(httpClient.connectTimeout()).contains(Duration.ofSeconds(30));
        assertThat(ReflectionTestUtils.getField(factory, "readTimeout"))
                .isEqualTo(Duration.ofSeconds(30));
    }

    /** 三类请求必须使用固定路径和后台密钥，并正确解析响应。 */
    @Test
    void requestsShouldUseStablePathsAndSecretHeader() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        server.expect(requestTo(BASE_URL
                        + "/api/admin/points/virtual-payment/gift-orders/17/executions"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(header("X-Admin-Point-Secret", ADMIN_SECRET))
                .andRespond(withSuccess(executionBody(17L, "GIFT_ORDER", "PROCESSED"),
                        MediaType.APPLICATION_JSON));
        server.expect(requestTo(BASE_URL
                        + "/api/admin/points/virtual-payment/debit-tasks/23/executions"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(header("X-Admin-Point-Secret", ADMIN_SECRET))
                .andRespond(withSuccess(executionBody(
                        23L, "DEBIT_TASK", "SKIPPED_NOT_CLAIMABLE"), MediaType.APPLICATION_JSON));
        server.expect(requestTo(BASE_URL
                        + "/api/admin/points/virtual-payment/users/7/debit-task-recoveries"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(header("X-Admin-Point-Secret", ADMIN_SECRET))
                .andRespond(withSuccess("""
                        {"success":true,"data":{"userId":7,"taskId":31,"outcome":"ACTIVE_TASK_ENSURED"}}
                        """, MediaType.APPLICATION_JSON));

        RuntimeVirtualPaymentTaskClient client = client(builder.build());

        assertThat(client.executeGiftOrder(17L).getOutcome()).isEqualTo("PROCESSED");
        assertThat(client.executeDebitTask(23L).getOutcome()).isEqualTo("SKIPPED_NOT_CLAIMABLE");
        assertThat(client.recoverDebitTask(7L).getTaskId()).isEqualTo(31L);
        server.verify();
    }

    /** 5xx 后不得在单次方法内部重试。 */
    @Test
    void executeShouldSendOnlyOneRequestAfterServerFailure() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        server.expect(requestTo(BASE_URL
                        + "/api/admin/points/virtual-payment/gift-orders/17/executions"))
                .andRespond(withServerError());

        assertThatThrownBy(() -> client(builder.build()).executeGiftOrder(17L))
                .isInstanceOf(HttpServerErrorException.class);
        server.verify();
    }

    /** 非 HTTPS runtime 地址必须在发请求前拒绝。 */
    @Test
    void requestShouldRejectNonHttpsRuntimeAddress() {
        RuntimeProperties runtimeProperties = new RuntimeProperties();
        runtimeProperties.setBaseUrl("http://runtime.example.com");

        RuntimeVirtualPaymentTaskClient client = new RuntimeVirtualPaymentTaskClient(
                runtimeProperties, adminProperties(), RestClient.create());

        assertThatThrownBy(() -> client.executeGiftOrder(17L))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("HTTPS");
    }

    /** 创建使用可控 RestClient 的客户端。 */
    private RuntimeVirtualPaymentTaskClient client(RestClient restClient) {
        RuntimeProperties runtimeProperties = new RuntimeProperties();
        runtimeProperties.setBaseUrl(BASE_URL);
        return new RuntimeVirtualPaymentTaskClient(runtimeProperties, adminProperties(), restClient);
    }

    /** 构造后台密钥配置。 */
    private AdminPointProperties adminProperties() {
        AdminPointProperties properties = new AdminPointProperties();
        properties.setSecret(ADMIN_SECRET);
        return properties;
    }

    /** 构造任务执行响应 JSON。 */
    private String executionBody(long targetId, String taskType, String outcome) {
        return """
                {"success":true,"data":{"targetId":%d,"taskType":"%s","outcome":"%s"}}
                """.formatted(targetId, taskType, outcome);
    }
}
