package com.jxc.wefolio.job.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jxc.wefolio.job.config.VideoAudioProperties;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.io.InputStream;
import java.math.BigDecimal;
import java.net.HttpURLConnection;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/** 官方 ASR 同步调用：仅发送三个必要字段，一次请求，无隐藏重试。 */
@Component
@Slf4j
public class TokenHubAudioTranscriptionClient {
    /** 协议字段和认证头统一定义，日志不输出正文或凭据。 */
    private static final String MODEL = "model", DATA = "data", FORMAT = "voice_encode_format", WAV = "wav";
    private static final String STATUS = "status", COMPLETED = "completed", OUTPUT = "output", TEXT = "text";
    private static final String USAGE = "usage", TOTAL_TOKENS = "total_tokens", TOTAL_TOKEN = "total_token";
    private static final String REQUEST_ID = "request_id", AUTHORIZATION = "Authorization", BEARER = "Bearer ";
    private static final String CONTENT_TYPE = "Content-Type", JSON_TYPE = "application/json", POST = "POST";
    /** 每 token 十微元，保持小数精度，不能每次向分取整。 */
    private static final BigDecimal YUAN_PER_TOKEN = new BigDecimal("0.00001");
    /** 只读取密钥及有界请求配置，不输出整个配置对象。 */
    private final VideoAudioProperties properties;
    /** 最小请求与受限响应的 JSON 编解码器。 */
    private final ObjectMapper mapper;
    /** 生产固定官方端点，测试仅替换为本机模拟端点。 */
    private final URI endpoint;

    /** 生产只使用固定官方地址。 */
    @Autowired
    public TokenHubAudioTranscriptionClient(VideoAudioProperties properties, ObjectMapper mapper) {
        this(properties, mapper, URI.create(VideoAudioProperties.ENDPOINT));
    }

    /** 测试可注入回环 HTTP 服务，不开放生产端点配置。 */
    TokenHubAudioTranscriptionClient(VideoAudioProperties properties, ObjectMapper mapper, URI endpoint) {
        this.properties = properties; this.mapper = mapper; this.endpoint = endpoint;
    }

    /** 读取有界 WAV 并提交一次请求，返回全文、真实计量和可追踪编号。 */
    public Result transcribe(Path wav, Instant outerDeadline) throws Exception {
        long startedNanos = System.nanoTime();
        Instant deadline = VideoAudioIo.deadline(outerDeadline, properties.getHttpTimeoutSeconds());
        long bytes = Files.size(wav);
        long encodedBytes = Math.multiplyExact((bytes + 2) / 3, 4);
        if (bytes <= 0 || bytes > properties.getMaxWavBytes() || encodedBytes > properties.getMaxBase64Bytes()) {
            throw new IllegalStateException("ASR 音频或 Base64 超过字节边界");
        }
        Map<String, String> body = new LinkedHashMap<>();
        body.put(MODEL, VideoAudioProperties.MODEL);
        body.put(DATA, Base64.getEncoder().encodeToString(Files.readAllBytes(wav)));
        body.put(FORMAT, WAV);
        byte[] request = mapper.writeValueAsBytes(body);
        VideoAudioIo.check(deadline);
        log.info("视频音频 ASR 请求: model={}, wavBytes={}, encodedBytes={}", VideoAudioProperties.MODEL, bytes, encodedBytes);
        try (VideoAudioIo.Connection connection = VideoAudioIo.open(endpoint, deadline)) {
            HttpURLConnection http = connection.http();
            http.setRequestMethod(POST);
            http.setRequestProperty(AUTHORIZATION, BEARER + properties.getApiKey());
            http.setRequestProperty(CONTENT_TYPE, JSON_TYPE);
            http.setFixedLengthStreamingMode(request.length);
            http.setDoOutput(true);
            try (var output = http.getOutputStream()) { output.write(request); }
            int status = http.getResponseCode();
            if (status == 401 || status == 402) { throw new RunDisabledException(status); }
            if (status < 200 || status >= 300) { throw new IllegalStateException("ASR HTTP 请求失败: " + status); }
            if (http.getContentLengthLong() > properties.getMaxResponseBytes()) {
                throw new IllegalStateException("ASR 响应超过字节上限");
            }
            byte[] response;
            try (InputStream input = http.getInputStream()) {
                response = VideoAudioIo.read(input, properties.getMaxResponseBytes(), deadline);
            }
            Result result = parse(response);
            log.info("视频音频 ASR 响应: status={}, requestId={}, textCodePoints={}, totalTokens={}, estimatedYuan={}, elapsedMillis={}",
                    status, result.requestId(), result.text().codePointCount(0, result.text().length()),
                    result.tokens(), result.estimatedYuan(), TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedNanos));
            return result;
        }
    }

    /** 缺失文本不能变成成功空文本；只作无语义变化的换行和边界清理。 */
    Result parse(byte[] response) throws Exception {
        if (response.length > properties.getMaxResponseBytes()) { throw new IllegalStateException("ASR 响应超过字节上限"); }
        JsonNode root = mapper.readTree(response);
        if (root == null || !COMPLETED.equals(root.path(STATUS).asText()) || !root.path(OUTPUT).path(TEXT).isTextual()) {
            throw new IllegalStateException("ASR 响应状态或文本字段非法");
        }
        String text = root.path(OUTPUT).path(TEXT).textValue();
        if (text.startsWith("\uFEFF")) { text = text.substring(1); }
        text = text.replace("\r\n", "\n").replace('\r', '\n').strip();
        if (text.codePointCount(0, text.length()) > properties.getMaxTextCodePoints()
                || text.getBytes(StandardCharsets.UTF_8).length > properties.getMaxTextBytes()) {
            throw new IllegalStateException("ASR 全文超过存储边界，不截断写入");
        }
        JsonNode usage = root.path(USAGE);
        JsonNode count = usage.has(TOTAL_TOKENS) ? usage.path(TOTAL_TOKENS) : usage.path(TOTAL_TOKEN);
        Long tokens = count.isIntegralNumber() && count.canConvertToLong() && count.longValue() >= 0 ? count.longValue() : null;
        String requestId = root.path(REQUEST_ID).isTextual() ? root.path(REQUEST_ID).textValue() : null;
        // 日志编号只允许普通标识符，拒绝供应商原文注入日志。
        if (requestId != null && !requestId.matches("[A-Za-z0-9_-]{1,128}")) { requestId = null; }
        return new Result(text, tokens, requestId, tokens == null ? null : YUAN_PER_TOKEN.multiply(BigDecimal.valueOf(tokens)));
    }

    /** 原文只供仓储使用，toString 明确排除。 */
    public record Result(String text, Long tokens, String requestId, BigDecimal estimatedYuan) {
        /** 调试输出只包含安全计量与请求编号，永不输出转写全文。 */
        @Override public String toString() { return "AudioResult[tokens=" + tokens + ",requestId=" + requestId + "]"; }
    }

    /** 鉴权或付费不可用只终止本轮 ASR 发送，不修改作品审核结果。 */
    public static final class RunDisabledException extends Exception {
        /** 仅携带状态码，防止供应商原始错误包含业务内容或凭据。 */
        public RunDisabledException(int status) { super("ASR 本轮停止发送: HTTP " + status); }
    }
}
