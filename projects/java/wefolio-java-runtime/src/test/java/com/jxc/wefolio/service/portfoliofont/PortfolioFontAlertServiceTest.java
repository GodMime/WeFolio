package com.jxc.wefolio.service.portfoliofont;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;
import com.jxc.wefolio.config.PortfolioFontProperties;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import static org.assertj.core.api.Assertions.assertThat;

/** 仅向本机 HTTP 接收器验证真实告警投递与签名，绝不使用真实机器人配置。 */
class PortfolioFontAlertServiceTest {
    /** 签名与 JSON 按飞书约定发送，接口确认成功才报告送达。 */
    @Test void sendsSignedNotificationOnceToLocalReceiver() throws Exception {
        var received = new AtomicReference<JSONObject>();
        var requests = new AtomicInteger();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/notify", exchange -> {
            requests.incrementAndGet(); received.set(JSON.parseObject(exchange.getRequestBody().readAllBytes()));
            byte[] body = "{\"code\":0}".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, body.length); exchange.getResponseBody().write(body); exchange.close();
        });
        server.start();
        var properties = new PortfolioFontProperties();
        properties.getAlert().setEnabled(true);
        properties.getAlert().setWebhookUrl("http://127.0.0.1:" + server.getAddress().getPort() + "/notify");
        properties.getAlert().setWebhookSecret("local-test-secret"); properties.getAlert().setNodeId("node-test");
        var service = new PortfolioFontAlertService(properties);
        try {
            assertThat(service.notifyFailure("test-build", "SOURCE_UNAVAILABLE")).isTrue();
            assertThat(requests.get()).isEqualTo(1);
            JSONObject payload = received.get();
            assertThat(payload.getString("msg_type")).isEqualTo("text");
            assertThat(payload.getJSONObject("content").getString("text")).contains("node-test", "test-build", "SOURCE_UNAVAILABLE");
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec((payload.getString("timestamp") + "\nlocal-test-secret").getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            assertThat(payload.getString("sign")).isEqualTo(Base64.getEncoder().encodeToString(mac.doFinal(new byte[0])));
        } finally { service.close(); server.stop(0); }
    }

    /** HTTP 拒绝、机器人拒绝及重定向都不重试；独立开关关闭时没有投递。 */
    @Test void rejectedOrDisabledNotificationsNeverRetry() throws Exception {
        var requests = new AtomicInteger();
        var statusCode = new AtomicInteger(302);
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/notify", exchange -> {
            requests.incrementAndGet();
            exchange.getResponseHeaders().set("Location", "/unexpected");
            byte[] body = "{\"code\":123}".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(statusCode.get(), body.length); exchange.getResponseBody().write(body); exchange.close();
        });
        server.createContext("/unexpected", exchange -> { requests.incrementAndGet(); exchange.close(); });
        server.start();
        var properties = new PortfolioFontProperties(); properties.getAlert().setEnabled(true);
        properties.getAlert().setWebhookUrl("http://127.0.0.1:" + server.getAddress().getPort() + "/notify");
        var service = new PortfolioFontAlertService(properties);
        try {
            assertThat(service.notifyFailure("test-build", "TOOL_UNAVAILABLE")).isFalse();
            assertThat(requests.get()).isEqualTo(1);
            statusCode.set(503);
            assertThat(service.notifyFailure("test-build", "SOURCE_UNAVAILABLE")).isFalse();
            assertThat(requests.get()).isEqualTo(2);
            statusCode.set(200);
            assertThat(service.notifyFailure("test-build", "SELF_TEST_FAILED")).isFalse();
            assertThat(requests.get()).isEqualTo(3);
            properties.getAlert().setEnabled(false);
            assertThat(service.notifyFailure("test-build", "TOOL_UNAVAILABLE")).isFalse();
            assertThat(requests.get()).isEqualTo(3);
        } finally { service.close(); server.stop(0); }
    }
}
