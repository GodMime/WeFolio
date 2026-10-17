package com.jxc.wefolio.config;

import jakarta.annotation.PostConstruct;
import lombok.Data;
import lombok.ToString;
import java.nio.charset.StandardCharsets;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.Set;

/** 启动时读取 AI 开关和运行预算；修改后重启生效。 */
@Data
@Component
@ConfigurationProperties(prefix = "portfolio-ai")
public class PortfolioAiProperties {
    /** 腾讯云 TokenHub 的官方兼容接口根地址。 */
    public static final String TOKENHUB_BASE_URL = "https://tokenhub.tencentmaas.com/v1";
    /** 本次接入唯一允许使用的模型名称。 */
    public static final String TOKENHUB_MODEL = "glm-5.3-flash";
    /** TokenHub 推荐的采样温度。 */
    private static final double DEFAULT_TEMPERATURE = 1.0;
    /** 思考与最终 JSON 共用输出额度，应用保持有界的 32K 预算。 */
    private static final int MAX_OUTPUT_TOKENS = 32_768;
    /** 默认使用较高思考深度，避免供应商缺省 max 消耗过多预算。 */
    private static final String DEFAULT_REASONING_EFFORT = "high";
    /** 官方 GLM 思考深度取值；新任务冻结后不随启动配置变更。 */
    private static final Set<String> REASONING_EFFORTS = Set.of("max", DEFAULT_REASONING_EFFORT, "low");
    /** 新任务开关，默认关闭以允许后端先发布。 */
    private boolean enabled;
    /** 新任务是否冻结真实多模态输入，默认关闭以兼容旧部署。 */
    private boolean mediaEnabled;
    /** 全部 Job 消费者升级后才开启 v2 音频文字冻结。 */
    private boolean mediaAudioTextEnabled;
    /** 全部 Job 消费者升级后才开启 v3，优先冻结持久摘要并同时包含音频文字。 */
    private boolean mediaStoredVideoSummaryEnabled;
    /** 新参考附件接单开关；关闭后已有附件仍可维护。 */
    private boolean referenceEnabled;
    /** 全局附件活动额度，实际还需为普通任务保留一个位置。 */
    private int referenceMaxActiveTasks = 10;
    /** 所有 Runtime 实例共用的附件回执签名密钥，不参与日志输出。 */
    @ToString.Exclude
    private String referenceTokenSecret;
    /** 单次描述的 Unicode 码点上限。 */
    private int maxPromptCodePoints = 2000;
    /** 完整普通配置的字节上限。 */
    private int maxConfigBytes = 256 * 1024;
    /** 来源上下文的字节上限。 */
    private int maxContextBytes = 128 * 1024;
    /** JSON 根容器计第一层的深度上限。 */
    private int maxJsonDepth = 16;
    /** 总等待期限，覆盖排队、媒体分析、页面生成及全部自动重试，与 Job 保持一致。 */
    private int totalTimeoutSeconds = 1200;
    /** 终态记录留存天数。 */
    private int retentionDays = 30;
    /** 服务级活动任务队列容量。 */
    private int maxActiveTasks = 100;
    /** TokenHub 官方端点，冻结任务时不含鉴权头。 */
    private String baseUrl = TOKENHUB_BASE_URL;
    /** 固定使用 GLM Flash，避免任务误入旧供应商。 */
    private String model = TOKENHUB_MODEL;
    /** 结构化输出采样参数，默认使用 TokenHub 推荐值。 */
    private double temperature = DEFAULT_TEMPERATURE;
    /** 包含思考与最终 JSON 的输出 token 预算。 */
    private int maxTokens = MAX_OUTPUT_TOKENS;
    /** 新任务的思考深度，与输出额度一起冻结。 */
    private String reasoningEffort = DEFAULT_REASONING_EFFORT;

    /** 新任务启用时限制官方端点和模型，关闭时允许旧环境配置先完成部署。 */
    @PostConstruct
    public void validateWhenEnabled() {
        if (referenceEnabled && (maxActiveTasks < 2 || referenceMaxActiveTasks < 1
                || referenceTokenSecret == null || referenceTokenSecret.getBytes(StandardCharsets.UTF_8).length < 32)) {
            throw new IllegalStateException("参考附件需要至少两个任务名额、正数附件额度和至少32字节的独立签名密钥");
        }
        if (!enabled) { return; }
        if (!TOKENHUB_BASE_URL.equals(baseUrl) && !(TOKENHUB_BASE_URL + "/").equals(baseUrl)) {
            throw new IllegalStateException("portfolio-ai.base-url 必须为 TokenHub 官方接口地址");
        }
        if (!TOKENHUB_MODEL.equals(model)) {
            throw new IllegalStateException("portfolio-ai.model 必须为 glm-5.3-flash");
        }
        if (!Double.isFinite(temperature) || temperature < 0 || temperature > 2
                || maxTokens < 1 || maxTokens > MAX_OUTPUT_TOKENS) {
            throw new IllegalStateException("portfolio-ai.temperature 必须为 0 至 2 的有限数，max-tokens 必须为 1 至 32768");
        }
        if (reasoningEffort == null || !REASONING_EFFORTS.contains(reasoningEffort)) {
            throw new IllegalStateException("portfolio-ai.reasoning-effort 必须为 max、high 或 low");
        }
    }
}
