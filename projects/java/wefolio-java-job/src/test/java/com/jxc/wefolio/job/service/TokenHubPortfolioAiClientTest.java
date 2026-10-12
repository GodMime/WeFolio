package com.jxc.wefolio.job.service;

import com.fasterxml.jackson.databind.node.ObjectNode;
import com.jxc.wefolio.job.config.PortfolioAiProperties;
import com.jxc.wefolio.job.entity.AiGenerationTaskEntity;
import com.jxc.wefolio.service.portfolioai.AiProtocolException;
import com.jxc.wefolio.service.portfolioai.StrictAiJson;
import com.jxc.wefolio.service.portfolioai.PortfolioAiMediaContract;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.mockito.ArgumentCaptor;

import java.io.ByteArrayOutputStream;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpHeaders;
import java.nio.charset.StandardCharsets;
import java.nio.ByteBuffer;
import java.time.Duration;
import java.util.List;
import java.util.ArrayList;
import java.util.Map;
import java.util.Collections;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Flow;
import java.util.concurrent.TimeUnit;
import java.util.zip.GZIPOutputStream;
import java.util.stream.Stream;

import static com.jxc.wefolio.job.service.PortfolioAiModelClient.CacheStatus.*;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/** 官方请求参数、供应商完成原因、绝对期限和读取中响应预算测试。 */
class TokenHubPortfolioAiClientTest {
    /** 真实客户端消费传输替身响应，缓存异常不影响正文及合法核心用量。 */
    @ParameterizedTest(name = "{0}")
    @MethodSource("cacheUsageCases")
    void optionalCacheUsageIsStrictlyNormalized(String label, String fields, Long expectedCache,
                                                PortfolioAiModelClient.CacheStatus expectedStatus) {
        var completion = completionFromUsage("{\"prompt_tokens\":100,\"completion_tokens\":20" + fields + "}");
        assertThat(completion.content()).isEqualTo("{}");
        assertThat(completion.promptTokens()).isEqualTo(100L);
        assertThat(completion.completionTokens()).isEqualTo(20L);
        assertThat(completion.cachedTokens()).isEqualTo(expectedCache);
        assertThat(completion.cacheStatus()).isEqualTo(expectedStatus);
    }

    /** 单字段、等值双字段、冲突及每种非法字段都经过最终 HTTP 响应解析。 */
    private static Stream<Arguments> cacheUsageCases() {
        List<Arguments> cases = new ArrayList<>(List.of(
                Arguments.of("嵌套字段命中", ",\"prompt_tokens_details\":{\"cached_tokens\":80}", 80L, KNOWN_HIT),
                Arguments.of("直接字段命中", ",\"cache_read_tokens\":80", 80L, KNOWN_HIT),
                Arguments.of("双字段相同只使用一次", ",\"prompt_tokens_details\":{\"cached_tokens\":80},\"cache_read_tokens\":80", 80L, KNOWN_HIT),
                Arguments.of("双字段冲突", ",\"prompt_tokens_details\":{\"cached_tokens\":80},\"cache_read_tokens\":79", null, CONFLICT),
                Arguments.of("已知零缓存", ",\"prompt_tokens_details\":{\"cached_tokens\":0},\"cache_read_tokens\":0", 0L, KNOWN_MISS),
                Arguments.of("全部输入命中", ",\"cache_read_tokens\":100", 100L, KNOWN_HIT),
                Arguments.of("候选字段均缺失", "", null, UNKNOWN),
                Arguments.of("空详情对象", ",\"prompt_tokens_details\":{}", null, UNKNOWN),
                Arguments.of("详情存在其它字段", ",\"prompt_tokens_details\":{\"audio_tokens\":10}", null, UNKNOWN),
                Arguments.of("输出思考用量已包含", ",\"cache_read_tokens\":80,\"completion_tokens_details\":{\"reasoning_tokens\":15}", 80L, KNOWN_HIT)));
        for (String value : List.of("null", "\"80\"", "80.0", "1e400", "1e-2000", "-1", "101", "9223372036854775808", "-9223372036854775809", "true", "[]", "{}",
                "[9223372036854775808]", "{\"value\":9223372036854775808}", "[1e400]", "{\"value\":1e400}")) {
            cases.add(Arguments.of("嵌套非法且直接合法：" + value,
                    ",\"prompt_tokens_details\":{\"cached_tokens\":" + value + "},\"cache_read_tokens\":80", null, INVALID));
            cases.add(Arguments.of("直接非法且嵌套合法：" + value,
                    ",\"prompt_tokens_details\":{\"cached_tokens\":80},\"cache_read_tokens\":" + value, null, INVALID));
        }
        for (String value : List.of("null", "\"invalid\"", "[]", "80", "true", "9223372036854775808", "1e400", "[9223372036854775808]", "[1e400]")) {
            cases.add(Arguments.of("详情父节点非法：" + value,
                    ",\"prompt_tokens_details\":" + value + ",\"cache_read_tokens\":80", null, INVALID));
        }
        return cases.stream();
    }

    /** 核心用量不能由缓存推算；合法的一侧仍保留供未知用量日志诊断。 */
    @ParameterizedTest(name = "{0}")
    @MethodSource("coreUsageCases")
    void invalidCoreUsageRemainsUnknown(String label, String usage, Long expectedPrompt, Long expectedOutput) {
        var completion = completionFromUsage(usage);
        assertThat(completion.content()).isEqualTo("{}");
        assertThat(completion.promptTokens()).isEqualTo(expectedPrompt);
        assertThat(completion.completionTokens()).isEqualTo(expectedOutput);
        assertThat(completion.cachedTokens()).isNull();
        assertThat(completion.cacheStatus()).isEqualTo(CORE_USAGE_UNKNOWN);
    }

    /** 缺失、类型错误、负数与溢出都不制造合法核心用量。 */
    private static Stream<Arguments> coreUsageCases() {
        List<Arguments> cases = new ArrayList<>(List.of(
                Arguments.of("整个用量缺失", null, null, null),
                Arguments.of("整个用量为null", "null", null, null),
                Arguments.of("整个用量为数组", "[]", null, null),
                Arguments.of("整个用量为字符串", "\"unknown\"", null, null),
                Arguments.of("整个用量为超界整数", "9223372036854775808", null, null),
                Arguments.of("整个用量为超界浮点", "1e400", null, null),
                Arguments.of("整个用量为带超界整数的数组", "[9223372036854775808]", null, null),
                Arguments.of("整个用量为带超界浮点的数组", "[1e400]", null, null),
                Arguments.of("输入缺失", "{\"completion_tokens\":20,\"cache_read_tokens\":80}", null, 20L),
                Arguments.of("输出缺失", "{\"prompt_tokens\":100,\"cache_read_tokens\":80}", 100L, null)));
        for (String value : List.of("null", "\"100\"", "100.0", "1e400", "1e-2000", "-1", "9223372036854775808", "true", "[]", "{}",
                "[9223372036854775808]", "{\"value\":9223372036854775808}", "[1e400]", "{\"value\":1e400}")) {
            cases.add(Arguments.of("核心输入非法：" + value,
                    "{\"prompt_tokens\":" + value + ",\"completion_tokens\":20,\"cache_read_tokens\":80}", null, 20L));
            cases.add(Arguments.of("核心输出非法：" + value,
                    "{\"prompt_tokens\":100,\"completion_tokens\":" + value + ",\"cache_read_tokens\":80}", 100L, null));
        }
        return cases.stream();
    }

    /** 摘要与最终生成共享相同用量解析，零用量和 Long 上界可无损通过。 */
    @Test
    void summarySharesCacheUsageAndSupportsZeroAndLongBoundary() {
        var client = respondingClient(responseWithUsage("{\"prompt_tokens\":100,\"completion_tokens\":20,\"cache_read_tokens\":80}"));
        var summary = client.summarize(task(), request(),
                List.of(new PortfolioAiModelClient.MediaInput("image_url", "https://private.example/frame", null)), Duration.ofSeconds(2));
        assertThat(summary.cachedTokens()).isEqualTo(80L);
        assertThat(summary.cacheStatus()).isEqualTo(KNOWN_HIT);
        var zero = completionFromUsage("{\"prompt_tokens\":0,\"completion_tokens\":0,\"cache_read_tokens\":0}");
        assertThat(zero.promptTokens()).isZero(); assertThat(zero.completionTokens()).isZero();
        assertThat(zero.cachedTokens()).isZero(); assertThat(zero.cacheStatus()).isEqualTo(KNOWN_MISS);
        var boundary = completionFromUsage("{\"prompt_tokens\":9223372036854775807,\"completion_tokens\":0,\"cache_read_tokens\":9223372036854775807}");
        assertThat(boundary.promptTokens()).isEqualTo(Long.MAX_VALUE);
        assertThat(boundary.cachedTokens()).isEqualTo(Long.MAX_VALUE);
    }

    /** 旧三参结果和默认客户端仍明确报告未知缓存，不需要旧调用方修改。 */
    @Test
    void legacyCompletionAndDefaultClientKeepUnknownCache() {
        var old = new PortfolioAiModelClient.Completion("{}", 100L, 20L);
        assertThat(old.cachedTokens()).isNull(); assertThat(old.cacheStatus()).isEqualTo(UNKNOWN);
        PortfolioAiModelClient legacyClient = (task, request, remaining) -> "{}";
        var completion = legacyClient.generateWithUsage(task(), request(), Duration.ofSeconds(2));
        assertThat(completion.content()).isEqualTo("{}");
        assertThat(completion.promptTokens()).isNull(); assertThat(completion.completionTokens()).isNull();
        assertThat(completion.cachedTokens()).isNull(); assertThat(completion.cacheStatus()).isEqualTo(UNKNOWN);
    }

    /** 只降级计费候选位置的整数溢出，完整响应仍受共享严格 JSON 边界约束。 */
    @Test
    void overflowFallbackStillRejectsMalformedOrUnrelatedResponseFields() {
        String valid = responseWithUsage("{\"prompt_tokens\":100,\"completion_tokens\":20,\"cache_read_tokens\":9223372036854775808}");
        for (String invalid : List.of(
                valid.substring(0, valid.length() - 1) + ",\"unrelated\":9223372036854775808}",
                valid.substring(0, valid.length() - 1) + ",\"unrelated\":1,\"unrelated\":2}",
                valid.substring(0, valid.length() - 1) + ",\"unrelated\":\"\\ud800\"}",
                valid.substring(0, valid.length() - 1) + ",\"unrelated\":1e400}",
                valid.substring(0, valid.length() - 1) + ",\"unrelated\":0e-2000}",
                valid.substring(0, valid.length() - 1) + ",\"unrelated\":1.0e-2000}",
                valid.substring(0, valid.length() - 1) + ",\"unrelated\":1.000e-1000}",
                valid + " {}")) {
            assertThatThrownBy(() -> respondingClient(invalid).generateWithUsage(task(), request(), Duration.ofSeconds(2)))
                    .isInstanceOf(AiProtocolException.class);
        }
        for (String unrelatedDetails : List.of("9223372036854775808", "1e400", "0e-2000", "1.0e-2000", "1.000e-1000")) {
            var raw = responseWithUsage("{\"prompt_tokens\":100,\"completion_tokens\":20,\"prompt_tokens_details\":{\"cached_tokens\":9223372036854775808,\"unrelated\":"
                    + unrelatedDetails + "}}");
            assertThatThrownBy(() -> respondingClient(raw).generateWithUsage(task(), request(), Duration.ofSeconds(2)))
                    .isInstanceOf(AiProtocolException.class);
        }
    }

    /** 要降级的结构也先检查原文树，禁止删除其中的非法 Unicode 或重复键掩盖协议错误。 */
    @Test
    void overflowFallbackChecksUnicodeAndDuplicateKeysBeforeClearingInvalidUsageStructures() {
        for (String usage : List.of(
                "{\"prompt_tokens\":100,\"completion_tokens\":20,\"cache_read_tokens\":{\"value\":9223372036854775808,\"text\":\"\\ud800\"}}",
                "{\"prompt_tokens\":100,\"completion_tokens\":20,\"cache_read_tokens\":{\"value\":9223372036854775808,\"\\ud800\":\"text\"}}",
                "{\"prompt_tokens\":100,\"completion_tokens\":20,\"prompt_tokens_details\":[9223372036854775808,\"\\ud800\"]}",
                "[9223372036854775808,\"\\ud800\"]",
                "[9223372036854775808,{\"value\":1,\"value\":2}]")) {
            assertThatThrownBy(() -> respondingClient(responseWithUsage(usage)).generateWithUsage(task(), request(), Duration.ofSeconds(2)))
                    .isInstanceOf(AiProtocolException.class);
        }
    }

    /** 缓存解析不得将截断结果变为成功，仍在正文校验阶段抛原有安全分类。 */
    @Test
    void validCacheUsageDoesNotOverrideTruncatedOutput() {
        String raw = responseWithUsage("{\"prompt_tokens\":100,\"completion_tokens\":20,\"cache_read_tokens\":80}")
                .replace("\"finish_reason\":\"stop\"", "\"finish_reason\":\"length\"");
        assertThatThrownBy(() -> respondingClient(raw).generateWithUsage(task(), request(), Duration.ofSeconds(2)))
                .isInstanceOfSatisfying(PortfolioAiCallException.class,
                        error -> assertThat(error.getCode()).isEqualTo("OUTPUT_TRUNCATED"));
    }

    /** 构造供应商返回结构，null 参数表示整个用量字段缺失。 */
    private String responseWithUsage(String usage) {
        return "{\"model\":\"glm-5.3-flash\",\"choices\":[{\"finish_reason\":\"stop\",\"message\":{\"role\":\"assistant\",\"content\":\"{}\"}}]"
                + (usage == null ? "" : ",\"usage\":" + usage) + "}";
    }

    /** 通过真实响应解析读取用量，不直接调用私有解析方法。 */
    private PortfolioAiModelClient.Completion completionFromUsage(String usage) {
        return respondingClient(responseWithUsage(usage)).generateWithUsage(task(), request(), Duration.ofSeconds(2));
    }

    /** 替换 HTTP 传输，不调用真实模型或读取环境密钥。 */
    private TokenHubPortfolioAiClient respondingClient(String raw) {
        TokenHubPortfolioAiClient client = spy(new TokenHubPortfolioAiClient(properties()));
        HttpClient transport = mock(HttpClient.class); doReturn(transport).when(client).createHttpClient(any());
        HttpResponse<byte[]> response = mock(HttpResponse.class);
        when(response.statusCode()).thenReturn(200);
        when(response.headers()).thenReturn(HttpHeaders.of(Map.of(), (name, value) -> true));
        when(response.body()).thenReturn(raw.getBytes(StandardCharsets.UTF_8));
        when(transport.sendAsync(any(HttpRequest.class), any(HttpResponse.BodyHandler.class)))
                .thenReturn(CompletableFuture.completedFuture(response));
        return client;
    }

    /** 官方结构化媒体字段及图片/视频互斥，链接不藏在文字content。 */
    @Test void multimediaPayloadUsesTypedContentAndRejectsMixedInput() {
        var client = new TokenHubPortfolioAiClient(properties());
        var frame = new PortfolioAiModelClient.MediaInput("image_url", "https://private.example/frame?signature=fake", 1000L);
        ObjectNode body = client.mediaPayload(task(), request(), List.of(frame));
        assertThat(body.path("max_tokens").asInt()).isEqualTo(4096);
        var content = body.path("messages").get(1).path("content");
        assertThat(content.isArray()).isTrue();
        assertThat(content.get(2).path("type").asText()).isEqualTo("image_url");
        assertThat(content.get(2).path("image_url").path("url").asText()).contains("private.example");
        assertThat(content.get(0).path("text").asText()).doesNotContain("private.example");
        var video = new PortfolioAiModelClient.MediaInput("video_url", "https://private.example/video", null);
        assertThatThrownBy(() -> client.mediaPayload(task(), request(), List.of(frame, video))).hasMessageContaining("类型或数量");
        assertThat(client.mediaPayload(task(), request(), List.of(video)).path("messages").get(1).path("content").get(1).path("type").asText()).isEqualTo("video_url");
    }

    /** 模型图片输入接受持久帧固定上限，超限及空媒体统一报安全分类。 */
    @Test void multimediaCountLimitUsesTheSameContractAsPersistentFrames() {
        var client = new TokenHubPortfolioAiClient(properties());
        var frame = new PortfolioAiModelClient.MediaInput("image_url", "https://private.example/frame", null);
        ObjectNode body = client.mediaPayload(task(), request(), Collections.nCopies(PortfolioAiMediaContract.FRAME_MAX_COUNT, frame));
        assertThat(body.path("messages").get(1).path("content").size()).isEqualTo(1 + PortfolioAiMediaContract.FRAME_MAX_COUNT);
        assertThatThrownBy(() -> client.mediaPayload(task(), request(), Collections.nCopies(PortfolioAiMediaContract.FRAME_MAX_COUNT + 1, frame)))
                .hasMessageContaining("类型或数量");
        assertThatThrownBy(() -> client.mediaPayload(task(), request(), List.of())).hasMessageContaining("类型或数量");
        assertThatThrownBy(() -> client.mediaPayload(task(), request(), null)).hasMessageContaining("类型或数量");
    }

    /** 最终摘要投影也只认WORK且可绑定，不能从不可用来源复制残留摘要。 */
    @Test void finalPayloadProjectsMediaSummaryOnlyForBindableWork() {
        var client = new TokenHubPortfolioAiClient(properties()); ObjectNode request = request();
        var sources = request.withObject("facts").withArray("sources");
        sources.addObject().put("sourceNo", 1).put("kind", "WORK").put("canBind", false)
                .putObject("mediaSummary").put("summary", "不可用作品的残留摘要");
        sources.addObject().put("sourceNo", 2).put("kind", "MEMBER_PORTFOLIO").put("canBind", true)
                .putObject("mediaSummary").put("summary", "成员作品集不能作为媒体摘要");
        sources.addObject().put("sourceNo", 3).put("kind", "WORK").put("canBind", true)
                .putObject("mediaSummary").put("summary", "当前可绑定作品摘要");
        String user = client.payload(task(), request).path("messages").get(1).path("content").asText();
        assertThat(user).contains("当前可绑定作品摘要").doesNotContain("不可用作品的残留摘要", "成员作品集不能作为媒体摘要");
    }
    /** 固定假凭据只存在测试，不读取环境文件或真实密钥。 */
    private PortfolioAiProperties properties() {
        PortfolioAiProperties properties = new PortfolioAiProperties(); properties.setApiKey("test-fake-key"); return properties;
    }

    /** 代表性已冻结请求。 */
    private ObjectNode request() {
        return StrictAiJson.parseObject("""
                {"contractVersion":"ai-portfolio-v1","promptVersion":"portfolio-ai-2026-10-10.2",
                 "input":{"ownerType":"USER","mode":"MODIFY_PAGE","targetMenuKey":"__root__","prompt":"原始秘密13800138000"},
                 "facts":{"safePrompt":"精简文字","baseConfig":{"components":[]},"sources":[],"bindings":[]},
                 "modelConfig":{"provider":"TOKENHUB","model":"glm-5.3-flash","baseUrl":"https://tokenhub.tencentmaas.com/v1","temperature":1.0,"maxTokens":8192}}
                """, 10000, 16);
    }

    /** 模型调用任务元数据。 */
    private AiGenerationTaskEntity task() {
        AiGenerationTaskEntity task = new AiGenerationTaskEntity(); task.setId(1L); task.setRequestedBy(987654L); task.setAttemptCount(1); return task;
    }

    @Test
    void payloadUsesFrozenDefaultsSafeUserAndHmacIdentityWithoutSecretOrRealId() {
        TokenHubPortfolioAiClient client = new TokenHubPortfolioAiClient(properties());
        ObjectNode payload = client.payload(task(), request());
        assertThat(payload.path("model").asText()).isEqualTo("glm-5.3-flash");
        assertThat(payload.path("thinking").path("type").asText()).isEqualTo("enabled");
        assertThat(payload.path("stream").asBoolean()).isFalse();
        assertThat(payload.path("max_tokens").asInt()).isEqualTo(8192);
        assertThat(payload.path("temperature").asDouble()).isEqualTo(1.0);
        assertThat(payload.path("top_p").asDouble()).isEqualTo(0.95);
        assertThat(payload.path("response_format").path("type").asText()).isEqualTo("json_object");
        assertThat(payload.has("user_id")).isFalse();
        assertThat(payload.path("user").asText()).matches("wf_[0-9a-f]{64}");
        assertThat(payload.toString()).doesNotContain("test-fake-key", "987654", "原始秘密", "13800138000");
        assertThat(payload.path("messages").get(0).path("content").asText()).contains("协议版本：ai-portfolio-v1", "可信能力契约");
        assertThat(payload.path("messages").get(1).path("content").asText()).contains("精简文字");
    }

    /** 实际 HTTP 请求只使用 TokenHub 端点和密钥，授权 COS 链接进入安全投影。 */
    @Test
    void transportSendsTokenHubEndpointCredentialsJsonAndOnlySelectedWorkCosLinks() throws Exception {
        PortfolioAiProperties properties = properties();
        properties.setBaseUrl(properties.getBaseUrl() + "/");
        TokenHubPortfolioAiClient client = spy(new TokenHubPortfolioAiClient(properties));
        HttpClient transport = mock(HttpClient.class);
        doReturn(transport).when(client).createHttpClient(any());
        HttpResponse<byte[]> response = mock(HttpResponse.class);
        when(response.statusCode()).thenReturn(200);
        when(response.headers()).thenReturn(HttpHeaders.of(Map.of(), (name, value) -> true));
        when(response.body()).thenReturn("""
                {"model":"glm-5.3-flash","choices":[{"finish_reason":"stop","message":{"role":"assistant","content":"{}"}}]}
                """.getBytes(StandardCharsets.UTF_8));
        when(transport.sendAsync(any(HttpRequest.class), any(HttpResponse.BodyHandler.class)))
                .thenReturn(CompletableFuture.completedFuture(response));
        ObjectNode request = request();
        request.withObject("modelConfig").put("baseUrl", properties.getBaseUrl());
        var sources = request.withObject("facts").withArray("sources");
        sources.addObject().put("sourceNo", 1).put("kind", "WORK").put("mediaType", "VIDEO")
                .put("sourceId", "private-work-id").put("canBind", true)
                .put("mediaUrl", "https://cos.example.test/WF1/work/video/selected.mp4")
                .put("coverUrl", "https://cos.example.test/WF1/work/image/selected.jpg");
        sources.addObject().put("sourceNo", 2).put("kind", "WORK").put("canBind", false)
                .put("mediaUrl", "https://cos.example.test/unavailable.mp4");
        sources.addObject().put("sourceNo", 3).put("kind", "MEMBER_PORTFOLIO").put("canBind", true)
                .put("mediaUrl", "https://cos.example.test/private-member.mp4")
                .put("coverUrl", "https://cos.example.test/private-member.jpg");

        assertThat(client.generate(task(), request, Duration.ofSeconds(2))).isEqualTo("{}");

        ArgumentCaptor<HttpRequest> captured = ArgumentCaptor.forClass(HttpRequest.class);
        verify(transport).sendAsync(captured.capture(), any(HttpResponse.BodyHandler.class));
        HttpRequest http = captured.getValue();
        assertThat(http.uri().toString()).isEqualTo("https://tokenhub.tencentmaas.com/v1/chat/completions");
        assertThat(http.method()).isEqualTo("POST");
        assertThat(http.headers().firstValue("Authorization")).contains("Bearer test-fake-key");
        assertThat(http.headers().firstValue("Content-Type")).contains("application/json");
        ObjectNode payload = StrictAiJson.parseObject(body(http), 200000, 24);
        assertThat(payload.path("model").asText()).isEqualTo("glm-5.3-flash");
        assertThat(payload.path("thinking").path("type").asText()).isEqualTo("enabled");
        assertThat(payload.path("response_format").path("type").asText()).isEqualTo("json_object");
        assertThat(payload.path("user").asText()).matches("wf_[0-9a-f]{64}");
        assertThat(payload.has("user_id")).isFalse();
        assertThat(payload.toString()).contains("selected.mp4", "selected.jpg")
                .doesNotContain("test-fake-key", "private-work-id", "unavailable.mp4", "private-member", "13800138000");
    }

    /** 供应商迁移后的旧冻结任务或不完整冻结配置均不可访问网络。 */
    @Test
    void legacyProviderMissingFieldsAndChangedFrozenModelsNeverReachTransport() {
        TokenHubPortfolioAiClient client = spy(new TokenHubPortfolioAiClient(properties()));
        ObjectNode legacy = request();
        legacy.withObject("modelConfig").put("provider", "DEEPSEEK").put("model", "deepseek-flash")
                .put("baseUrl", "https://api.deepseek.com");
        ObjectNode changedModel = request(); changedModel.withObject("modelConfig").put("model", "glm-5.3");
        ObjectNode changedEndpoint = request(); changedEndpoint.withObject("modelConfig").put("baseUrl", "https://other.example.test/v1");
        for (ObjectNode invalid : List.of(legacy, changedModel, changedEndpoint)) { rejectedWithoutTransport(client, invalid); }
        for (String field : List.of("provider", "model", "baseUrl")) {
            ObjectNode incomplete = request(); incomplete.withObject("modelConfig").remove(field);
            rejectedWithoutTransport(client, incomplete);
        }
        verify(client, never()).createHttpClient(any());
    }

    /** 缺省采样值沿当前 GLM 契约，不沿用旧模型默认温度。 */
    @Test
    void payloadUsesGlmDefaultsWhenFrozenOptionalSamplingFieldsAreAbsent() {
        ObjectNode request = request(); request.withObject("modelConfig").remove(List.of("temperature", "maxTokens"));
        ObjectNode payload = new TokenHubPortfolioAiClient(properties()).payload(task(), request);
        assertThat(payload.path("temperature").asDouble()).isEqualTo(1.0);
        assertThat(payload.path("max_tokens").asInt()).isEqualTo(8192);
    }

    /** 统一核对供应商契约失效分类，不把历史请求转给当前模型。 */
    private void rejectedWithoutTransport(TokenHubPortfolioAiClient client, ObjectNode request) {
        assertThatThrownBy(() -> client.generate(task(), request, Duration.ofSeconds(2)))
                .isInstanceOfSatisfying(AiProtocolException.class, error -> assertThat(error.getCode()).isEqualTo("CONTRACT_UNAVAILABLE"));
    }

    /** 读取实际请求发布器，确保断言覆盖最终序列化传输体。 */
    private String body(HttpRequest request) throws Exception {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        CompletableFuture<String> result = new CompletableFuture<>();
        request.bodyPublisher().orElseThrow().subscribe(new Flow.Subscriber<ByteBuffer>() {
            @Override public void onSubscribe(Flow.Subscription subscription) { subscription.request(Long.MAX_VALUE); }
            @Override public void onNext(ByteBuffer buffer) {
                byte[] part = new byte[buffer.remaining()]; buffer.get(part); bytes.writeBytes(part);
            }
            @Override public void onError(Throwable error) { result.completeExceptionally(error); }
            @Override public void onComplete() { result.complete(bytes.toString(StandardCharsets.UTF_8)); }
        });
        return result.get(2, TimeUnit.SECONDS);
    }

    /** 已移除的模式先校验拒绝，不能再尝试加载旧模板或构建供应商请求。 */
    @Test
    void payloadRejectsRemovedModesBeforeLoadingPrompt() {
        TokenHubPortfolioAiClient client = new TokenHubPortfolioAiClient(properties());
        for (String removedMode : List.of("MODIFY_COMPONENT", "REGENERATE_PAGE")) {
            ObjectNode request = request(); request.withObject("input").put("mode", removedMode);
            assertThatThrownBy(() -> client.payload(task(), request))
                    .isInstanceOf(AiProtocolException.class).hasMessage("任务模式不支持");
        }
    }

    @Test
    void onlyStructureRetryIncludesSafeCorrectionAndNeverRejectedRawOutput() {
        AiGenerationTaskEntity task = task(); task.setErrorCode("INVALID_OUTPUT"); task.setErrorMessage("notes 应为字符串数组");
        TokenHubPortfolioAiClient client = new TokenHubPortfolioAiClient(properties());
        assertThat(client.payload(task, request()).toString()).contains("本次是同一任务因输出结构错误而重试", "notes 应为字符串数组");
        task.setErrorCode("REMOTE_BUSY");
        assertThat(client.payload(task, request()).toString()).doesNotContain("notes 应为字符串数组");
    }

    @Test
    void onlyUniqueAssistantStopWithoutToolsIsAcceptedAndReasoningIsIgnored() {
        TokenHubPortfolioAiClient client = new TokenHubPortfolioAiClient(properties());
        ObjectNode completion = StrictAiJson.parseObject("""
                {"id":"completion-1","model":"glm-5.3-flash","choices":[{"finish_reason":"stop","message":{"role":"assistant","content":"{}","reasoning_content":"private thoughts"}}]}
                """, 10000, 16);
        assertThat(client.content(completion, task())).isEqualTo("{}");
        for (String reason : List.of("length", "content_filter", "tool_calls", "aborted", "insufficient_system_resource")) {
            ObjectNode invalid = completion.deepCopy(); ((ObjectNode) invalid.path("choices").get(0)).put("finish_reason", reason);
            assertThatThrownBy(() -> client.content(invalid, task())).isInstanceOf(PortfolioAiCallException.class);
        }
        ((ObjectNode) completion.path("choices").get(0).path("message")).putArray("tool_calls");
        assertThatThrownBy(() -> client.content(completion, task())).isInstanceOf(PortfolioAiCallException.class);
    }

    @Test
    void transportCancelsSubscriptionAsSoonAsReadBytesExceedLimit() {
        TokenHubPortfolioAiClient.BoundedSubscriber subscriber = new TokenHubPortfolioAiClient.BoundedSubscriber(10);
        Flow.Subscription subscription = mock(Flow.Subscription.class); subscriber.onSubscribe(subscription);
        subscriber.onNext(List.of(ByteBuffer.wrap(new byte[6]))); subscriber.onNext(List.of(ByteBuffer.wrap(new byte[5])));
        verify(subscription).cancel();
        assertThatThrownBy(() -> subscriber.getBody().toCompletableFuture().join())
                .hasCauseInstanceOf(PortfolioAiCallException.class);
        TokenHubPortfolioAiClient.BoundedSubscriber exact = new TokenHubPortfolioAiClient.BoundedSubscriber(10);
        exact.onSubscribe(mock(Flow.Subscription.class)); exact.onNext(List.of(ByteBuffer.wrap(new byte[10]))); exact.onComplete();
        assertThat(exact.getBody().toCompletableFuture().join()).hasSize(10);
    }

    @Test
    void gzipDecompressionChecksDecodedBytesDuringReading() throws Exception {
        ByteArrayOutputStream compressed = new ByteArrayOutputStream();
        try (GZIPOutputStream gzip = new GZIPOutputStream(compressed)) { gzip.write(new byte[1000]); }
        assertThat(compressed.size()).isLessThan(100);
        assertThatThrownBy(() -> TokenHubPortfolioAiClient.decode(compressed.toByteArray(), "gzip", 100))
                .isInstanceOf(PortfolioAiCallException.class).hasMessage("模型响应字节数超过上限");
        assertThat(TokenHubPortfolioAiClient.decode(compressed.toByteArray(), "gzip", 1000)).hasSize(1000);
    }

    @Test
    void absoluteDeadlineCancelsPendingTransportEvenWhenNoIdleReadTimeoutFires() {
        TokenHubPortfolioAiClient client = spy(new TokenHubPortfolioAiClient(properties())); HttpClient transport = mock(HttpClient.class);
        doReturn(transport).when(client).createHttpClient(any());
        CompletableFuture<HttpResponse<byte[]>> pending = new CompletableFuture<>();
        when(transport.sendAsync(any(HttpRequest.class), any(HttpResponse.BodyHandler.class))).thenReturn(pending);
        long started = System.nanoTime();
        assertThatThrownBy(() -> client.generate(task(), request(), Duration.ofMillis(50)))
                .isInstanceOf(PortfolioAiCallException.class).hasMessage("模型调用超过绝对期限");
        assertThat(pending.isCancelled()).isTrue(); verify(transport).shutdownNow();
        assertThat(Duration.ofNanos(System.nanoTime() - started)).isLessThan(Duration.ofSeconds(2));
    }
}
