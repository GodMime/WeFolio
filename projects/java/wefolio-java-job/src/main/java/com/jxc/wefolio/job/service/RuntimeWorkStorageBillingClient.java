package com.jxc.wefolio.job.service;

import com.jxc.wefolio.job.config.AdminPointProperties;
import com.jxc.wefolio.job.config.RuntimeProperties;
import lombok.Data;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

import java.time.LocalDate;
import java.time.YearMonth;
import java.util.Map;

/** job 调用 runtime 的月度作品存储结算客户端。 */
@Service
public class RuntimeWorkStorageBillingClient {

    private static final String SETTLEMENT_PATH = "/api/admin/points/work-storage-billing/settlements";
    private static final String SECRET_HEADER = "X-Admin-Point-Secret";

    private final RuntimeProperties runtimeProperties;
    private final AdminPointProperties adminPointProperties;
    private final RestClient restClient;

    /** 创建复用同一 HTTP 客户端实例的 runtime 结算客户端。 */
    public RuntimeWorkStorageBillingClient(
            RuntimeProperties runtimeProperties,
            AdminPointProperties adminPointProperties
    ) {
        this.runtimeProperties = runtimeProperties;
        this.adminPointProperties = adminPointProperties;
        this.restClient = RestClient.create();
    }

    /** 按用户和账期请求 runtime 结算，不传积分规则或余额。 */
    public SettlementResult settle(long userId, LocalDate billingMonth) {
        String baseUrl = runtimeProperties.getBaseUrl();
        if (baseUrl == null || !baseUrl.startsWith("https://")) {
            throw new IllegalStateException("WEFOLIO_RUNTIME_BASE_URL 必须配置为 HTTPS 地址");
        }
        Envelope envelope = restClient.post()
                .uri(baseUrl.replaceAll("/+$", "") + SETTLEMENT_PATH)
                .contentType(MediaType.APPLICATION_JSON)
                .header(SECRET_HEADER, adminPointProperties.getSecret())
                .body(Map.of("userId", userId, "billingMonth", YearMonth.from(billingMonth).toString()))
                .retrieve()
                .body(Envelope.class);
        if (envelope == null || envelope.getData() == null) {
            throw new IllegalStateException("runtime 月度结算响应为空");
        }
        return envelope.getData();
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
