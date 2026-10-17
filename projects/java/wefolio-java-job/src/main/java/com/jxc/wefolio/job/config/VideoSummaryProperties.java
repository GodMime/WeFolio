package com.jxc.wefolio.job.config;

import jakarta.annotation.PostConstruct;
import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/** 原视频摘要独立配置，关闭时不影响现有审核、帧与音频处理。 */
@Getter
@Setter
@Component
@ConfigurationProperties(prefix = VideoSummaryProperties.PREFIX)
public class VideoSummaryProperties {
    /** Spring 配置统一命名空间。 */
    public static final String PREFIX = "video-summary";
    /** 固定官方端点，禁止凭据跟随业务 URL。 */
    public static final String ENDPOINT = "https://tokenhub.tencentmaas.com/v1/chat/completions";
    /** 已验证支持原视频输入的模型。 */
    public static final String MODEL = "glm-5.3-flash";
    /** 官方一百万级上下文按二进制上界保守预留，不根据单样本推断视频 token。 */
    public static final long CONTEXT_INPUT_UPPER_BOUND = 1_048_576L;
    /** 明确关闭默认值，迁移和消费者升级后由运维开启。 */
    private boolean enabled;
    /** 与现有 TokenHub 共用密钥，禁止输出完整配置。 */
    private String apiKey;
    /** 单件包含预检与模型调用的最长秒数。 */
    private int totalTimeoutSeconds = 180;
    /** 模型连接建立上限秒数。 */
    private int connectTimeoutSeconds = 10;
    /** 响应传输和解压分别使用的硬字节上限。 */
    private int maxResponseBytes = 1_048_576;
    /** 作品保存时长的应用准入上限，不代表供应商保证处理十分钟视频。 */
    private int maxDurationSeconds = 600;
    /** 文档 100 MB 默认按十进制保守解释，显式扩展不得超过本地保护硬界。 */
    private long maxVideoBytes = 100_000_000L;
    /** 每实例付费调用并发数。 */
    private int concurrency = 1;
    /** 每次审核扫描或历史请求可预留的摘要次数。 */
    private int maxRequestsPerRun = 100;
    /** 独立后台估算费用预算，单位分，不使用用户积分或作品集账本。 */
    private long maxCostFenPerRun = 500;
    /** 含思考在内的最大输出 token 数，为模型推理及摘要正文共同留出空间。 */
    private int maxOutputTokens = 16384;

    /** 启用时拒绝缺少密钥和不受控资源配置。 */
    @PostConstruct
    public void validate() {
        if (!enabled) { return; }
        if (apiKey == null || apiKey.isBlank()) { throw new IllegalStateException("视频摘要未配置 TokenHub 密钥"); }
        if (totalTimeoutSeconds <= 0 || totalTimeoutSeconds > 180 || connectTimeoutSeconds <= 0
                || connectTimeoutSeconds > totalTimeoutSeconds || maxResponseBytes <= 0 || maxResponseBytes > 1_048_576
                || maxDurationSeconds <= 0 || maxDurationSeconds > 600 || maxVideoBytes <= 0 || maxVideoBytes > 104_857_600L
                || concurrency <= 0 || concurrency > 4 || maxRequestsPerRun <= 0 || maxRequestsPerRun > 1000
                || maxCostFenPerRun <= 0 || maxCostFenPerRun > 100_000
                || maxOutputTokens <= 0 || maxOutputTokens > 16384) {
            throw new IllegalStateException("视频摘要资源边界或预算非法");
        }
    }
}
