package com.jxc.wefolio.config;

import jakarta.annotation.PostConstruct;
import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

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
    /** 当前供应商客户端允许的输出 token 预算上限。 */
    private static final int MAX_OUTPUT_TOKENS = 8192;
    /** 新任务开关，默认关闭以允许后端先发布。 */
    private boolean enabled;
    /** 单次描述的 Unicode 码点上限。 */
    private int maxPromptCodePoints = 2000;
    /** 完整普通配置的字节上限。 */
    private int maxConfigBytes = 256 * 1024;
    /** 来源上下文的字节上限。 */
    private int maxContextBytes = 128 * 1024;
    /** JSON 根容器计第一层的深度上限。 */
    private int maxJsonDepth = 16;
    /** 总等待期限，包括排队。 */
    private int totalTimeoutSeconds = 180;
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
    /** 输出 token 预算。 */
    private int maxTokens = MAX_OUTPUT_TOKENS;

    /** 新任务启用时限制官方端点和模型，关闭时允许旧环境配置先完成部署。 */
    @PostConstruct
    public void validateWhenEnabled() {
        if (!enabled) { return; }
        if (!TOKENHUB_BASE_URL.equals(baseUrl) && !(TOKENHUB_BASE_URL + "/").equals(baseUrl)) {
            throw new IllegalStateException("portfolio-ai.base-url 必须为 TokenHub 官方接口地址");
        }
        if (!TOKENHUB_MODEL.equals(model)) {
            throw new IllegalStateException("portfolio-ai.model 必须为 glm-5.3-flash");
        }
        if (!Double.isFinite(temperature) || temperature < 0 || temperature > 2
                || maxTokens < 1 || maxTokens > MAX_OUTPUT_TOKENS) {
            throw new IllegalStateException("portfolio-ai.temperature 必须为 0 至 2 的有限数，max-tokens 必须为 1 至 8192");
        }
    }
}
