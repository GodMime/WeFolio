package com.jxc.wefolio.job.service;

import com.jxc.wefolio.job.config.AdminPointProperties;
import com.jxc.wefolio.job.config.RuntimeProperties;
import com.jxc.wefolio.job.config.VirtualPaymentDispatchProperties;
import lombok.Data;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

import java.net.http.HttpClient;
import java.time.Duration;

/**
 * job 调用 runtime 微信虚拟支付单任务接口的无重试客户端。
 */
@Service
public class RuntimeVirtualPaymentTaskClient {

    /** 后台积分内部密钥请求头。 */
    private static final String SECRET_HEADER = "X-Admin-Point-Secret";

    /** 赠送订单执行路径模板。 */
    private static final String GIFT_EXECUTION_PATH =
            "/api/admin/points/virtual-payment/gift-orders/%d/executions";

    /** 扣币任务执行路径模板。 */
    private static final String DEBIT_EXECUTION_PATH =
            "/api/admin/points/virtual-payment/debit-tasks/%d/executions";

    /** 扣币任务恢复路径模板。 */
    private static final String DEBIT_RECOVERY_PATH =
            "/api/admin/points/virtual-payment/users/%d/debit-task-recoveries";

    /** runtime 根地址末尾斜杠匹配表达式。 */
    private static final String TRAILING_SLASH_PATTERN = "/+$";

    /** runtime 根地址配置。 */
    private final RuntimeProperties runtimeProperties;

    /** 后台积分密钥配置。 */
    private final AdminPointProperties adminPointProperties;

    /** 复用的无重试 HTTP 客户端。 */
    private final RestClient restClient;

    /** 创建使用分发专用超时的复用 HTTP 客户端。 */
    @Autowired
    public RuntimeVirtualPaymentTaskClient(
            RuntimeProperties runtimeProperties,
            AdminPointProperties adminPointProperties,
            VirtualPaymentDispatchProperties dispatchProperties
    ) {
        this(runtimeProperties, adminPointProperties,
                RestClient.builder()
                        .requestFactory(createRequestFactory(dispatchProperties.getRequestTimeout()))
                        .build());
    }

    /** 使用可控 RestClient 构造客户端，供 HTTP 契约测试使用。 */
    RuntimeVirtualPaymentTaskClient(
            RuntimeProperties runtimeProperties,
            AdminPointProperties adminPointProperties,
            RestClient restClient
    ) {
        this.runtimeProperties = runtimeProperties;
        this.adminPointProperties = adminPointProperties;
        this.restClient = restClient;
    }

    /** 请求 runtime 执行单条赠送订单。 */
    public TaskExecutionResult executeGiftOrder(long orderId) {
        return requestExecution(GIFT_EXECUTION_PATH.formatted(orderId));
    }

    /** 请求 runtime 执行单条扣币任务。 */
    public TaskExecutionResult executeDebitTask(long taskId) {
        return requestExecution(DEBIT_EXECUTION_PATH.formatted(taskId));
    }

    /** 请求 runtime 为指定用户保障活动扣币任务。 */
    public DebitTaskRecoveryResult recoverDebitTask(long userId) {
        DebitTaskRecoveryEnvelope envelope = restClient.post()
                .uri(runtimeUrl(DEBIT_RECOVERY_PATH.formatted(userId)))
                .header(SECRET_HEADER, adminPointProperties.getSecret())
                .retrieve()
                .body(DebitTaskRecoveryEnvelope.class);
        if (envelope == null || envelope.getData() == null) {
            throw new IllegalStateException("runtime 扣币任务恢复响应为空");
        }
        return envelope.getData();
    }

    /** 发送一次任务执行请求，不在本方法内重试。 */
    private TaskExecutionResult requestExecution(String path) {
        TaskExecutionEnvelope envelope = restClient.post()
                .uri(runtimeUrl(path))
                .header(SECRET_HEADER, adminPointProperties.getSecret())
                .retrieve()
                .body(TaskExecutionEnvelope.class);
        if (envelope == null || envelope.getData() == null) {
            throw new IllegalStateException("runtime 虚拟支付任务执行响应为空");
        }
        return envelope.getData();
    }

    /** 组合并校验 runtime HTTPS 完整地址。 */
    private String runtimeUrl(String path) {
        String baseUrl = runtimeProperties.getBaseUrl();
        if (baseUrl == null || !baseUrl.startsWith("https://")) {
            throw new IllegalStateException("WEFOLIO_RUNTIME_BASE_URL 必须配置为 HTTPS 地址");
        }
        return baseUrl.replaceAll(TRAILING_SLASH_PATTERN, "") + path;
    }

    /** 创建使用指定连接和读取超时的请求工厂。 */
    static JdkClientHttpRequestFactory createRequestFactory(Duration requestTimeout) {
        HttpClient httpClient = HttpClient.newBuilder()
                .connectTimeout(requestTimeout)
                .build();
        JdkClientHttpRequestFactory requestFactory = new JdkClientHttpRequestFactory(httpClient);
        requestFactory.setReadTimeout(requestTimeout);
        return requestFactory;
    }

    /** runtime 任务执行响应信封。 */
    @Data
    public static class TaskExecutionEnvelope {
        private TaskExecutionResult data;
    }

    /** runtime 单任务执行结果。 */
    @Data
    public static class TaskExecutionResult {
        private Long targetId;
        private String taskType;
        private String outcome;
    }

    /** runtime 扣币任务恢复响应信封。 */
    @Data
    public static class DebitTaskRecoveryEnvelope {
        private DebitTaskRecoveryResult data;
    }

    /** runtime 扣币任务恢复结果。 */
    @Data
    public static class DebitTaskRecoveryResult {
        private Long userId;
        private Long taskId;
        private String outcome;
    }
}
