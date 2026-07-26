package com.jxc.wefolio.job.service;

import com.jxc.wefolio.job.config.AdminPointProperties;
import com.jxc.wefolio.job.config.RuntimeProperties;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.client.HttpServerErrorException;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;

import java.net.http.HttpClient;
import java.time.Duration;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.Map;

/** job 调用 runtime 的月度作品存储结算客户端。 */
@Slf4j
@Service
public class RuntimeWorkStorageBillingClient {

    private static final String SETTLEMENT_PATH = "/api/admin/points/work-storage-billing/settlements";
    private static final String SECRET_HEADER = "X-Admin-Point-Secret";
    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(3);
    private static final int MAX_ATTEMPTS = 2;

    private final RuntimeProperties runtimeProperties;
    private final AdminPointProperties adminPointProperties;
    private final RestClient restClient;

    /** 创建复用同一 HTTP 客户端实例的 runtime 结算客户端。 */
    @Autowired
    public RuntimeWorkStorageBillingClient(
            RuntimeProperties runtimeProperties,
            AdminPointProperties adminPointProperties
    ) {
        this(runtimeProperties, adminPointProperties, createRestClient());
    }

    /**
     * 创建使用指定 RestClient 的客户端，供可控 HTTP 测试复用。
     *
     * @param runtimeProperties runtime 地址配置
     * @param adminPointProperties 后台密钥配置
     * @param restClient HTTP 客户端
     */
    RuntimeWorkStorageBillingClient(
            RuntimeProperties runtimeProperties,
            AdminPointProperties adminPointProperties,
            RestClient restClient
    ) {
        this.runtimeProperties = runtimeProperties;
        this.adminPointProperties = adminPointProperties;
        this.restClient = restClient;
    }

    /** 按用户和账期请求 runtime 结算，不传积分规则或余额。 */
    public SettlementResult settle(long userId, LocalDate billingMonth) {
        String baseUrl = runtimeProperties.getBaseUrl();
        if (baseUrl == null || !baseUrl.startsWith("https://")) {
            throw new IllegalStateException("WEFOLIO_RUNTIME_BASE_URL 必须配置为 HTTPS 地址");
        }
        Envelope envelope = null;
        for (int attempt = 1; attempt <= MAX_ATTEMPTS; attempt++) {
            try {
                envelope = requestSettlement(baseUrl, userId, billingMonth);
                break;
            } catch (ResourceAccessException | HttpServerErrorException exception) {
                if (attempt >= MAX_ATTEMPTS) {
                    throw exception;
                }
                log.warn("runtime 单用户月度结算请求失败，准备重试: userId={}, billingMonth={}, attempt={}, exceptionType={}",
                        userId, YearMonth.from(billingMonth), attempt, exception.getClass().getSimpleName());
            }
        }
        if (envelope == null || envelope.getData() == null) {
            throw new IllegalStateException("runtime 月度结算响应为空");
        }
        return envelope.getData();
    }

    /**
     * 执行一次 runtime 结算请求。
     */
    private Envelope requestSettlement(String baseUrl, long userId, LocalDate billingMonth) {
        return restClient.post()
                .uri(baseUrl.replaceAll("/+$", "") + SETTLEMENT_PATH)
                .contentType(MediaType.APPLICATION_JSON)
                .header(SECRET_HEADER, adminPointProperties.getSecret())
                .body(Map.of("userId", userId, "billingMonth", YearMonth.from(billingMonth).toString()))
                .retrieve()
                .body(Envelope.class);
    }

    /**
     * 创建带固定连接和读取超时的请求工厂。
     */
    static JdkClientHttpRequestFactory createRequestFactory() {
        HttpClient httpClient = HttpClient.newBuilder()
                .connectTimeout(REQUEST_TIMEOUT)
                .build();
        JdkClientHttpRequestFactory requestFactory = new JdkClientHttpRequestFactory(httpClient);
        requestFactory.setReadTimeout(REQUEST_TIMEOUT);
        return requestFactory;
    }

    /**
     * 创建复用的 runtime HTTP 客户端。
     */
    private static RestClient createRestClient() {
        return RestClient.builder()
                .requestFactory(createRequestFactory())
                .build();
    }

    /** runtime 通用响应信封。 */
    @Data
    public static class Envelope {
        private SettlementResult data;
    }

    /** runtime 单用户结算结果。 */
    @Data
    public static class SettlementResult {
        private Long userId;
        private String billingMonth;
        private String status;
        private Long pointsDue;
        private Long pointsDeducted;
        private boolean idempotent;
    }
}
