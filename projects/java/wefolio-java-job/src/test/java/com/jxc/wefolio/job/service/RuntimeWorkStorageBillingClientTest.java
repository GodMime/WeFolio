package com.jxc.wefolio.job.service;

import com.jxc.wefolio.job.config.AdminPointProperties;
import com.jxc.wefolio.job.config.RuntimeProperties;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.HttpServerErrorException;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;

import java.time.LocalDate;
import java.time.Duration;
import java.net.http.HttpClient;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withBadRequest;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

/**
 * Job 调用 runtime 月度作品存储结算客户端测试。
 */
class RuntimeWorkStorageBillingClientTest {

    /** runtime 测试地址。 */
    private static final String BASE_URL = "https://runtime.example.com";

    /** runtime 结算完整地址。 */
    private static final String SETTLEMENT_URL =
            BASE_URL + "/api/admin/points/work-storage-billing/settlements";

    /** 后台密钥。 */
    private static final String ADMIN_SECRET = "test-admin-secret";

    /** 成功响应。 */
    private static final String SUCCESS_BODY = """
            {
              "success": true,
              "data": {
                "userId": 7,
                "billingMonth": "2026-07",
                "status": "CHARGED",
                "pointsDue": 2,
                "pointsDeducted": 2,
                "idempotent": false
              }
            }
            """;

    @Test
    void requestFactoryShouldLimitConnectionAndWholeResponseToThreeSeconds() {
        JdkClientHttpRequestFactory factory =
                RuntimeWorkStorageBillingClient.createRequestFactory();
        HttpClient httpClient = (HttpClient) ReflectionTestUtils.getField(factory, "httpClient");

        assertThat(httpClient).isNotNull();
        assertThat(httpClient.connectTimeout()).contains(Duration.ofSeconds(3));
        assertThat(ReflectionTestUtils.getField(factory, "readTimeout")).isEqualTo(Duration.ofSeconds(3));
    }

    @Test
    void settleShouldRetryOneServerFailureThenReturnSecondResponse() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        server.expect(requestTo(SETTLEMENT_URL)).andExpect(method(HttpMethod.POST))
                .andExpect(header("X-Admin-Point-Secret", ADMIN_SECRET))
                .andRespond(withServerError());
        server.expect(requestTo(SETTLEMENT_URL)).andExpect(method(HttpMethod.POST))
                .andRespond(withSuccess(SUCCESS_BODY, MediaType.APPLICATION_JSON));

        RuntimeWorkStorageBillingClient.SettlementResult result =
                client(builder.build()).settle(7L, LocalDate.of(2026, 7, 1));

        assertThat(result.getUserId()).isEqualTo(7L);
        assertThat(result.getStatus()).isEqualTo("CHARGED");
        assertThat(result.getPointsDeducted()).isEqualTo(2L);
        server.verify();
    }

    @Test
    void settleShouldRetryOneNetworkFailureThenReturnSecondResponse() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        server.expect(requestTo(SETTLEMENT_URL))
                .andRespond(request -> {
                    throw new ResourceAccessException("读取超时");
                });
        server.expect(requestTo(SETTLEMENT_URL))
                .andRespond(withSuccess(SUCCESS_BODY, MediaType.APPLICATION_JSON));

        RuntimeWorkStorageBillingClient.SettlementResult result =
                client(builder.build()).settle(7L, LocalDate.of(2026, 7, 1));

        assertThat(result.getStatus()).isEqualTo("CHARGED");
        server.verify();
    }

    @Test
    void settleShouldStopAfterSecondServerFailure() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        server.expect(requestTo(SETTLEMENT_URL)).andRespond(withServerError());
        server.expect(requestTo(SETTLEMENT_URL)).andRespond(withServerError());

        assertThatThrownBy(() -> client(builder.build()).settle(7L, LocalDate.of(2026, 7, 1)))
                .isInstanceOf(HttpServerErrorException.class);
        server.verify();
    }

    @Test
    void settleShouldNotRetryClientFailure() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        server.expect(requestTo(SETTLEMENT_URL)).andRespond(withBadRequest());

        assertThatThrownBy(() -> client(builder.build()).settle(7L, LocalDate.of(2026, 7, 1)))
                .isInstanceOf(HttpClientErrorException.class);
        server.verify();
    }

    /**
     * 创建使用测试 RestClient 的客户端。
     */
    private RuntimeWorkStorageBillingClient client(RestClient restClient) {
        RuntimeProperties runtimeProperties = new RuntimeProperties();
        runtimeProperties.setBaseUrl(BASE_URL);
        AdminPointProperties adminPointProperties = new AdminPointProperties();
        adminPointProperties.setSecret(ADMIN_SECRET);
        return new RuntimeWorkStorageBillingClient(runtimeProperties, adminPointProperties, restClient);
    }
}
