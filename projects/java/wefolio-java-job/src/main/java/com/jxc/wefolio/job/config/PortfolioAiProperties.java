package com.jxc.wefolio.job.config;

import jakarta.annotation.PostConstruct;
import lombok.Data;
import lombok.ToString;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.net.URI;

/** 高级作品集 AI 的启动配置；默认关闭，Runtime 与 Job 使用相同环境开关。 */
@Data
@ToString(exclude = {"apiKey", "userHmacSecret"})
@Component
@ConfigurationProperties(prefix = "portfolio-ai")
public class PortfolioAiProperties {
    /** 腾讯云 TokenHub 官方兼容接口根地址。 */
    public static final String TOKENHUB_BASE_URL = "https://tokenhub.tencentmaas.com/v1";
    /** 当前唯一支持的模型。 */
    public static final String TOKENHUB_MODEL = "glm-5.3-flash";
    /** 冻结任务的供应商身份。 */
    public static final String TOKENHUB_PROVIDER = "TOKENHUB";
    /** 全局接单开关，启动读取。 */
    private boolean enabled;
    /** 媒体部署预期开关；新任务能力以 Runtime 冻结计划为准，差异会告警。 */
    private boolean mediaEnabled;
    /** 单任务全部真实请求及重试的费用上限，单位分。 */
    private int mediaTaskBudgetCents = 300;
    /** 每请求人每日真实请求预算，单位分。 */
    private int mediaDailyBudgetCents = 2000;
    /** 单任务全部尝试共享的模型调用次数。 */
    private int mediaMaxCalls = 100;
    /** 媒体摘要长期缓存天数，不控制帧资产生命周期。 */
    private int mediaCacheDays = 7;
    /** 腾讯云 TokenHub 官方根地址，包含 v1 路径。 */
    private String baseUrl = TOKENHUB_BASE_URL;
    /** 当前固定模型名称。 */
    private String model = TOKENHUB_MODEL;
    /** 供应商密钥，不进入任务或日志。 */
    private String apiKey = "";
    /** 用于供应商隔离标识的服务端 HMAC 密钥，缺省使用 API 密钥。 */
    private String userHmacSecret = "";
    /** 全任务总期限。 */
    private int totalTimeoutSeconds = 180;
    /** 每次 HTTP 绝对截止时间。 */
    private int callTimeoutSeconds = 60;
    /** 连接期限不超过剩余调用时间。 */
    private int connectTimeoutSeconds = 5;
    /** 进程中断恢复窗口，大于一次 HTTP 期限。 */
    private int recoverySeconds = 90;
    /** 初次及自动重试共用预算。 */
    private int maxAttempts = 3;
    /** 终态主库保留天数。 */
    private int retentionDays = 30;
    /** 每轮数据库扫描数量上限。 */
    private int batchSize = 100;
    /** 每小时最多清理的批次数，防止积压长期占用调度线程。 */
    private int maxCleanupBatches = 100;
    /** 当前实例的模型并发上限。 */
    private int workerCount = 2;
    /** 解压后供应商 HTTP 响应字节预算。 */
    private int maxResponseBytes = 2 * 1024 * 1024;

    /** 验证有界参数；开关关闭时允许暂未配置 API 密钥。 */
    @PostConstruct
    public void validate() {
        URI endpoint = URI.create(baseUrl);
        if (!"https".equals(endpoint.getScheme()) || endpoint.getHost() == null || endpoint.getUserInfo() != null
                || endpoint.getQuery() != null || endpoint.getFragment() != null) {
            throw new IllegalStateException("portfolio-ai.base-url 必须为 HTTPS 根地址");
        }
        if (totalTimeoutSeconds <= 0 || callTimeoutSeconds <= 0 || callTimeoutSeconds >= recoverySeconds
                || connectTimeoutSeconds <= 0 || connectTimeoutSeconds > callTimeoutSeconds
                || maxAttempts <= 0 || maxAttempts > 3 || retentionDays <= 0 || workerCount <= 0
                || workerCount > 32 || batchSize <= 0 || batchSize > 1000 || maxCleanupBatches <= 0 || maxCleanupBatches > 1000 || maxResponseBytes <= 0
                || maxResponseBytes > 2 * 1024 * 1024) {
            throw new IllegalStateException("portfolio-ai 的期限、数量与字节预算不合法");
        }
        if (mediaTaskBudgetCents < 1 || mediaTaskBudgetCents > 10000 || mediaDailyBudgetCents < mediaTaskBudgetCents
                || mediaDailyBudgetCents > 1000000 || mediaMaxCalls < 1 || mediaMaxCalls > 100 || mediaCacheDays != 7) {
            throw new IllegalStateException("portfolio-ai 媒体费用、次数或缓存预算不合法");
        }
        if (enabled && (!TOKENHUB_BASE_URL.equals(canonicalBaseUrl(baseUrl)) || !TOKENHUB_MODEL.equals(model))) {
            throw new IllegalStateException("启用高级 AI 仅支持腾讯云 TokenHub 的 glm-5.3-flash 官方端点");
        }
        if (enabled && apiKey.isBlank()) { throw new IllegalStateException("启用高级 AI 前须配置 TOKENHUB_API_KEY"); }
    }

    /** 仅忽略根地址末尾斜杠，不接受其他主机、路径或查询参数。 */
    public static String canonicalBaseUrl(String value) { return value == null ? "" : value.replaceAll("/+$", ""); }
}
