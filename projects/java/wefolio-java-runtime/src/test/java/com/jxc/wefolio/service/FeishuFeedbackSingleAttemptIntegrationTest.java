package com.jxc.wefolio.service;

import com.jxc.wefolio.config.FeedbackConfiguration;
import com.jxc.wefolio.config.FeedbackProperties;
import com.jxc.wefolio.dict.FeedbackStatusDict;
import com.jxc.wefolio.entity.FeedbackEntity;
import com.jxc.wefolio.entity.UserEntity;
import com.jxc.wefolio.mapper.UserEntityMapper;
import com.jxc.wefolio.model.FeedbackRoundSnapshot;
import org.apache.hc.client5.http.impl.classic.CloseableHttpClient;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClient;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketException;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** 飞书通知 Apache 客户端单次尝试黑盒测试。 */
class FeishuFeedbackSingleAttemptIntegrationTest {

    /** 本地端点等待上限。 */
    private static final Duration ENDPOINT_WAIT_TIMEOUT = Duration.ofSeconds(2);

    /** HTTP 请求头最大字节数。 */
    private static final int MAX_HEADER_BYTES = 16_384;

    /** Content-Length 请求头匹配规则。 */
    private static final Pattern CONTENT_LENGTH_PATTERN = Pattern.compile(
            "(?i)\\r\\nContent-Length:\\s*(\\d+)\\r\\n");

    /** 飞书成功响应。 */
    private static final String FEISHU_SUCCESS_RESPONSE = "HTTP/1.1 200 OK\r\n"
            + "Content-Type: application/json\r\n"
            + "Content-Length: 10\r\n"
            + "Connection: close\r\n\r\n"
            + "{\"code\":0}";

    /** 远端收完整请求后断开连接时，一次通知只能建立并发送一次请求。 */
    @Test
    void droppedConnectionMakesNotifierSendExactlyOnce() throws Exception {
        FeedbackConfiguration configuration = new FeedbackConfiguration();
        RawEndpoint endpoint = RawEndpoint.dropConnections();
        try (endpoint;
             CloseableHttpClient httpClient = configuration.feedbackFeishuHttpClient(
                     properties(endpoint.url()))) {
            RestClient restClient = configuration.feedbackFeishuRestClient(httpClient);
            FeishuFeedbackNotifier notifier = notifier(properties(endpoint.url()), restClient);

            assertThatThrownBy(() -> notifier.notifyCreated(mutationResult()))
                    .isInstanceOf(RuntimeException.class);
            assertThat(endpoint.awaitRequest(ENDPOINT_WAIT_TIMEOUT)).isTrue();
        }

        assertThat(endpoint.failure()).isNull();
        assertThat(endpoint.connectionCount()).isEqualTo(1);
        assertThat(endpoint.requestCount()).isEqualTo(1);
        assertThat(endpoint.firstRequestLine()).isEqualTo("POST /feedback HTTP/1.1");
    }

    /** 307 响应不得把含签名的通知载荷转发到第二端点。 */
    @Test
    void redirectResponseIsNotFollowed() throws Exception {
        FeedbackConfiguration configuration = new FeedbackConfiguration();
        RawEndpoint redirectTarget = RawEndpoint.respondWith(FEISHU_SUCCESS_RESPONSE);
        String redirectResponse = "HTTP/1.1 307 Temporary Redirect\r\n"
                + "Location: " + redirectTarget.url() + "\r\n"
                + "Content-Length: 0\r\n"
                + "Connection: close\r\n\r\n";
        RawEndpoint firstEndpoint = RawEndpoint.respondWith(redirectResponse);
        try (redirectTarget;
             firstEndpoint;
             CloseableHttpClient httpClient = configuration.feedbackFeishuHttpClient(
                     properties(firstEndpoint.url()))) {
            RestClient restClient = configuration.feedbackFeishuRestClient(httpClient);
            FeishuFeedbackNotifier notifier = notifier(properties(firstEndpoint.url()), restClient);

            assertThatCode(() -> notifier.notifyCreated(mutationResult()))
                    .doesNotThrowAnyException();
            assertThat(firstEndpoint.awaitRequest(ENDPOINT_WAIT_TIMEOUT)).isTrue();
        }

        assertThat(firstEndpoint.failure()).isNull();
        assertThat(redirectTarget.failure()).isNull();
        assertThat(firstEndpoint.requestCount()).isEqualTo(1);
        assertThat(redirectTarget.requestCount()).isZero();
    }

    /** 构造指向本地原始 HTTP 端点的配置。 */
    private FeedbackProperties properties(String webhookUrl) {
        FeedbackProperties properties = new FeedbackProperties();
        properties.setWebhookUrl(webhookUrl);
        properties.setWebhookSecret("test-feedback-secret");
        return properties;
    }

    /** 构造仅模拟数据库上下文、保留真实 HTTP 链路的通知器。 */
    private FeishuFeedbackNotifier notifier(FeedbackProperties properties, RestClient restClient) {
        UserEntityMapper userEntityMapper = mock(UserEntityMapper.class);
        UserEntity user = new UserEntity();
        user.setUniqueCode("WFSINGLE001");
        when(userEntityMapper.selectById(7L)).thenReturn(user);
        return new FeishuFeedbackNotifier(
                properties, userEntityMapper, mock(CosService.class), restClient);
    }

    /** 构造不含附件的一次新建事务结果。 */
    private FeedbackTransactionService.MutationResult mutationResult() {
        FeedbackEntity feedback = new FeedbackEntity();
        feedback.setFeedbackNo("FB0123456789abcdef0123456789abcdef");
        feedback.setUserId(7L);
        feedback.setStatus(FeedbackStatusDict.PROCESSING.getCode());
        FeedbackRoundSnapshot round = new FeedbackRoundSnapshot();
        round.setRoundNo(1);
        round.setDescription("掉线端点不重试测试");
        round.setSubmittedAt(LocalDateTime.of(2026, 8, 25, 20, 0));
        round.setAttachments(List.of());
        return new FeedbackTransactionService.MutationResult(feedback, round, true);
    }

    /** 可按测试配置返回响应或主动断连的本地原始 HTTP 端点。 */
    private static final class RawEndpoint implements AutoCloseable {

        /** 本地监听套接字。 */
        private final ServerSocket serverSocket;

        /** 完整收到请求后返回的响应；为空时直接断连。 */
        private final byte[] responseBytes;

        /** 端点是否继续接收连接。 */
        private final AtomicBoolean running = new AtomicBoolean(true);

        /** 已建立连接数。 */
        private final AtomicInteger connectionCount = new AtomicInteger();

        /** 已完整接收请求数。 */
        private final AtomicInteger requestCount = new AtomicInteger();

        /** 首个请求行。 */
        private final AtomicReference<String> firstRequestLine = new AtomicReference<>();

        /** 服务线程异常。 */
        private final AtomicReference<Throwable> failure = new AtomicReference<>();

        /** 首个完整请求信号。 */
        private final CountDownLatch requestReceived = new CountDownLatch(1);

        /** 服务线程终止信号。 */
        private final CountDownLatch stopped = new CountDownLatch(1);

        /** 服务线程。 */
        private final Thread serverThread;

        /** 创建并立即启动本地端点。 */
        private RawEndpoint(String response) throws IOException {
            serverSocket = new ServerSocket();
            serverSocket.bind(new InetSocketAddress(InetAddress.getByName("127.0.0.1"), 0));
            serverSocket.setSoTimeout(200);
            responseBytes = response == null ? null : response.getBytes(StandardCharsets.UTF_8);
            serverThread = Thread.ofPlatform()
                    .name("feedback-raw-endpoint")
                    .daemon(true)
                    .start(this::serve);
        }

        /** 创建收到完整请求后直接断连的端点。 */
        private static RawEndpoint dropConnections() throws IOException {
            return new RawEndpoint(null);
        }

        /** 创建收到完整请求后返回指定原始 HTTP 响应的端点。 */
        private static RawEndpoint respondWith(String response) throws IOException {
            return new RawEndpoint(response);
        }

        /** 返回本地 Webhook 地址。 */
        private String url() {
            return "http://127.0.0.1:" + serverSocket.getLocalPort() + "/feedback";
        }

        /** 等待首个完整请求。 */
        private boolean awaitRequest(Duration timeout) throws InterruptedException {
            return requestReceived.await(timeout.toMillis(), TimeUnit.MILLISECONDS);
        }

        /** 返回连接计数。 */
        private int connectionCount() {
            return connectionCount.get();
        }

        /** 返回完整请求计数。 */
        private int requestCount() {
            return requestCount.get();
        }

        /** 返回首个请求行。 */
        private String firstRequestLine() {
            return firstRequestLine.get();
        }

        /** 返回服务线程异常。 */
        private Throwable failure() {
            return failure.get();
        }

        /** 循环接收请求并主动断开。 */
        private void serve() {
            try {
                while (running.get()) {
                    acceptAndHandle();
                }
            } catch (SocketException exception) {
                if (running.get()) {
                    failure.compareAndSet(null, exception);
                }
            } catch (IOException exception) {
                failure.compareAndSet(null, exception);
            } finally {
                stopped.countDown();
            }
        }

        /** 接收一个完整请求后返回配置响应或以 RST 断开。 */
        private void acceptAndHandle() throws IOException {
            try (Socket socket = serverSocket.accept()) {
                connectionCount.incrementAndGet();
                socket.setSoTimeout(2_000);
                if (readCompleteRequest(socket.getInputStream())) {
                    requestCount.incrementAndGet();
                    requestReceived.countDown();
                }
                if (responseBytes == null) {
                    socket.setSoLinger(true, 0);
                } else {
                    socket.getOutputStream().write(responseBytes);
                    socket.getOutputStream().flush();
                }
            } catch (SocketTimeoutException exception) {
                if (!running.get()) {
                    return;
                }
            }
        }

        /** 读取请求头与 Content-Length 指定的完整请求体。 */
        private boolean readCompleteRequest(InputStream inputStream) throws IOException {
            ByteArrayOutputStream headerBytes = new ByteArrayOutputStream();
            int matchedDelimiterBytes = 0;
            while (headerBytes.size() < MAX_HEADER_BYTES) {
                int nextByte = inputStream.read();
                if (nextByte < 0) {
                    return false;
                }
                headerBytes.write(nextByte);
                matchedDelimiterBytes = delimiterProgress(matchedDelimiterBytes, nextByte);
                if (matchedDelimiterBytes == 4) {
                    break;
                }
            }
            String headers = headerBytes.toString(StandardCharsets.ISO_8859_1);
            firstRequestLine.compareAndSet(null, headers.lines().findFirst().orElse(null));
            Matcher matcher = CONTENT_LENGTH_PATTERN.matcher(headers);
            if (!matcher.find()) {
                return true;
            }
            int contentLength = Integer.parseInt(matcher.group(1));
            return inputStream.readNBytes(contentLength).length == contentLength;
        }

        /** 推进 HTTP 请求头结束符匹配状态。 */
        private int delimiterProgress(int matchedBytes, int nextByte) {
            int[] delimiter = {'\r', '\n', '\r', '\n'};
            if (nextByte == delimiter[matchedBytes]) {
                return matchedBytes + 1;
            }
            return nextByte == delimiter[0] ? 1 : 0;
        }

        /** 关闭监听并等待服务线程退出。 */
        @Override
        public void close() throws Exception {
            running.set(false);
            serverSocket.close();
            if (!stopped.await(ENDPOINT_WAIT_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS)) {
                throw new IllegalStateException("本地原始 HTTP 端点未按时关闭");
            }
            serverThread.join(ENDPOINT_WAIT_TIMEOUT.toMillis());
        }
    }
}
