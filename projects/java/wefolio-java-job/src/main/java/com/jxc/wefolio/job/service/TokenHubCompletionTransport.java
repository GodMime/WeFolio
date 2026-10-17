package com.jxc.wefolio.job.service;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.StreamReadConstraints;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.cfg.JsonNodeFeature;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.jxc.wefolio.service.portfolioai.AiProtocolException;
import com.jxc.wefolio.service.portfolioai.StrictAiJson;

import lombok.extern.slf4j.Slf4j;

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
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Flow;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Function;
import java.util.zip.GZIPInputStream;

import com.jxc.wefolio.job.service.PortfolioAiModelClient.Completion;
import com.jxc.wefolio.job.service.PortfolioAiModelClient.CacheStatus;

/** 不依赖作品集任务的 TokenHub 单次有界传输与计量解析，禁止内部重试。 */
@Slf4j
final class TokenHubCompletionTransport {
    /** 固定 HTTP 与响应 JSON 协议值，日志仅记录受控元数据。 */
    private static final String AUTHORIZATION_HEADER = "Authorization";
    private static final String BEARER_PREFIX = "Bearer ";
    private static final String CONTENT_TYPE_HEADER = "Content-Type";
    private static final String JSON_CONTENT_TYPE = "application/json";
    private static final String CONTENT_ENCODING_HEADER = "Content-Encoding";
    private static final String MODEL_FIELD = "model";
    private static final String CHOICES = "choices";
    private static final String MESSAGE = "message";
    private static final String FINISH_REASON = "finish_reason";
    private static final String COMPLETION_ID = "id";
    private static final String FINGERPRINT = "system_fingerprint";
    private static final String CONTENT = "content";
    private static final String ROLE = "role";
    private static final String ASSISTANT = "assistant";
    private static final String TOOL_CALLS = "tool_calls";
    private static final String FUNCTION_CALL = "function_call";
    private static final String RESOURCE_INSUFFICIENT = "insufficient_system_resource";
    private static final String FINISH_STOP = "stop";
    private static final String FINISH_LENGTH = "length";
    private static final String IDENTITY_ENCODING = "identity";
    private static final String GZIP_ENCODING = "gzip";
    private static final String UNKNOWN_META = "UNKNOWN";
    /** 保持旧作品集协议的固定失败分类，供后台摘要复用账户错误语义。 */
    private static final String ERROR_TIMEOUT = "TIMEOUT";
    private static final String ERROR_AI_UNAVAILABLE = "AI_UNAVAILABLE";
    private static final String ERROR_REMOTE_TIMEOUT = "REMOTE_TIMEOUT";
    private static final String ERROR_EXECUTION_INTERRUPTED = "EXECUTION_INTERRUPTED";
    private static final String ERROR_REMOTE_ERROR = "REMOTE_ERROR";
    private static final String ERROR_INVALID_OUTPUT = "INVALID_OUTPUT";
    private static final String ERROR_REMOTE_BUSY = "REMOTE_BUSY";
    private static final String ERROR_OUTPUT_TRUNCATED = "OUTPUT_TRUNCATED";
    private static final String ERROR_OUTPUT_BLOCKED = "OUTPUT_BLOCKED";
    private static final String ERROR_REMOTE_CONFIGURATION = "REMOTE_CONFIGURATION";
    private static final String ERROR_RESPONSE_TOO_LARGE = "RESPONSE_TOO_LARGE";
    /** 工具类只使用显式传入的配置与安全日志上下文。 */
    private TokenHubCompletionTransport() { }
    /** 日志元数据安全格式，不回显业务内容。 */
    private static final String SAFE_META = "[A-Za-z0-9_.:/-]{1,160}";
    /** 供应商用量对象与核心输入、输出字段。 */
    private static final String KEY_USAGE = "usage";
    private static final String KEY_PROMPT_TOKENS = "prompt_tokens";
    private static final String KEY_COMPLETION_TOKENS = "completion_tokens";
    /** 供应商输出上限参数名，同时兼容历史冻结参数的下划线写法。 */
    private static final String MAX_TOKENS_PARAMETER = "max_tokens";
    /** 供应商报告的思考用量属于 completion_tokens，不能再次计费相加。 */
    private static final String KEY_COMPLETION_TOKENS_DETAILS = "completion_tokens_details";
    private static final String KEY_REASONING_TOKENS = "reasoning_tokens";
    /** 两种可选缓存读取量不能相加，必须一致或只出现一种合法值。 */
    private static final String KEY_PROMPT_TOKENS_DETAILS = "prompt_tokens_details";
    private static final String KEY_CACHED_TOKENS = "cached_tokens";
    private static final String KEY_CACHE_READ_TOKENS = "cache_read_tokens";
    /** 共享严格解析器的整数边界错误，仅在供应商计费用量位置允许降级未知。 */
    private static final String JSON_LONG_OVERFLOW = "JSON 整数溢出";
    /** 共享严格解析器的浮点边界错误，计费用量本来就不接受浮点数。 */
    private static final String JSON_NUMBER_OVERFLOW = "JSON 数值溢出";

    /** 一次收费请求受连接、完整响应和解压总期限约束，不持有数据库事务。 */
    static Completion complete(URI endpoint, String apiKey, ObjectNode body, Duration timeout,
            Duration connection, int maximum, String context,
            Function<Duration, HttpClient> factory) {
        if (timeout.isZero() || timeout.isNegative()) { throw failed(ERROR_TIMEOUT, "模型调用超过绝对期限", false); }
        if (apiKey == null || apiKey.isBlank()) { throw failed(ERROR_AI_UNAVAILABLE, "AI 配置暂时不可用", false); }
        HttpRequest request = HttpRequest.newBuilder(endpoint).timeout(timeout)
                .header(AUTHORIZATION_HEADER, BEARER_PREFIX + apiKey).header(CONTENT_TYPE_HEADER, JSON_CONTENT_TYPE)
                .POST(HttpRequest.BodyPublishers.ofString(body.toString(), StandardCharsets.UTF_8)).build();
        long started = System.nanoTime();
        log.info("TokenHub 请求: context={}, model={}, maxTokens={}, timeoutMs={}", context,
                safeMeta(body.path(MODEL_FIELD)), body.path(MAX_TOKENS_PARAMETER).asInt(), timeout.toMillis());
        try (HttpClient client = factory.apply(connection)) {
            CompletableFuture<HttpResponse<byte[]>> future = client.sendAsync(request, response -> {
                log.info("TokenHub HTTP: context={}, status={}", context, response.statusCode());
                classifyStatus(response.statusCode());
                return new BoundedSubscriber(maximum);
            });
            HttpResponse<byte[]> response;
            try { response = future.get(timeout.toNanos(), TimeUnit.NANOSECONDS); }
            catch (TimeoutException exception) {
                future.cancel(true); client.shutdownNow();
                throw failed(ERROR_REMOTE_TIMEOUT, "模型调用超过绝对期限", true);
            } catch (InterruptedException exception) {
                future.cancel(true); client.shutdownNow(); Thread.currentThread().interrupt();
                throw failed(ERROR_EXECUTION_INTERRUPTED, "模型调用中断", true);
            } catch (ExecutionException exception) {
                future.cancel(true);
                // 只记录异常类型，禁止输出可能包含签名地址或密钥的异常消息。
                log.warn("TokenHub 网络失败: context={}, elapsedMs={}, timeoutMs={}, exceptionType={}", context,
                        TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started), timeout.toMillis(),
                        exception.getCause() == null ? exception.getClass().getSimpleName() : exception.getCause().getClass().getSimpleName());
                if (exception.getCause() instanceof PortfolioAiCallException failure) { throw failure; }
                throw failed(ERROR_REMOTE_ERROR, "模型网络调用暂时失败", true);
            }
            classifyStatus(response.statusCode());
            byte[] decoded = decode(response.body(), response.headers().firstValue(CONTENT_ENCODING_HEADER).orElse(""), maximum);
            if (System.nanoTime() - started >= timeout.toNanos()) { throw failed(ERROR_REMOTE_TIMEOUT, "模型调用超过绝对期限", true); }
            ObjectNode completion = parseCompletion(utf8(decoded), maximum);
            // 严格 JSON 已完整接收后，正文截断或协议失败仍可能收费，不能把合法用量丢弃。
            Completion failureUsage = completionWithUsage(null, completion.path(KEY_USAGE));
            String result;
            try {
                result = content(completion, context);
                if (System.nanoTime() - started >= timeout.toNanos()) { throw failed(ERROR_REMOTE_TIMEOUT, "模型调用超过绝对期限", true); }
            } catch (PortfolioAiCallException exception) {
                throw new PortfolioAiCallException(exception.getCode(), exception.getMessage(), exception.isRetryable(), failureUsage);
            }
            Completion usage = completionWithUsage(result, completion.path(KEY_USAGE));
            log.info("TokenHub 响应: context={}, elapsedMs={}, promptTokens={}, completionTokens={}, cachedTokens={}, cacheStatus={}",
                    context, TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started), usage.promptTokens(),
                    usage.completionTokens(), usage.cachedTokens(), usage.cacheStatus());
            return usage;
        } catch (PortfolioAiCallException exception) {
            log.warn("TokenHub 调用失败: context={}, elapsedMs={}, timeoutMs={}, errorCode={}, retryable={}", context,
                    TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started), timeout.toMillis(),
                    exception.getCode(), exception.isRetryable());
            throw exception;
        } catch (AiProtocolException exception) {
            log.warn("TokenHub 协议失败: context={}, elapsedMs={}, timeoutMs={}, errorCode={}", context,
                    TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started), timeout.toMillis(), exception.getCode());
            throw exception;
        }
    }
    /**
     * 保持共享 JSON 严格边界；仅将已明确非法的计费用量结构降级为 null。
     * 正常响应沿原路径解析，其它位置的溢出、尾随内容、非法 Unicode 等仍拒绝。
     */
    private static ObjectNode parseCompletion(String text, int maxResponseBytes) {
        try {
            return StrictAiJson.parseObject(text, maxResponseBytes, StrictAiJson.MAX_DEPTH);
        } catch (AiProtocolException original) {
            if (!JSON_LONG_OVERFLOW.equals(original.getMessage()) && !JSON_NUMBER_OVERFLOW.equals(original.getMessage())) {
                throw original;
            }
            ObjectMapper mapper = StrictAiJson.mapper();
            mapper.getFactory().setStreamReadConstraints(StreamReadConstraints.builder()
                    .maxNestingDepth(StrictAiJson.MAX_DEPTH).maxStringLength(maxResponseBytes)
                    .maxNumberLength(256).build());
            mapper.getFactory().enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION);
            mapper.enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS,
                    DeserializationFeature.USE_BIG_INTEGER_FOR_INTS, DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS);
            mapper.configure(JsonNodeFeature.STRIP_TRAILING_BIGDECIMAL_ZEROES, false);
            try {
                JsonNode completion = mapper.readTree(text);
                if (!validUnicodeTree(completion)) { throw original; }
                if (completion.path(KEY_USAGE) instanceof ObjectNode usage) {
                    nullInvalidUsageValue(usage, KEY_PROMPT_TOKENS);
                    nullInvalidUsageValue(usage, KEY_COMPLETION_TOKENS);
                    nullInvalidUsageValue(usage, KEY_CACHE_READ_TOKENS);
                    if (usage.path(KEY_PROMPT_TOKENS_DETAILS) instanceof ObjectNode details) {
                        nullInvalidUsageValue(details, KEY_CACHED_TOKENS);
                    } else if (usage.has(KEY_PROMPT_TOKENS_DETAILS)) {
                        usage.putNull(KEY_PROMPT_TOKENS_DETAILS);
                    }
                } else if (completion instanceof ObjectNode object && object.has(KEY_USAGE)) {
                    object.putNull(KEY_USAGE);
                }
                return StrictAiJson.parseObject(completion.toString(), maxResponseBytes, StrictAiJson.MAX_DEPTH);
            } catch (IOException | AiProtocolException exception) { throw original; }
        }
    }

    /** 只清理指定费用字段已不合法的值，不截断或夹取为合法缓存量。 */
    private static void nullInvalidUsageValue(ObjectNode object, String field) {
        JsonNode value = object.get(field);
        if (value != null && nonNegativeLong(value) == null) { object.putNull(field); }
    }

    /** 清理非法用量结构前检查原树，字段名和文本中的非法 Unicode 不能被清理掩盖。 */
    private static boolean validUnicodeTree(JsonNode node) {
        if (node.isTextual()) { return validUnicode(node.textValue()); }
        if (node.isObject()) {
            var fields = node.fields();
            while (fields.hasNext()) {
                var field = fields.next();
                if (!validUnicode(field.getKey()) || !validUnicodeTree(field.getValue())) { return false; }
            }
        } else if (node.isArray()) {
            for (JsonNode value : node) { if (!validUnicodeTree(value)) { return false; } }
        }
        return true;
    }

    /** 与共享严格解析器一致，拒绝字符串中未配对的高位或低位代理项。 */
    private static boolean validUnicode(String value) {
        for (int index = 0; index < value.length(); index++) {
            char ch = value.charAt(index);
            if (Character.isHighSurrogate(ch)) {
                if (++index >= value.length() || !Character.isLowSurrogate(value.charAt(index))) { return false; }
            } else if (Character.isLowSurrogate(ch)) { return false; }
        }
        return true;
    }

    /** 核心用量先独立校验，缓存异常仅降级全价，不改变正常正文的生成结果。 */
    private static Completion completionWithUsage(String content, JsonNode usage) {
        Long promptTokens = nonNegativeLong(usage.path(KEY_PROMPT_TOKENS));
        Long completionTokens = nonNegativeLong(usage.path(KEY_COMPLETION_TOKENS));
        if (promptTokens == null || completionTokens == null) {
            return new Completion(content, promptTokens, completionTokens, null, CacheStatus.CORE_USAGE_UNKNOWN);
        }
        JsonNode details = usage.get(KEY_PROMPT_TOKENS_DETAILS);
        JsonNode nested = details != null && details.isObject() ? details.get(KEY_CACHED_TOKENS) : null;
        JsonNode direct = usage.get(KEY_CACHE_READ_TOKENS);
        Long nestedTokens = nonNegativeLong(nested);
        Long directTokens = nonNegativeLong(direct);
        if (details != null && !details.isObject()
                || nested != null && (nestedTokens == null || nestedTokens > promptTokens)
                || direct != null && (directTokens == null || directTokens > promptTokens)) {
            return new Completion(content, promptTokens, completionTokens, null, CacheStatus.INVALID);
        }
        if (nestedTokens != null && directTokens != null && !nestedTokens.equals(directTokens)) {
            return new Completion(content, promptTokens, completionTokens, null, CacheStatus.CONFLICT);
        }
        Long cachedTokens = nestedTokens != null ? nestedTokens : directTokens;
        CacheStatus cacheStatus = cachedTokens == null ? CacheStatus.UNKNOWN
                : cachedTokens == 0 ? CacheStatus.KNOWN_MISS : CacheStatus.KNOWN_HIT;
        return new Completion(content, promptTokens, completionTokens, cachedTokens, cacheStatus);
    }

    /** 仅无损接受非负 Long 整数，不强转字符串、浮点数或溢出值。 */
    private static Long nonNegativeLong(JsonNode value) {
        return value != null && value.isIntegralNumber() && value.canConvertToLong() && value.longValue() >= 0
                ? value.longValue() : null;
    }

    /** UTF-8 必须可无损解码，不能把非法响应字节替换成占位字符后接受。 */
    private static String utf8(byte[] raw) {
        try {
            return StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(raw)).toString();
        } catch (CharacterCodingException exception) { throw failed(ERROR_INVALID_OUTPUT, "模型响应 UTF-8 不合法", true); }
    }

    /** 唯一 assistant、stop 及无工具调用才可继续解析中间信封。 */
    static String content(ObjectNode completion, String context) {
        JsonNode choices = completion.path(CHOICES);
        if (!choices.isArray() || choices.size() != 1) { throw failed(ERROR_INVALID_OUTPUT, "模型响应必须有唯一回复", true); }
        JsonNode choice = choices.get(0); JsonNode message = choice.path(MESSAGE);
        String finish = choice.path(FINISH_REASON).asText();
        log.info("TokenHub 响应 context={}, completionId={}, model={}, fingerprint={}, finishReason={}, usageKnown={}, promptTokens={}, completionTokens={}, reasoningTokens={}, contentCharacters={}",
                context, safeMeta(completion.path(COMPLETION_ID)), safeMeta(completion.path(MODEL_FIELD)), safeMeta(completion.path(FINGERPRINT)),
                safeMeta(choice.path(FINISH_REASON)), completion.path(KEY_USAGE).isObject(),
                completion.path(KEY_USAGE).path(KEY_PROMPT_TOKENS).isIntegralNumber() ? completion.path(KEY_USAGE).path(KEY_PROMPT_TOKENS).asLong() : null,
                completion.path(KEY_USAGE).path(KEY_COMPLETION_TOKENS).isIntegralNumber() ? completion.path(KEY_USAGE).path(KEY_COMPLETION_TOKENS).asLong() : null,
                completion.path(KEY_USAGE).path(KEY_COMPLETION_TOKENS_DETAILS).path(KEY_REASONING_TOKENS).isIntegralNumber()
                        ? completion.path(KEY_USAGE).path(KEY_COMPLETION_TOKENS_DETAILS).path(KEY_REASONING_TOKENS).asLong() : null,
                message.path(CONTENT).isTextual() ? message.path(CONTENT).textValue().codePointCount(0, message.path(CONTENT).textValue().length()) : null);
        if (RESOURCE_INSUFFICIENT.equals(finish)) { throw failed(ERROR_REMOTE_BUSY, "模型服务繁忙", true); }
        if (!FINISH_STOP.equals(finish)) {
            throw failed(FINISH_LENGTH.equals(finish) ? ERROR_OUTPUT_TRUNCATED : ERROR_OUTPUT_BLOCKED, "模型未能完整生成，请调整描述", false);
        }
        if (!ASSISTANT.equals(message.path(ROLE).asText()) || message.hasNonNull(TOOL_CALLS) || message.hasNonNull(FUNCTION_CALL)
                || !message.path(CONTENT).isTextual() || message.path(CONTENT).asText().isBlank()) {
            throw failed(ERROR_INVALID_OUTPUT, "模型响应内容、角色或工具字段不合法", true);
        }
        return message.path(CONTENT).textValue();
    }

    /** 状态码失败只保存安全分类，不解析或记录远端错误全文。 */
    private static void classifyStatus(int status) {
        if (status == 200) { return; }
        if (status == 429 || status == 500 || status == 502 || status == 503 || status == 504) {
            throw failed(ERROR_REMOTE_BUSY, "模型服务繁忙", true);
        }
        if (status == 401 || status == 402 || status == 403) { throw failed(ERROR_AI_UNAVAILABLE, "AI 账户配置暂时不可用", false); }
        throw failed(ERROR_REMOTE_CONFIGURATION, "模型接口配置不合法", false);
    }

    /** 有界解压；读到超限即停止，不先创建无限完整解压数组。 */
    static byte[] decode(byte[] raw, String encoding, int maximum) {
        if (encoding.isBlank() || IDENTITY_ENCODING.equalsIgnoreCase(encoding)) { return raw; }
        if (!GZIP_ENCODING.equalsIgnoreCase(encoding)) { throw failed(ERROR_INVALID_OUTPUT, "模型响应编码不支持", true); }
        try (InputStream input = new GZIPInputStream(new ByteArrayInputStream(raw)); ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[8192]; int count;
            while ((count = input.read(buffer)) != -1) {
                if (output.size() + count > maximum) { throw failed(ERROR_RESPONSE_TOO_LARGE, "模型响应字节数超过上限", true); }
                output.write(buffer, 0, count);
            }
            return output.toByteArray();
        } catch (IOException exception) { throw failed(ERROR_INVALID_OUTPUT, "模型响应解压失败", true); }
    }

    /** 对供应商诊断标识使用有限白名单，异常值以固定词替代。 */
    private static String safeMeta(JsonNode value) { String text = value.asText(); return text.matches(SAFE_META) ? text : UNKNOWN_META; }
    /** 创建固定错误分类。 */
    private static PortfolioAiCallException failed(String code, String message, boolean retryable) { return new PortfolioAiCallException(code, message, retryable); }

    /** 逐块限制传输体；超限取消订阅，避免 BodyHandlers.ofByteArray 无界读入。 */
    static class BoundedSubscriber implements HttpResponse.BodySubscriber<byte[]> {
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
                    subscription.cancel(); body.completeExceptionally(failed(ERROR_RESPONSE_TOO_LARGE, "模型响应字节数超过上限", true)); return;
                }
                byte[] bytes = new byte[buffer.remaining()]; buffer.get(bytes); output.writeBytes(bytes);
            }
        }
        @Override public void onError(Throwable error) { body.completeExceptionally(error); }
        @Override public void onComplete() { body.complete(output.toByteArray()); }
    }
}
