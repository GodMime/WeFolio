package com.jxc.wefolio.service.portfoliofont;

import com.jxc.wefolio.config.CosProperties;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** 用真实签名器和 HTTP 客户端验证传输次数、错误语义及取消，不连接真实 COS。 */
class PortfolioFontStorageTest {
    /** 测试字体文件所在目录。 */
    @TempDir Path directory;
    /** 隔离测试使用的合法对象键。 */
    private static final String KEY = "WF1234/others/fonts/1/test.woff";

    /** 成功 PUT 保留 WOFF MIME、不可变缓存和签名，并返回公开域名路径。 */
    @Test void successfulUploadUsesOneSignedRequest() throws Exception {
        try (var server = new Fixture(200, ""); var budget = new PortfolioFontBudget(3000)) {
            String result = server.storage.upload(font(), KEY, budget);
            assertThat(result).isEqualTo("https://fonts.example/" + KEY);
            assertThat(server.requests).containsExactly("PUT /" + KEY);
            assertThat(server.contentType.get()).isEqualTo("font/woff");
            assertThat(server.cacheControl.get()).isEqualTo("public,max-age=31536000,immutable");
            assertThat(server.authorization.get()).startsWith("q-sign-algorithm=");
            assertThat(server.body.get()).containsExactly(1, 2, 3, 4);
        }
    }

    /** 服务错误及重定向都只发送一次原请求，后续组不受前一失败污染。 */
    @Test void putAndDeleteErrorsNeverRetryOrFollowRedirects() throws Exception {
        for (int status : List.of(302, 429, 500, 503)) {
            try (var server = new Fixture(status, "<Error><Code>InternalError</Code></Error>")) {
                assertThatThrownBy(() -> server.storage.upload(font(), KEY, new PortfolioFontBudget(2000)))
                        .isInstanceOf(IllegalStateException.class).hasMessageContaining("HTTP " + status);
                assertThatThrownBy(() -> server.storage.delete(KEY, new PortfolioFontBudget(2000)))
                        .isInstanceOf(IllegalStateException.class).hasMessageContaining("HTTP " + status);
                assertThat(server.requests).containsExactly("PUT /" + KEY, "DELETE /" + KEY);
            }
        }
    }

    /** 对端收完请求即断开连接时，幂等 DELETE 也不得触发 Apache 默认重试。 */
    @Test void connectionLossNeverRetriesEitherMethod() throws Exception {
        try (var server = new Fixture(-1, "")) {
            assertThatThrownBy(() -> server.storage.upload(font(), KEY, new PortfolioFontBudget(2000)))
                    .isInstanceOf(IOException.class);
            assertThatThrownBy(() -> server.storage.delete(KEY, new PortfolioFontBudget(2000)))
                    .isInstanceOf(IOException.class);
            assertThat(server.requests).containsExactly("PUT /" + KEY, "DELETE /" + KEY);
        }
    }

    /** 404 只在删除且明确对象不存在时成功，不吞桶错误、空正文或 XML 实体。 */
    @Test void onlyExplicitMissingObjectIsSuccessfulDeletion() throws Exception {
        for (String code : List.of("NoSuchKey", "NoSuchObject")) {
            try (var server = new Fixture(404, "<Error><Code>" + code + "</Code></Error>")) {
                assertThatCode(() -> server.storage.delete(KEY, new PortfolioFontBudget(2000))).doesNotThrowAnyException();
                assertThatThrownBy(() -> server.storage.upload(font(), KEY, new PortfolioFontBudget(2000)))
                        .isInstanceOf(IllegalStateException.class);
                assertThat(server.requests).containsExactly("DELETE /" + KEY, "PUT /" + KEY);
            }
        }
        for (String body : List.of("", "not xml", "<Error><Code>NoSuchBucket</Code></Error>",
                "<Error><Code>NoSuchKey</Code><Code>NoSuchObject</Code></Error>",
                "<!DOCTYPE Error [<!ENTITY x 'NoSuchKey'>]><Error><Code>&x;</Code></Error>")) {
            try (var server = new Fixture(404, body)) {
                assertThatThrownBy(() -> server.storage.delete(KEY, new PortfolioFontBudget(2000)))
                        .isInstanceOf(IllegalStateException.class);
                assertThat(server.requests).containsExactly("DELETE /" + KEY);
            }
        }
    }

    /** 服务无响应时受套接字预算约束，一次超时不会自动再次发送。 */
    @Test void stalledResponseTimesOutOnceForBothMethods() throws Exception {
        for (boolean delete : List.of(false, true)) {
            try (var server = new Fixture(200, "")) {
                server.stall = true;
                long start = System.nanoTime();
                assertThatThrownBy(() -> {
                    try (var budget = new PortfolioFontBudget(200)) {
                        if (delete) { server.storage.delete(KEY, budget); }
                        else { server.storage.upload(font(), KEY, budget); }
                    }
                }).isInstanceOf(IOException.class);
                assertThat(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start)).isLessThan(2000);
                assertThat(server.requests).containsExactly((delete ? "DELETE /" : "PUT /") + KEY);
            }
        }
    }

    /** 主请求取消会中止在途连接；新的删除使用独立预算且不会重新上传。 */
    @Test void cancellationAbortsIoAndDoesNotPoisonIndependentDeletion() throws Exception {
        for (boolean delete : List.of(false, true)) {
            try (var server = new Fixture(200, ""); var executor = Executors.newVirtualThreadPerTaskExecutor()) {
                server.stall = true;
                var budget = new PortfolioFontBudget(10000);
                Path file = font();
                var future = executor.submit(() -> {
                    if (delete) { server.storage.delete(KEY, budget); }
                    else { server.storage.upload(file, KEY, budget); }
                    return null;
                });
                assertThat(server.received.await(2, TimeUnit.SECONDS)).isTrue();
                budget.close();
                assertThatThrownBy(() -> future.get(2, TimeUnit.SECONDS)).hasCauseInstanceOf(IOException.class);
                assertThatThrownBy(() -> server.storage.upload(file, KEY, budget))
                        .isInstanceOf(TimeoutException.class);
                server.stall = false;
                server.release.countDown();
                try (var deletionBudget = new PortfolioFontBudget(2000)) {
                    server.storage.delete(KEY, deletionBudget);
                }
                assertThat(server.requests).containsExactly((delete ? "DELETE /" : "PUT /") + KEY, "DELETE /" + KEY);
            }
        }
    }

    /** 构造仅用于传输验证的文件，不模拟字体裁剪器。 */
    private Path font() throws IOException {
        Path file = directory.resolve("font.woff");
        Files.write(file, new byte[]{1, 2, 3, 4});
        return file;
    }

    /** 单测试独立本地端点，以实际收到请求而非 Mock 调用计数断言。 */
    private static final class Fixture implements AutoCloseable {
        /** 本地 HTTP 服务。 */
        private final HttpServer server;
        /** 真正执行签名和 HTTP 的存储适配器。 */
        private final PortfolioFontStorage storage;
        /** 收到的实际方法及路径。 */
        private final List<String> requests = new CopyOnWriteArrayList<>();
        /** 请求内容类型。 */
        private final AtomicReference<String> contentType = new AtomicReference<>();
        /** 请求缓存头。 */
        private final AtomicReference<String> cacheControl = new AtomicReference<>();
        /** 请求签名，仅使用虚构测试凭据。 */
        private final AtomicReference<String> authorization = new AtomicReference<>();
        /** 实际传输的请求体。 */
        private final AtomicReference<byte[]> body = new AtomicReference<>();
        /** 在途请求同步点。 */
        private final CountDownLatch received = new CountDownLatch(1);
        /** 阻塞响应的释放信号。 */
        private final CountDownLatch release = new CountDownLatch(1);
        /** 是否延迟响应至测试主动释放。 */
        private volatile boolean stall;

        /** 仅替换包内端点解析边界，保留真实签名器及 HTTP 执行链。 */
        private Fixture(int status, String responseBody) throws IOException {
            server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.createContext("/", exchange -> handle(exchange, status, responseBody));
            server.start();
            var properties = new CosProperties();
            properties.setSecretId("test-secret-id"); properties.setSecretKey("test-secret-key");
            properties.setBucketName("test-bucket"); properties.setRegion("ap-test");
            properties.setPublicBaseUrl("https://fonts.example/");
            storage = new PortfolioFontStorage(properties) {
                /** 只在测试中将受控端点指向当前环回服务。 */
                @Override String endpoint(String key) {
                    return "http://127.0.0.1:" + server.getAddress().getPort() + "/" + key;
                }
            };
        }

        /** 记录完整收到的请求后按当前测试响应。 */
        private void handle(HttpExchange exchange, int status, String responseBody) throws IOException {
            try (exchange) {
                requests.add(exchange.getRequestMethod() + " " + exchange.getRequestURI().getPath());
                contentType.set(exchange.getRequestHeaders().getFirst("Content-Type"));
                cacheControl.set(exchange.getRequestHeaders().getFirst("Cache-Control"));
                authorization.set(exchange.getRequestHeaders().getFirst("Authorization"));
                body.set(exchange.getRequestBody().readAllBytes());
                received.countDown();
                if (status < 0) { return; }
                if (stall) {
                    try { release.await(3, TimeUnit.SECONDS); }
                    catch (InterruptedException exception) { Thread.currentThread().interrupt(); }
                }
                byte[] bytes = responseBody.getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().set("Location", "/redirect-target");
                exchange.sendResponseHeaders(status, bytes.length == 0 ? -1 : bytes.length);
                exchange.getResponseBody().write(bytes);
            }
        }

        /** 结束本地服务并释放阻塞处理器。 */
        @Override public void close() {
            release.countDown();
            server.stop(0);
        }
    }
}
