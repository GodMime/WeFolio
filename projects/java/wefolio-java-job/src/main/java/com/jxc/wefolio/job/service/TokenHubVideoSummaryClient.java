package com.jxc.wefolio.job.service;

import com.fasterxml.jackson.databind.node.ObjectNode;
import com.jxc.wefolio.job.config.VideoSummaryProperties;
import com.jxc.wefolio.service.portfolioai.StrictAiJson;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;

/** 原视频单次摘要请求，无作品集任务依赖，不下载、裁剪或混入帧与转写。 */
@Service
@RequiredArgsConstructor
public class TokenHubVideoSummaryClient {
    /** 静态模板路径及固定文字，业务内容不能进入 system。 */
    private static final String RESOURCE = "/portfolioai/direct-video-summary.txt";
    /** 只请求当前视频的事实摘要。 */
    private static final String USER_HINT = "请整理这个原视频的作品摘要，区分画面与可见字幕，说明可确认内容及识别局限。";
    /** 提示词身份命名空间，与帧摘要缓存隔离。 */
    private static final String PROMPT_VERSION_PREFIX = "direct-video-v1:";
    /** 稳定模板链接只用于哈希，不发往供应商。 */
    private static final String TEMPLATE_URL = "https://template.invalid/video";
    /** 官方请求固定字段及值，不允许作品内容影响协议参数。 */
    private static final String MODEL_FIELD = "model";
    private static final String STREAM = "stream";
    private static final String TEMPERATURE = "temperature";
    private static final String TOP_P = "top_p";
    private static final String MAX_TOKENS = "max_tokens";
    private static final String REASONING_EFFORT = "reasoning_effort";
    private static final String LOW = "low";
    private static final String THINKING = "thinking";
    private static final String TYPE = "type";
    private static final String ENABLED = "enabled";
    private static final String RESPONSE_FORMAT = "response_format";
    private static final String JSON_OBJECT = "json_object";
    private static final String MESSAGES = "messages";
    private static final String ROLE = "role";
    private static final String SYSTEM = "system";
    private static final String CONTENT = "content";
    private static final String USER = "user";
    private static final String TEXT = "text";
    private static final String VIDEO_URL = "video_url";
    private static final String URL = "url";
    private static final String HTTPS = "https";
    private static final String PROMPT_CACHE_KEY = "prompt_cache_key";
    private static final String SOURCE_VERSION = "sourceVersion";
    /** 安全诊断上下文不包含用户标识或签名地址。 */
    private static final String LOG_CONTEXT_PREFIX = "video-summary:";
    /** 独立边界配置与现有 TokenHub 环境密钥。 */
    private final VideoSummaryProperties properties;

    /** 精确一次请求，不因响应失败自动重试。 */
    public PortfolioAiModelClient.Completion summarize(long workId, String signedUrl, Instant deadline) {
        VideoAudioIo.check(deadline);
        Duration remaining = Duration.between(Instant.now(), deadline);
        Duration maximum = Duration.ofSeconds(properties.getTotalTimeoutSeconds());
        Duration timeout = remaining.compareTo(maximum) < 0 ? remaining : maximum;
        Duration connection = timeout.compareTo(Duration.ofSeconds(properties.getConnectTimeoutSeconds())) < 0
                ? timeout : Duration.ofSeconds(properties.getConnectTimeoutSeconds());
        return TokenHubCompletionTransport.complete(URI.create(VideoSummaryProperties.ENDPOINT), properties.getApiKey(),
                payload(signedUrl), timeout, connection, properties.getMaxResponseBytes(), LOG_CONTEXT_PREFIX + workId,
                this::createHttpClient);
    }

    /** 单原视频 URL 的请求不包含媒体数据库主键、音频或图片。 */
    ObjectNode payload(String signedUrl) {
        URI uri;
        try { uri = URI.create(signedUrl); }
        catch (Exception exception) { throw new IllegalArgumentException("原视频读取地址非法"); }
        if (!HTTPS.equals(uri.getScheme()) || uri.getHost() == null || uri.getUserInfo() != null
                || uri.getFragment() != null || (uri.getPort() != -1 && uri.getPort() != 443)) {
            throw new IllegalArgumentException("原视频读取地址非法");
        }
        ObjectNode body = body(signedUrl);
        body.put(PROMPT_CACHE_KEY, StrictAiJson.canonicalHash(body(TEMPLATE_URL)));
        return body;
    }

    /** 输入身份剔除临时签名地址，但包括实际模型参数和静态提示内容。 */
    public String inputHash(String sourceVersion) {
        ObjectNode stable = body(TEMPLATE_URL).put(SOURCE_VERSION, sourceVersion);
        return StrictAiJson.canonicalHash(stable);
    }

    /** 保存稳定的真实模板身份，模板变化不会强制旧合法摘要失效。 */
    public String promptVersion() { return PROMPT_VERSION_PREFIX + StrictAiJson.canonicalHash(body(TEMPLATE_URL)); }

    /** 两种摘要提示分别带版本，共用严格 JSON 输出合同和传输。 */
    private ObjectNode body(String url) {
        ObjectNode body = StrictAiJson.mapper().createObjectNode().put(MODEL_FIELD, VideoSummaryProperties.MODEL)
                .put(STREAM, false).put(TEMPERATURE, 1.0).put(TOP_P, 0.95)
                .put(MAX_TOKENS, properties.getMaxOutputTokens()).put(REASONING_EFFORT, LOW);
        body.putObject(THINKING).put(TYPE, ENABLED);
        body.putObject(RESPONSE_FORMAT).put(TYPE, JSON_OBJECT);
        var messages = body.putArray(MESSAGES);
        messages.addObject().put(ROLE, SYSTEM).put(CONTENT, system());
        var content = messages.addObject().put(ROLE, USER).putArray(CONTENT);
        content.addObject().put(TYPE, TEXT).put(TEXT, USER_HINT);
        content.addObject().put(TYPE, VIDEO_URL).putObject(VIDEO_URL).put(URL, url);
        return body;
    }

    /** 模板读取失败使用固定消息，不携带视频或远端响应。 */
    private String system() {
        try (InputStream input = getClass().getResourceAsStream(RESOURCE)) {
            if (input == null) { throw new IllegalStateException("原视频摘要模板不可用"); }
            return new String(input.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException exception) { throw new IllegalStateException("原视频摘要模板不可用"); }
    }

    /** 测试可替换传输，不跟随远端重定向。 */
    HttpClient createHttpClient(Duration connection) {
        return HttpClient.newBuilder().connectTimeout(connection).followRedirects(HttpClient.Redirect.NEVER).build();
    }
}
