package com.jxc.wefolio.job.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.jxc.wefolio.job.config.PortfolioAiProperties;
import com.jxc.wefolio.job.entity.AiGenerationTaskEntity;
import com.jxc.wefolio.message.PortfolioAiTaskMessage;
import com.jxc.wefolio.service.portfolioai.AiPortfolioProtocol;
import com.jxc.wefolio.service.portfolioai.StrictAiJson;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.security.GeneralSecurityException;
import java.time.Duration;
import java.util.HexFormat;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Flow;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.zip.GZIPInputStream;

/** 腾讯云 TokenHub 非流式客户端；完整读取受绝对期限、传输及解压字节预算约束。 */
@Slf4j
@Service
@RequiredArgsConstructor
public class TokenHubPortfolioAiClient implements PortfolioAiModelClient {
    /** 运行参数和服务端密钥。 */
    private final PortfolioAiProperties properties;
    /** 供应商固定名称。 */
    private static final String PROVIDER = PortfolioAiProperties.TOKENHUB_PROVIDER;
    /** Flash 模型仅支持启用思考。 */
    private static final String THINKING_ENABLED = "enabled";
    /** 官方建议的采样参数，保持固定而不从业务输入读取。 */
    private static final double TOP_P = 0.95;
    /** 官方采样参数名。 */
    private static final String TOP_P_PARAMETER = "top_p";
    /** 兼容 API 中用于滥用检测的匿名用户标识参数。 */
    private static final String USER_PARAMETER = "user";
    /** 官方 completion 路由。 */
    private static final String COMPLETION_PATH = "/chat/completions";
    /** HMAC 摘要算法。 */
    private static final String HMAC = "HmacSHA256";
    /** 固定身份隔离前缀。 */
    private static final String USER_PREFIX = "wf_";
    /** 日志元数据安全格式，不回显业务内容。 */
    private static final String SAFE_META = "[A-Za-z0-9_.:/-]{1,160}";

    /** 冻结模型参数、固定提示词和安全 user 投影组成一次请求，不在内部重试。 */
    @Override
    public String generate(AiGenerationTaskEntity task, ObjectNode request, Duration remaining) {
        if (remaining.isZero() || remaining.isNegative()) { throw failed("TIMEOUT", PortfolioAiTaskMessage.TASK_TIMEOUT_MESSAGE, false); }
        Duration timeout = remaining.compareTo(Duration.ofSeconds(properties.getCallTimeoutSeconds())) < 0
                ? remaining : Duration.ofSeconds(properties.getCallTimeoutSeconds());
        Duration connection = timeout.compareTo(Duration.ofSeconds(properties.getConnectTimeoutSeconds())) < 0
                ? timeout : Duration.ofSeconds(properties.getConnectTimeoutSeconds());
        PortfolioAiModelContract.validate(request, properties);
        ObjectNode config = (ObjectNode) request.path("modelConfig");
        if (properties.getApiKey().isBlank()) { throw failed("AI_UNAVAILABLE", "AI 配置暂时不可用", false); }
        String frozenBaseUrl = PortfolioAiProperties.canonicalBaseUrl(config.path("baseUrl").textValue());
        ObjectNode body = payload(task, request);
        HttpRequest http = HttpRequest.newBuilder(URI.create(frozenBaseUrl + COMPLETION_PATH))
                .timeout(timeout).header("Authorization", "Bearer " + properties.getApiKey())
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body.toString(), StandardCharsets.UTF_8)).build();
        long started = System.nanoTime();
        CompletableFuture<HttpResponse<byte[]>> responseFuture;
        try (HttpClient client = createHttpClient(connection)) {
            responseFuture = client.sendAsync(http, response -> {
                log.info("高级 AI HTTP taskId={}, attempt={}, httpStatus={}", task.getId(), task.getAttemptCount(), response.statusCode());
                classifyStatus(response.statusCode());
                return new BoundedSubscriber(properties.getMaxResponseBytes());
            });
            HttpResponse<byte[]> response;
            try {
                response = responseFuture.get(timeout.toNanos(), TimeUnit.NANOSECONDS);
            } catch (TimeoutException exception) {
                responseFuture.cancel(true); client.shutdownNow();
                throw failed("REMOTE_TIMEOUT", "模型调用超过绝对期限", true);
            } catch (InterruptedException exception) {
                responseFuture.cancel(true); client.shutdownNow(); Thread.currentThread().interrupt();
                throw failed("EXECUTION_INTERRUPTED", "模型调用中断", true);
            } catch (ExecutionException exception) {
                responseFuture.cancel(true);
                if (exception.getCause() instanceof PortfolioAiCallException classified) { throw classified; }
                throw failed("REMOTE_ERROR", "模型网络调用暂时失败", true);
            }
            int status = response.statusCode();
            log.info("高级 AI 调用 taskId={}, attempt={}, provider={}, model={}, httpStatus={}, elapsedMs={}",
                    task.getId(), task.getAttemptCount(), PROVIDER, safeMeta(config.path("model")), status,
                    TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started));
            classifyStatus(status);
            byte[] decoded = decode(response.body(), response.headers().firstValue("Content-Encoding").orElse(""),
                    properties.getMaxResponseBytes());
            if (System.nanoTime() - started >= timeout.toNanos()) { throw failed("REMOTE_TIMEOUT", "模型调用超过绝对期限", true); }
            ObjectNode completion = StrictAiJson.parseObject(utf8(decoded),
                    properties.getMaxResponseBytes(), StrictAiJson.MAX_DEPTH);
            String result = content(completion, task);
            if (System.nanoTime() - started >= timeout.toNanos()) { throw failed("REMOTE_TIMEOUT", "模型调用超过绝对期限", true); }
            return result;
        }
    }

    /** 创建禁止重定向且连接期限不超过剩余时间的客户端，测试可替换传输。 */
    HttpClient createHttpClient(Duration connection) {
        return HttpClient.newBuilder().connectTimeout(connection).followRedirects(HttpClient.Redirect.NEVER).build();
    }

    /** UTF-8 必须可无损解码，不能把非法响应字节替换成占位字符后接受。 */
    private String utf8(byte[] raw) {
        try {
            return StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(raw)).toString();
        } catch (CharacterCodingException exception) { throw failed("INVALID_OUTPUT", "模型响应 UTF-8 不合法", true); }
    }

    /** 生成正确的官方参数及 HMAC 用户隔离身份，密钥不写入 JSON。 */
    ObjectNode payload(AiGenerationTaskEntity task, ObjectNode request) {
        AiPortfolioProtocol.checkVersion(request);
        PortfolioAiModelContract.validate(request, properties);
        ObjectNode config = (ObjectNode) request.path("modelConfig");
        ObjectNode body = StrictAiJson.mapper().createObjectNode();
        body.put("model", config.path("model").asText()).put("stream", false);
        JsonNode temperature = config.get("temperature");
        JsonNode maxTokens = config.has("maxTokens") ? config.get("maxTokens") : config.get("max_tokens");
        if (temperature != null && (!temperature.isNumber() || !Double.isFinite(temperature.doubleValue()) || temperature.doubleValue() < 0 || temperature.doubleValue() > 2)
                || maxTokens != null && (!maxTokens.isIntegralNumber() || !maxTokens.canConvertToInt() || maxTokens.intValue() < 1 || maxTokens.intValue() > 8192)) {
            throw failed("AI_UNAVAILABLE", "模型请求参数不合法", false);
        }
        body.put("temperature", temperature == null ? 1.0 : temperature.doubleValue());
        body.put("max_tokens", maxTokens == null ? 8192 : maxTokens.intValue());
        body.put(TOP_P_PARAMETER, TOP_P);
        body.putObject("thinking").put("type", THINKING_ENABLED);
        body.putObject("response_format").put("type", "json_object");
        body.put(USER_PARAMETER, requesterHash(task.getRequestedBy()));
        String mode = request.path("input").path("mode").asText();
        String system = resource("system.txt") + "\n" + resource(mode + ".txt") + "\n可信能力契约：\n" + AiPortfolioProtocol.contract(request);
        ObjectNode user = AiPortfolioProtocol.project(request);
        if ("INVALID_OUTPUT".equals(task.getErrorCode())) {
            system += "\n" + resource("retry.txt");
            user.putObject("retry").put("error_code", task.getErrorCode()).put("error_message", task.getErrorMessage());
        }
        var messages = body.putArray("messages");
        messages.addObject().put("role", "system").put("content", system);
        messages.addObject().put("role", "user").put("content", user.toString());
        return body;
    }

    /** 唯一 assistant、stop 及无工具调用才可继续解析中间信封。 */
    String content(ObjectNode completion, AiGenerationTaskEntity task) {
        JsonNode choices = completion.path("choices");
        if (!choices.isArray() || choices.size() != 1) { throw failed("INVALID_OUTPUT", "模型响应必须有唯一回复", true); }
        JsonNode choice = choices.get(0); JsonNode message = choice.path("message");
        String finish = choice.path("finish_reason").asText();
        log.info("高级 AI 响应 taskId={}, completionId={}, model={}, fingerprint={}, finishReason={}, usageKnown={}, promptTokens={}, completionTokens={}",
                task.getId(), safeMeta(completion.path("id")), safeMeta(completion.path("model")), safeMeta(completion.path("system_fingerprint")),
                safeMeta(choice.path("finish_reason")), completion.path("usage").isObject(),
                completion.path("usage").path("prompt_tokens").isIntegralNumber() ? completion.path("usage").path("prompt_tokens").asLong() : null,
                completion.path("usage").path("completion_tokens").isIntegralNumber() ? completion.path("usage").path("completion_tokens").asLong() : null);
        if ("insufficient_system_resource".equals(finish)) { throw failed("REMOTE_BUSY", "模型服务繁忙", true); }
        if (!"stop".equals(finish)) {
            throw failed("length".equals(finish) ? "OUTPUT_TRUNCATED" : "OUTPUT_BLOCKED", "模型未能完整生成，请调整描述", false);
        }
        if (!"assistant".equals(message.path("role").asText()) || message.hasNonNull("tool_calls") || message.hasNonNull("function_call")
                || !message.path("content").isTextual() || message.path("content").asText().isBlank()) {
            throw failed("INVALID_OUTPUT", "模型响应内容、角色或工具字段不合法", true);
        }
        return message.path("content").textValue();
    }

    /** 状态码失败只保存安全分类，不解析或记录远端错误全文。 */
    private void classifyStatus(int status) {
        if (status == 200) { return; }
        if (status == 429 || status == 500 || status == 502 || status == 503 || status == 504) {
            throw failed("REMOTE_BUSY", "模型服务繁忙", true);
        }
        if (status == 401 || status == 402 || status == 403) { throw failed("AI_UNAVAILABLE", "AI 账户配置暂时不可用", false); }
        throw failed("REMOTE_CONFIGURATION", "模型接口配置不合法", false);
    }

    /** 有界解压；读到超限即停止，不先创建无限完整解压数组。 */
    static byte[] decode(byte[] raw, String encoding, int maximum) {
        if (encoding.isBlank() || "identity".equalsIgnoreCase(encoding)) { return raw; }
        if (!"gzip".equalsIgnoreCase(encoding)) { throw failed("INVALID_OUTPUT", "模型响应编码不支持", true); }
        try (InputStream input = new GZIPInputStream(new ByteArrayInputStream(raw)); ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[8192]; int count;
            while ((count = input.read(buffer)) != -1) {
                if (output.size() + count > maximum) { throw failed("RESPONSE_TOO_LARGE", "模型响应字节数超过上限", true); }
                output.write(buffer, 0, count);
            }
            return output.toByteArray();
        } catch (IOException exception) { throw failed("INVALID_OUTPUT", "模型响应解压失败", true); }
    }

    /** 从静态生产资源读取明确版本模板，业务内容从不拼进 system。 */
    private String resource(String name) {
        try (InputStream input = getClass().getResourceAsStream("/portfolioai/" + name)) {
            if (input == null) { throw failed("CONTRACT_UNAVAILABLE", "AI 提示词版本不可用", false); }
            return new String(input.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException exception) { throw failed("CONTRACT_UNAVAILABLE", "AI 提示词版本不可用", false); }
    }

    /** 对发起人主键作服务端 HMAC，供应商不接收真实主键或个人唯一码。 */
    private String requesterHash(Long userId) {
        try {
            Mac mac = Mac.getInstance(HMAC);
            String secret = properties.getUserHmacSecret().isBlank() ? properties.getApiKey() : properties.getUserHmacSecret();
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), HMAC));
            return USER_PREFIX + HexFormat.of().formatHex(mac.doFinal(Long.toString(userId).getBytes(StandardCharsets.UTF_8)));
        } catch (GeneralSecurityException | IllegalArgumentException exception) { throw failed("AI_UNAVAILABLE", "AI 身份隔离配置不可用", false); }
    }

    /** 对供应商诊断标识使用有限白名单，异常值以固定词替代。 */
    private static String safeMeta(JsonNode value) { String text = value.asText(); return text.matches(SAFE_META) ? text : "UNKNOWN"; }
    /** 创建固定错误分类。 */
    private static PortfolioAiCallException failed(String code, String message, boolean retryable) { return new PortfolioAiCallException(code, message, retryable); }

    /** 逐块限制传输体；超限取消订阅，避免 BodyHandlers.ofByteArray 无界读入。 */
    static final class BoundedSubscriber implements HttpResponse.BodySubscriber<byte[]> {
        /** 单体最大传输字节数。 */ private final int maximum;
        /** 完成后交付有界结果。 */ private final CompletableFuture<byte[]> body = new CompletableFuture<>();
        /** 有界积累响应。 */ private final ByteArrayOutputStream output = new ByteArrayOutputStream();
        /** 传输订阅，在超限时立即取消。 */ private Flow.Subscription subscription;
        BoundedSubscriber(int maximum) { this.maximum = maximum; }
        @Override public CompletionStage<byte[]> getBody() { return body; }
        @Override public void onSubscribe(Flow.Subscription subscription) { this.subscription = subscription; subscription.request(Long.MAX_VALUE); }
        @Override public void onNext(List<ByteBuffer> buffers) {
            for (ByteBuffer buffer : buffers) {
                if ((long) output.size() + buffer.remaining() > maximum) {
                    subscription.cancel(); body.completeExceptionally(failed("RESPONSE_TOO_LARGE", "模型响应字节数超过上限", true)); return;
                }
                byte[] bytes = new byte[buffer.remaining()]; buffer.get(bytes); output.writeBytes(bytes);
            }
        }
        @Override public void onError(Throwable error) { body.completeExceptionally(error); }
        @Override public void onComplete() { body.complete(output.toByteArray()); }
    }
}
