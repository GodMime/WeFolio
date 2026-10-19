package com.jxc.wefolio.job.service;

import com.jxc.wefolio.job.config.VideoSummaryProperties;
import org.junit.jupiter.api.Test;
import java.net.http.HttpClient;
import java.net.http.HttpHeaders;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import static org.junit.jupiter.api.Assertions.*;

/** 原视频单次请求格式和稳定身份全部离线检查。 */
class TokenHubVideoSummaryClientTest {
    /** 固定测试地址无可用凭据。 */
    private static final String URL = "https://bucket.cos.ap-guangzhou.myqcloud.com/OWNER/work/video/a.mp4?sign=test";

    /** 请求只含一个原视频，轻度思考并为推理与最终正文共同预留有界输出。 */
    @Test void sendsOnlyOriginalVideoAndFixedPrompt() {
        var properties = new VideoSummaryProperties();
        var client = new TokenHubVideoSummaryClient(properties);
        var payload = client.payload(URL);
        assertEquals("glm-5.3-flash", payload.path("model").asText());
        assertEquals(16384, payload.path("max_tokens").asInt());
        assertEquals("low", payload.path("reasoning_effort").asText());
        assertEquals("enabled", payload.path("thinking").path("type").asText());
        var content = payload.path("messages").get(1).path("content");
        assertEquals(2, content.size());
        assertEquals("video_url", content.get(1).path("type").asText());
        assertEquals(URL, content.get(1).path("video_url").path("url").asText());
        assertFalse(payload.toString().contains("image_url"));
        assertFalse(payload.toString().contains("videoAudioText"));
        String prompt = payload.path("messages").get(0).path("content").asText();
        assertTrue(prompt.contains("不把字幕当成已经识别的原声"));
        assertTrue(prompt.contains("summary、observations、limitations、suggestedTitle 四个字段"));
        assertTrue(prompt.contains("文艺、简洁、有画面感"));
        assertTrue(prompt.contains("优先4至8个字，最多10个Unicode码点"));
        assertTrue(prompt.contains("无法可靠概括时返回空字符串"));
        assertTrue(prompt.contains("恰好四个顶层字段"));
        assertFalse(prompt.contains("三个字段"));
        assertFalse(prompt.contains("恰好三个顶层字段"));
        assertFalse(prompt.contains("不得增加第四个顶层字段"));
    }

    /** 临时签名变化不影响缓存分组，实际静态参数和原源变化影响输入身份。 */
    @Test void signedUrlDoesNotPolluteStableIdentity() {
        var properties = new VideoSummaryProperties(); var client = new TokenHubVideoSummaryClient(properties);
        assertEquals(client.payload(URL).path("prompt_cache_key"), client.payload(URL + "new").path("prompt_cache_key"));
        assertNotEquals(client.inputHash("a".repeat(64)), client.inputHash("b".repeat(64)));
        String before = client.promptVersion(); properties.setMaxOutputTokens(3000);
        assertNotEquals(before, client.promptVersion());
        assertTrue(client.promptVersion().length() <= 128);
    }

    /** 调用方即使绕过作品快照也不能把密钥带到不安全读取地址。 */
    @Test void rejectsUnsafeMediaAddresses() {
        var client = new TokenHubVideoSummaryClient(new VideoSummaryProperties());
        for (String url : new String[]{"http://localhost/x", "file:///a", "https://user:password@example.com/x",
                "https://example.com:444/x", "https://example.com/x#part", "bad-url"}) {
            assertThrows(IllegalArgumentException.class, () -> client.payload(url));
        }
    }

    /** 真实客户端入口复用有界传输并解析usage；替身不会访问官方端点。 */
    @Test void realEntryUsesSharedTransportAndUsageWithoutPaidNetwork() {
        var properties = new VideoSummaryProperties(); properties.setApiKey("offline-test-key");
        var client = spy(new TokenHubVideoSummaryClient(properties));
        HttpClient transport = mock(HttpClient.class); doReturn(transport).when(client).createHttpClient(any());
        HttpResponse<byte[]> response = mock(HttpResponse.class);
        when(response.statusCode()).thenReturn(200);
        when(response.headers()).thenReturn(HttpHeaders.of(Map.of(), (name, value) -> true));
        when(response.body()).thenReturn(("{\"choices\":[{\"finish_reason\":\"stop\",\"message\":{\"role\":\"assistant\",\"content\":\"{}\"}}],"
                + "\"usage\":{\"prompt_tokens\":229508,\"completion_tokens\":622,\"prompt_tokens_details\":{\"cached_tokens\":100}}}")
                .getBytes(StandardCharsets.UTF_8));
        when(transport.sendAsync(any(HttpRequest.class), any(HttpResponse.BodyHandler.class)))
                .thenReturn(CompletableFuture.completedFuture(response));
        var result = client.summarize(42, URL, Instant.now().plusSeconds(2));
        assertEquals(229508L, result.promptTokens()); assertEquals(622L, result.completionTokens());
        assertEquals(100L, result.cachedTokens());
        assertEquals(PortfolioAiModelClient.CacheStatus.KNOWN_HIT, result.cacheStatus());
        verify(transport, times(1)).sendAsync(any(HttpRequest.class), any(HttpResponse.BodyHandler.class));
    }

    /** 复现线上全部输出被推理耗尽；拒绝空摘要，但失败也必须保留已知计量。 */
    @Test void truncatedReasoningRetainsUsageWithoutReturningPartialContentOrRetrying() {
        var properties = new VideoSummaryProperties(); properties.setApiKey("offline-test-key");
        var client = spy(new TokenHubVideoSummaryClient(properties));
        HttpClient transport = mock(HttpClient.class); doReturn(transport).when(client).createHttpClient(any());
        HttpResponse<byte[]> response = mock(HttpResponse.class);
        when(response.statusCode()).thenReturn(200);
        when(response.headers()).thenReturn(HttpHeaders.of(Map.of(), (name, value) -> true));
        when(response.body()).thenReturn(("{\"choices\":[{\"finish_reason\":\"length\",\"message\":{\"role\":\"assistant\",\"content\":\"\",\"reasoning_content\":\"private reasoning\"}}],"
                + "\"usage\":{\"prompt_tokens\":89404,\"completion_tokens\":4096,\"completion_tokens_details\":{\"reasoning_tokens\":4079}}}")
                .getBytes(StandardCharsets.UTF_8));
        when(transport.sendAsync(any(HttpRequest.class), any(HttpResponse.BodyHandler.class)))
                .thenReturn(CompletableFuture.completedFuture(response));
        var error = assertThrows(PortfolioAiCallException.class, () -> client.summarize(28, URL, Instant.now().plusSeconds(2)));
        assertEquals("OUTPUT_TRUNCATED", error.getCode()); assertFalse(error.isRetryable());
        assertNotNull(error.getCompletion());
        assertNull(error.getCompletion().content());
        assertEquals(89404L, error.getCompletion().promptTokens());
        assertEquals(4096L, error.getCompletion().completionTokens());
        assertFalse(error.getMessage().contains("private reasoning"));
        verify(transport, times(1)).sendAsync(any(HttpRequest.class), any(HttpResponse.BodyHandler.class));
    }

    /** 账户拒绝为安全分类且不重试，已过期限不会创建连接。 */
    @Test void rejectsExpiredDeadlineAndClassifiesAccountFailureOnce() {
        var properties = new VideoSummaryProperties(); properties.setApiKey("offline-test-key");
        var client = spy(new TokenHubVideoSummaryClient(properties));
        assertThrows(IllegalStateException.class, () -> client.summarize(1, URL, Instant.EPOCH));
        verify(client, never()).createHttpClient(any());
        HttpClient transport = mock(HttpClient.class); doReturn(transport).when(client).createHttpClient(any());
        HttpResponse<byte[]> response = mock(HttpResponse.class); when(response.statusCode()).thenReturn(403);
        when(transport.sendAsync(any(HttpRequest.class), any(HttpResponse.BodyHandler.class)))
                .thenReturn(CompletableFuture.completedFuture(response));
        var failure = assertThrows(PortfolioAiCallException.class, () -> client.summarize(1, URL, Instant.now().plusSeconds(2)));
        assertEquals("AI_UNAVAILABLE", failure.getCode()); assertFalse(failure.isRetryable());
        verify(transport, times(1)).sendAsync(any(HttpRequest.class), any(HttpResponse.BodyHandler.class));
    }

    /** 默认关闭且百兆按十进制，显式扩大仍受保护硬界约束。 */
    @Test void validatesIndependentResourceBoundaries() {
        var properties = new VideoSummaryProperties();
        assertFalse(properties.isEnabled()); assertEquals(100_000_000, properties.getMaxVideoBytes());
        properties.validate(); properties.setEnabled(true);
        assertThrows(IllegalStateException.class, properties::validate);
        properties.setApiKey("offline-test-key"); properties.validate();
        properties.setMaxOutputTokens(16384); properties.validate();
        properties.setMaxOutputTokens(16385); assertThrows(IllegalStateException.class, properties::validate);
        properties.setMaxOutputTokens(16384);
        properties.setMaxVideoBytes(104_857_601); assertThrows(IllegalStateException.class, properties::validate);
        properties.setMaxVideoBytes(104_857_600); properties.validate();
        properties.setMaxDurationSeconds(601); assertThrows(IllegalStateException.class, properties::validate);
    }
}
