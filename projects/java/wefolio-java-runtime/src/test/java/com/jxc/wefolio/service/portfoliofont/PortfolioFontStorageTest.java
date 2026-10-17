package com.jxc.wefolio.service.portfoliofont;

import com.jxc.wefolio.config.CosProperties;
import org.apache.http.HttpHeaders;
import org.apache.http.HttpVersion;
import org.apache.http.NoHttpResponseException;
import org.apache.http.client.config.RequestConfig;
import org.apache.http.client.methods.CloseableHttpResponse;
import org.apache.http.client.methods.Configurable;
import org.apache.http.client.methods.HttpEntityEnclosingRequestBase;
import org.apache.http.client.methods.HttpRequestBase;
import org.apache.http.client.methods.HttpUriRequest;
import org.apache.http.entity.ByteArrayEntity;
import org.apache.http.impl.client.CloseableHttpClient;
import org.apache.http.impl.execchain.ClientExecChain;
import org.apache.http.impl.execchain.MainClientExec;
import org.apache.http.impl.execchain.RedirectExec;
import org.apache.http.impl.execchain.RetryExec;
import org.apache.http.message.BasicHttpResponse;
import org.apache.http.util.EntityUtils;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.test.util.ReflectionTestUtils;

import java.io.IOException;
import java.io.InterruptedIOException;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** 保留真实签名、请求实体和取消机制，以内存传输验证存储协议，不监听端口或访问网络。 */
class PortfolioFontStorageTest {
    /** 测试字体文件所在目录。 */
    @TempDir Path directory;
    /** 隔离测试使用的合法对象键。 */
    private static final String KEY = "WF1234/others/fonts/1/test.woff";
    /** 真实地域端点解析器应生成的测试桶域名。 */
    private static final String COS_HOST = "test-bucket.cos.ap-test.myqcloud.com";

    /** 成功 PUT 保留 WOFF MIME、不可变缓存和签名，并返回公开域名路径。 */
    @Test void successfulUploadUsesOneSignedRequest() throws Exception {
        try (var transport = new Fixture(200, ""); var budget = new PortfolioFontBudget(3000)) {
            String result = transport.upload(font(), budget);
            assertThat(result).isEqualTo("https://fonts.example/" + KEY);
            assertThat(transport.requests).containsExactly("PUT /" + KEY);
            assertThat(transport.contentType.get()).isEqualTo("font/woff");
            assertThat(transport.cacheControl.get()).isEqualTo("public,max-age=31536000,immutable");
            assertThat(transport.authorization.get()).startsWith("q-sign-algorithm=");
            assertThat(transport.body.get()).containsExactly(1, 2, 3, 4);
            var request = transport.sent.getFirst();
            assertThat(request.getURI().getScheme()).isEqualTo("https");
            assertThat(request.getURI().getHost()).isEqualTo(COS_HOST);
            assertThat(request.getFirstHeader(HttpHeaders.HOST).getValue()).isEqualTo(COS_HOST);
        }
    }

    /** 服务错误及重定向都只执行一次传输，客户端显式禁用重试和重定向。 */
    @Test void putAndDeleteErrorsNeverRetryOrFollowRedirects() throws Exception {
        for (int status : List.of(302, 429, 500, 503)) {
            try (var transport = new Fixture(status, "<Error><Code>InternalError</Code></Error>");
                 var uploadBudget = new PortfolioFontBudget(2000);
                 var deletionBudget = new PortfolioFontBudget(2000)) {
                assertThatThrownBy(() -> transport.upload(font(), uploadBudget))
                        .isInstanceOf(IllegalStateException.class).hasMessageContaining("HTTP " + status);
                assertThatThrownBy(() -> transport.delete(deletionBudget))
                        .isInstanceOf(IllegalStateException.class).hasMessageContaining("HTTP " + status);
                assertThat(transport.requests).containsExactly("PUT /" + KEY, "DELETE /" + KEY);
            }
        }
    }

    /** 完整读取请求体后模拟连接中断，PUT 和 DELETE 均不再次调用传输。 */
    @Test void connectionLossNeverRetriesEitherMethod() throws Exception {
        try (var transport = new Fixture(-1, "");
             var uploadBudget = new PortfolioFontBudget(2000);
             var deletionBudget = new PortfolioFontBudget(2000)) {
            assertThatThrownBy(() -> transport.upload(font(), uploadBudget))
                    .isInstanceOf(NoHttpResponseException.class);
            assertThat(transport.body.get()).containsExactly(1, 2, 3, 4);
            assertThatThrownBy(() -> transport.delete(deletionBudget))
                    .isInstanceOf(NoHttpResponseException.class);
            assertThat(transport.requests).containsExactly("PUT /" + KEY, "DELETE /" + KEY);
        }
    }

    /** 404 只在删除且明确对象不存在时成功，不吞桶错误、空正文或 XML 实体。 */
    @Test void onlyExplicitMissingObjectIsSuccessfulDeletion() throws Exception {
        for (String code : List.of("NoSuchKey", "NoSuchObject")) {
            try (var transport = new Fixture(404, "<Error><Code>" + code + "</Code></Error>");
                 var deletionBudget = new PortfolioFontBudget(2000);
                 var uploadBudget = new PortfolioFontBudget(2000)) {
                assertThatCode(() -> transport.delete(deletionBudget)).doesNotThrowAnyException();
                assertThatThrownBy(() -> transport.upload(font(), uploadBudget))
                        .isInstanceOf(IllegalStateException.class);
                assertThat(transport.requests).containsExactly("DELETE /" + KEY, "PUT /" + KEY);
            }
        }
        for (String body : List.of("", "not xml", "<Error><Code>NoSuchBucket</Code></Error>",
                "<Error><Code>NoSuchKey</Code><Code>NoSuchObject</Code></Error>",
                "<!DOCTYPE Error [<!ENTITY x 'NoSuchKey'>]><Error><Code>&x;</Code></Error>")) {
            try (var transport = new Fixture(404, body); var budget = new PortfolioFontBudget(2000)) {
                assertThatThrownBy(() -> transport.delete(budget)).isInstanceOf(IllegalStateException.class);
                assertThat(transport.requests).containsExactly("DELETE /" + KEY);
            }
        }
    }

    /** 连接、取连接和读取响应均使用剩余预算；内存传输按实际配置等待后只超时一次。 */
    @Test void stalledResponseTimesOutOnceForBothMethods() throws Exception {
        for (boolean delete : List.of(false, true)) {
            try (var transport = new Fixture(200, "")) {
                transport.stall = true;
                var clock = new AtomicLong();
                try (var budget = new PortfolioFontBudget(200, clock::get)) {
                    clock.set(TimeUnit.MILLISECONDS.toNanos(37));
                    long start = System.nanoTime();
                    assertThatThrownBy(() -> {
                        if (delete) { transport.delete(budget); }
                        else { transport.upload(font(), budget); }
                    }).isInstanceOf(SocketTimeoutException.class);
                    assertThat(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start))
                            .isBetween(150L, 2000L);
                    RequestConfig config = transport.configurations.getFirst();
                    assertThat(config.getConnectTimeout()).isEqualTo(163);
                    assertThat(config.getConnectionRequestTimeout()).isEqualTo(163);
                    assertThat(config.getSocketTimeout()).isEqualTo(163);
                    assertThat(transport.requests).containsExactly((delete ? "DELETE /" : "PUT /") + KEY);
                }
            }
        }
    }

    /** 主请求取消必须触发实际请求的取消回调；新的删除使用独立预算且不会重新上传。 */
    @Test void cancellationAbortsIoAndDoesNotPoisonIndependentDeletion() throws Exception {
        for (boolean delete : List.of(false, true)) {
            try (var transport = new Fixture(200, "");
                 var executor = Executors.newVirtualThreadPerTaskExecutor();
                 var budget = new PortfolioFontBudget(10000)) {
                transport.stall = true;
                Path file = font();
                var future = executor.submit(() -> {
                    if (delete) { transport.delete(budget); }
                    else { transport.upload(file, budget); }
                    return null;
                });
                assertThat(transport.received.await(2, TimeUnit.SECONDS)).isTrue();
                HttpRequestBase cancelledRequest = transport.sent.getFirst();
                assertThat(cancelledRequest.isAborted()).isFalse();
                budget.close();
                assertThatThrownBy(() -> future.get(2, TimeUnit.SECONDS))
                        .hasCauseInstanceOf(InterruptedIOException.class);
                assertThat(cancelledRequest.isAborted()).isTrue();
                assertThat(transport.cancelled.getCount()).isZero();
                assertThatThrownBy(() -> transport.upload(file, budget)).isInstanceOf(TimeoutException.class);
                transport.stall = false;
                try (var deletionBudget = new PortfolioFontBudget(2000)) {
                    transport.delete(deletionBudget);
                }
                assertThat(transport.sent.getLast()).isNotSameAs(cancelledRequest);
                assertThat(transport.requests).containsExactly((delete ? "DELETE /" : "PUT /") + KEY, "DELETE /" + KEY);
            }
        }
    }

    /** 真实客户端工厂保留各阶段预算，并从实际执行链移除自动重试和重定向。 */
    @Test void clientConfigurationDisablesRetriesAndRedirectsAndPreservesTimeouts() throws Exception {
        var config = RequestConfig.custom().setConnectTimeout(41).setConnectionRequestTimeout(43)
                .setSocketTimeout(47).build();
        try (var client = new PortfolioFontStorage(new CosProperties()).createClient(config)) {
            RequestConfig actual = ((Configurable) client).getConfig();
            assertThat(actual.getConnectTimeout()).isEqualTo(41);
            assertThat(actual.getConnectionRequestTimeout()).isEqualTo(43);
            assertThat(actual.getSocketTimeout()).isEqualTo(47);
            // 检查真实执行链，防止内存传输替身掩盖未来误开启重试或重定向。
            List<Object> handlers = new ArrayList<>();
            Object handler = ReflectionTestUtils.getField(client, "execChain");
            while (handler instanceof ClientExecChain) {
                handlers.add(handler);
                if (handler instanceof MainClientExec) { break; }
                handler = ReflectionTestUtils.getField(handler, "requestExecutor");
            }
            assertThat(handlers).anyMatch(MainClientExec.class::isInstance)
                    .noneMatch(RetryExec.class::isInstance).noneMatch(RedirectExec.class::isInstance);
        }
    }

    /** 构造仅用于传输验证的文件，不模拟字体裁剪器。 */
    private Path font() throws IOException {
        Path file = directory.resolve("font.woff");
        Files.write(file, new byte[]{1, 2, 3, 4});
        return file;
    }

    /** 只替换 HTTP 执行边界，记录真实请求及预算，无需本地 socket 权限。 */
    private static final class Fixture implements AutoCloseable {
        /** 真正执行签名、请求构造及预算登记的存储适配器。 */
        private final PortfolioFontStorage storage;
        /** 不创建 socket 的 HTTP 执行边界。 */
        private final CloseableHttpClient client = mock(CloseableHttpClient.class);
        /** 收到的实际方法及路径。 */
        private final List<String> requests = new CopyOnWriteArrayList<>();
        /** 实际构造并执行的请求，保留取消状态供断言。 */
        private final List<HttpRequestBase> sent = new CopyOnWriteArrayList<>();
        /** 每次构建客户端时传入的真实预算配置。 */
        private final List<RequestConfig> configurations = new CopyOnWriteArrayList<>();
        /** 请求内容类型。 */
        private final AtomicReference<String> contentType = new AtomicReference<>();
        /** 请求缓存头。 */
        private final AtomicReference<String> cacheControl = new AtomicReference<>();
        /** 请求签名，仅使用虚构测试凭据。 */
        private final AtomicReference<String> authorization = new AtomicReference<>();
        /** 从真实文件实体读取的完整请求体。 */
        private final AtomicReference<byte[]> body = new AtomicReference<>();
        /** 在途请求已登记取消回调的同步点。 */
        private final CountDownLatch received = new CountDownLatch(1);
        /** 仅由真实请求的 abort 回调释放。 */
        private final CountDownLatch cancelled = new CountDownLatch(1);
        /** 是否等待响应预算耗尽或被取消。 */
        private volatile boolean stall;

        /** 保留真实签名和受控域名解析，仅把客户端执行接入内存响应。 */
        private Fixture(int status, String responseBody) throws IOException {
            var properties = new CosProperties();
            properties.setSecretId("test-secret-id"); properties.setSecretKey("test-secret-key");
            properties.setBucketName("test-bucket"); properties.setRegion("ap-test");
            properties.setPublicBaseUrl("https://fonts.example/");
            storage = new PortfolioFontStorage(properties) {
                /** 仅替换传输实例，签名、请求、预算和响应处理仍执行生产实现。 */
                @Override CloseableHttpClient createClient(RequestConfig config) {
                    configurations.add(config);
                    return client;
                }
            };
            when(client.execute(any(HttpUriRequest.class)))
                    .thenAnswer(invocation -> respond(invocation.getArgument(0), status, responseBody));
        }

        /** 执行真实上传流程，文件实体和签名均由生产代码创建。 */
        private String upload(Path file, PortfolioFontBudget budget) throws Exception {
            return storage.upload(file, KEY, budget);
        }

        /** 执行真实删除流程，不另行实现状态码判断。 */
        private void delete(PortfolioFontBudget budget) throws Exception {
            storage.delete(KEY, budget);
        }

        /** 完整读取请求后返回内存响应，阻塞行为以真实配置及 abort 回调驱动。 */
        private CloseableHttpResponse respond(HttpRequestBase request, int status, String responseBody)
                throws IOException, InterruptedException {
            sent.add(request);
            requests.add(request.getMethod() + " " + request.getURI().getPath());
            contentType.set(header(request, HttpHeaders.CONTENT_TYPE));
            cacheControl.set(header(request, HttpHeaders.CACHE_CONTROL));
            authorization.set(header(request, HttpHeaders.AUTHORIZATION));
            body.set(request instanceof HttpEntityEnclosingRequestBase enclosing
                    ? EntityUtils.toByteArray(enclosing.getEntity()) : new byte[0]);
            assertThat(request.isAborted()).isFalse();
            if (stall) {
                request.setCancellable(() -> { cancelled.countDown(); return true; });
                received.countDown();
                int timeout = configurations.getLast().getSocketTimeout();
                assertThat(timeout).isPositive();
                if (!cancelled.await(timeout, TimeUnit.MILLISECONDS)) {
                    throw new SocketTimeoutException("内存传输等待响应超时");
                }
                assertThat(request.isAborted()).isTrue();
                throw new InterruptedIOException("内存传输已被请求取消");
            }
            received.countDown();
            if (status < 0) { throw new NoHttpResponseException("内存传输模拟连接断开"); }
            return new MemoryResponse(status, responseBody);
        }

        /** 读取实际请求头，DELETE 不存在的实体头保持为空。 */
        private String header(HttpRequestBase request, String name) {
            var header = request.getFirstHeader(name);
            return header == null ? null : header.getValue();
        }

        /** 每次实际传输都必须使用独立预算配置并关闭客户端、终止请求。 */
        @Override public void close() throws IOException {
            int count = requests.size();
            assertThat(configurations).hasSize(count);
            verify(client, times(count)).close();
            assertThat(sent).allMatch(HttpRequestBase::isAborted);
        }
    }

    /** 使用真实响应状态、实体及重定向头，正文解析继续由存储适配器执行。 */
    private static final class MemoryResponse extends BasicHttpResponse implements CloseableHttpResponse {
        /** 将测试正文转换为真实可消费的 HTTP 实体。 */
        private MemoryResponse(int status, String body) {
            super(HttpVersion.HTTP_1_1, status, "");
            setEntity(new ByteArrayEntity(body.getBytes(StandardCharsets.UTF_8)));
            setHeader(HttpHeaders.LOCATION, "/redirect-target");
        }

        /** 关闭响应时消费实体，与实际客户端的资源生命周期一致。 */
        @Override public void close() throws IOException { EntityUtils.consume(getEntity()); }
    }
}
