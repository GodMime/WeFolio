package com.jxc.wefolio.job.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jxc.wefolio.job.config.VideoAudioProperties;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/** 仅本地 HTTP 验证官方最小请求、严格响应和费用精度，不产生真实 ASR 费用。 */
class TokenHubAudioTranscriptionClientTest {
    /** 每个测试独立设置响应大小、超时与占位密钥。 */
    private final VideoAudioProperties properties = new VideoAudioProperties();
    /** 构造及检查真实序列化请求和响应。 */
    private final ObjectMapper mapper = new ObjectMapper();
    /** 存放仅供离线请求使用的微型 WAV 字节夹具。 */
    @TempDir Path temporary;

    /** 只传必要三字段，不发送格式开关、原视频 URL 或字幕参数。 */
    @Test void sendsMinimalRequestAndPreservesExactFractionalCost() throws Exception {
        AtomicReference<byte[]> body = new AtomicReference<>();
        AtomicReference<String> auth = new AtomicReference<>();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            body.set(exchange.getRequestBody().readAllBytes()); auth.set(exchange.getRequestHeaders().getFirst("Authorization"));
            byte[] response = "{\"status\":\"completed\",\"request_id\":\"request-1\",\"output\":{\"text\":\"  测试文字  \"},\"usage\":{\"total_tokens\":1861}}".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, response.length); exchange.getResponseBody().write(response); exchange.close();
        });
        server.start();
        properties.setApiKey("test-only-key");
        try {
            Path wav = temporary.resolve("audio.wav"); Files.write(wav, new byte[]{1, 2, 3});
            var result = client(server).transcribe(wav, Instant.now().plusSeconds(5));
            var sent = mapper.readTree(body.get());
            assertEquals(3, sent.size()); assertEquals(VideoAudioProperties.MODEL, sent.path("model").asText());
            assertEquals("AQID", sent.path("data").asText()); assertEquals("wav", sent.path("voice_encode_format").asText());
            assertEquals("Bearer test-only-key", auth.get()); assertEquals("测试文字", result.text());
            assertEquals(1861L, result.tokens()); assertEquals("0.01861", result.estimatedYuan().toPlainString());
            assertFalse(result.toString().contains("测试文字"));
        } finally { server.stop(0); }
    }

    /** HTTP 401/402 交给轮次熔断，不自动重试；其它 HTTP 错误也不伪装成功。 */
    @Test void authorizationAndQuotaFailureAreSingleAttempts() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        AtomicInteger status = new AtomicInteger(402);
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> { calls.incrementAndGet(); exchange.getRequestBody().readAllBytes();
            exchange.sendResponseHeaders(status.get(), -1); exchange.close(); });
        server.start();
        try {
            Path wav = temporary.resolve("audio.wav"); Files.write(wav, new byte[]{1});
            for (int code : new int[]{402, 401}) {
                status.set(code);
                assertThrows(TokenHubAudioTranscriptionClient.RunDisabledException.class,
                        () -> client(server).transcribe(wav, Instant.now().plusSeconds(5)));
            }
            status.set(429);
            assertThrows(IllegalStateException.class, () -> client(server).transcribe(wav, Instant.now().plusSeconds(5)));
            assertEquals(3, calls.get());
        } finally { server.stop(0); }
    }

    /** usage 两种官方拼写均兼容，但缺失、负数或小数必须保持未知。 */
    @Test void usageVariantsAndUnknownCountsAreNotInventedAsZero() throws Exception {
        var client = new TokenHubAudioTranscriptionClient(properties, mapper);
        assertEquals(4L, client.parse(response("", "{\"total_token\":4}")).tokens());
        assertEquals(7L, client.parse(response("", "{\"total_token\":4,\"total_tokens\":7}")).tokens());
        for (String usage : new String[]{"{}", "{\"total_tokens\":-1}", "{\"total_tokens\":1.2}", "{\"total_tokens\":\"5\"}"}) {
            var result = client.parse(response("", usage)); assertNull(result.tokens()); assertNull(result.estimatedYuan());
        }
        assertEquals("甲\n乙\n丙", client.parse(response("\uFEFF甲\r\n乙\r丙 ", "{}")).text());
    }

    /** 缺失 output.text、状态失败、损坏 JSON 和超长全文不能保存为空字符串。 */
    @Test void rejectsInvalidPayloadAndNeverTruncatesText() throws Exception {
        var client = new TokenHubAudioTranscriptionClient(properties, mapper);
        for (String response : new String[]{"{}", "[]", "not-json", "{\"status\":\"failed\"}",
                "{\"status\":\"completed\",\"output\":{}}", "{\"status\":\"completed\",\"output\":{\"text\":null}}"}) {
            assertThrows(Exception.class, () -> client.parse(response.getBytes(StandardCharsets.UTF_8)));
        }
        properties.setMaxTextCodePoints(2);
        assertEquals("😀甲", client.parse(response("😀甲", "{}")).text());
        assertThrows(IllegalStateException.class, () -> client.parse(response("😀甲乙", "{}")));
        properties.setMaxResponseBytes(2);
        assertThrows(IllegalStateException.class, () -> client.parse(response("", "{}")));
    }

    /** 无 Content-Length 的持续响应仍受实际字节边界限制。 */
    @Test void rejectsOversizedChunkedResponse() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            exchange.getRequestBody().readAllBytes(); exchange.sendResponseHeaders(200, 0);
            exchange.getResponseBody().write(new byte[128]); exchange.close();
        });
        server.start(); properties.setMaxResponseBytes(16);
        try {
            Path wav = temporary.resolve("audio.wav"); Files.write(wav, new byte[]{1});
            assertThrows(IOException.class, () -> client(server).transcribe(wav, Instant.now().plusSeconds(5)));
        } finally { server.stop(0); }
    }

    /** 每个小片段都在读取超时内到达时，总期限看门狗仍必须结束整个请求。 */
    @Test void absoluteDeadlineStopsContinuouslyDrippingResponse() throws Exception {
        CountDownLatch started = new CountDownLatch(1);
        HttpServer server = drippingServer(started);
        properties.setHttpTimeoutSeconds(1);
        try {
            Path wav = temporary.resolve("audio.wav"); Files.write(wav, new byte[]{1});
            long begin = System.nanoTime();
            assertThrows(Exception.class, () -> client(server).transcribe(wav, Instant.now().plusSeconds(10)));
            assertTrue(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - begin) < 1800);
        } finally { server.stop(0); }
    }

    /** 停机中断不需要等满 HTTP 一百二十秒，持续流读取也可及时断开。 */
    @Test void interruptionClosesResponseStreamPromptly() throws Exception {
        CountDownLatch started = new CountDownLatch(1);
        HttpServer server = drippingServer(started);
        AtomicReference<Exception> failure = new AtomicReference<>();
        Path wav = temporary.resolve("audio.wav"); Files.write(wav, new byte[]{1});
        Thread worker = Thread.ofVirtual().start(() -> {
            try { client(server).transcribe(wav, Instant.now().plusSeconds(10)); }
            catch (Exception exception) { failure.set(exception); }
        });
        try {
            assertTrue(started.await(3, TimeUnit.SECONDS));
            worker.interrupt(); worker.join(1500);
            assertFalse(worker.isAlive()); assertNotNull(failure.get());
        } finally { worker.interrupt(); server.stop(0); }
    }

    /** 测试服务持续分片写入，用于区分读取超时和绝对期限。 */
    private HttpServer drippingServer(CountDownLatch started) throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            try {
                exchange.getRequestBody().readAllBytes(); exchange.sendResponseHeaders(200, 0);
                started.countDown();
                for (int i = 0; i < 200; i++) {
                    exchange.getResponseBody().write(' '); exchange.getResponseBody().flush();
                    Thread.sleep(30);
                }
            } catch (Exception ignored) { /* 客户端主动取消属于本测试的预期结果。 */ }
            finally { exchange.close(); }
        });
        server.start(); return server;
    }

    /** 回环地址只通过包内测试构造器注入。 */
    private TokenHubAudioTranscriptionClient client(HttpServer server) {
        return new TokenHubAudioTranscriptionClient(properties, mapper, URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/"));
    }
    /** 用 JSON 序列化器保留 BOM 与真实换行等边界夹具。 */
    private byte[] response(String text, String usage) throws Exception {
        return mapper.writeValueAsBytes(Map.of("status", "completed", "output", Map.of("text", text), "usage", mapper.readTree(usage)));
    }
}
