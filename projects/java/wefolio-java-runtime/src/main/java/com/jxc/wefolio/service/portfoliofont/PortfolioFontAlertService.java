package com.jxc.wefolio.service.portfoliofont;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;
import com.jxc.wefolio.config.PortfolioFontProperties;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.apache.hc.client5.http.classic.methods.HttpPost;
import org.apache.hc.client5.http.config.RequestConfig;
import org.apache.hc.client5.http.impl.classic.CloseableHttpClient;
import org.apache.hc.client5.http.impl.classic.HttpClients;
import org.apache.hc.core5.http.ContentType;
import org.apache.hc.core5.http.io.entity.StringEntity;
import org.apache.hc.core5.http.io.entity.EntityUtils;
import org.apache.hc.core5.util.Timeout;
import org.springframework.stereotype.Service;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;
import java.util.Map;

/** 字体节点故障的单次飞书告警，不重试、不影响旧业务结果。 */
@Slf4j
@Service
public class PortfolioFontAlertService {
    /** 飞书签名算法。 */
    private static final String HMAC_ALGORITHM = "HmacSHA256";
    /** 字体独立配置。 */
    private final PortfolioFontProperties properties;
    /** 禁用重试与重定向，网络阶段均有界。 */
    private final CloseableHttpClient client;

    /** 创建独立客户端，不复用带业务身份的请求。 */
    @SuppressWarnings("deprecation")
    public PortfolioFontAlertService(PortfolioFontProperties properties) {
        this.properties = properties;
        client = HttpClients.custom().disableAutomaticRetries().disableRedirectHandling()
                .setDefaultRequestConfig(RequestConfig.custom().setConnectionRequestTimeout(Timeout.ofMilliseconds(300))
                        .setConnectTimeout(Timeout.ofMilliseconds(300)).setResponseTimeout(Timeout.ofMilliseconds(500)).build()).build();
    }

    /** 由就绪状态转换触发一次，发送失败只输出原因码，不记录 URL 或密钥。 */
    public boolean notifyFailure(String buildId, String reasonCode) {
        var config = properties.getAlert();
        if (config == null || !config.isEnabled()) { return false; }
        try {
            if (config.getWebhookUrl() == null || config.getWebhookUrl().isBlank()) { throw new IllegalStateException(); }
            JSONObject payload = JSONObject.of("msg_type", "text", "content", Map.of("text",
                    "WeFolio 字体节点不可用\n节点：" + config.getNodeId() + "\n构建：" + buildId + "\n原因：" + reasonCode
                            + "\n已停止本节点字体生成，旧业务仍可访问。请检查节点并受控重启完成准入。"));
            if (config.getWebhookSecret() != null && !config.getWebhookSecret().isBlank()) {
                String timestamp = String.valueOf(Instant.now().getEpochSecond());
                Mac mac = Mac.getInstance(HMAC_ALGORITHM);
                mac.init(new SecretKeySpec((timestamp + "\n" + config.getWebhookSecret()).getBytes(StandardCharsets.UTF_8), HMAC_ALGORITHM));
                payload.put("timestamp", timestamp); payload.put("sign", Base64.getEncoder().encodeToString(mac.doFinal(new byte[0])));
            }
            HttpPost request = new HttpPost(config.getWebhookUrl());
            request.setEntity(new StringEntity(JSON.toJSONString(payload), ContentType.APPLICATION_JSON));
            log.info("发送字体节点告警: nodeId={}, buildId={}, reasonCode={}", config.getNodeId(), buildId, reasonCode);
            boolean delivered = client.execute(request, response -> {
                String body = response.getEntity() == null ? "" : EntityUtils.toString(response.getEntity());
                JSONObject result = JSON.parseObject(body);
                log.info("字体告警响应: httpStatus={}, code={}, legacyStatusCode={}", response.getCode(),
                        result == null ? null : result.getInteger("code"), result == null ? null : result.getInteger("StatusCode"));
                return response.getCode() == 200 && result != null
                        && (Integer.valueOf(0).equals(result.getInteger("code")) || Integer.valueOf(0).equals(result.getInteger("StatusCode")));
            });
            if (!delivered) { log.error("字体告警未送达: reasonCode=ALERT_REJECTED"); }
            return delivered;
        } catch (Exception exception) { log.error("字体告警未送达: reasonCode=ALERT_DELIVERY_FAILED"); return false; }
    }

    /** 关闭专用连接，不保留后台任务。 */
    @PreDestroy
    public void close() throws Exception { client.close(); }
}
