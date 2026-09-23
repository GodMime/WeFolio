package com.jxc.wefolio.service.portfoliofont;

import com.jxc.wefolio.message.PortfolioFontMessage;

import com.jxc.wefolio.config.CosProperties;
import com.qcloud.cos.auth.BasicCOSCredentials;
import com.qcloud.cos.auth.COSSigner;
import com.qcloud.cos.endpoint.RegionEndpointBuilder;
import com.qcloud.cos.http.HttpMethodName;
import com.qcloud.cos.region.Region;
import lombok.extern.slf4j.Slf4j;
import org.apache.http.client.config.RequestConfig;
import org.apache.http.client.methods.HttpDelete;
import org.apache.http.client.methods.HttpPut;
import org.apache.http.client.methods.HttpRequestBase;
import org.apache.http.entity.FileEntity;
import org.apache.http.entity.ContentType;
import org.apache.http.impl.client.HttpClients;
import org.apache.http.util.EntityUtils;
import org.springframework.stereotype.Service;
import javax.xml.parsers.DocumentBuilderFactory;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Date;
import java.util.HashMap;
import java.util.Map;

/** 字体专用 COS 传输，复用官方签名器且明确关闭 HTTP 底层重试。 */
@Slf4j
@Service
public class PortfolioFontStorage {
    /** 自有存储配置，不从请求接收桶或地址。 */
    private final CosProperties properties;
    /** WOFF 响应 MIME。 */
    private static final String MIME = "font/woff";
    /** 不可变资源缓存策略。 */
    private static final String CACHE = "public,max-age=31536000,immutable";
    /** COS 对象不存在的两种明确错误码。 */
    private static final String NO_SUCH_KEY = "NoSuchKey";
    /** COS 兼容对象不存在错误码。 */
    private static final String NO_SUCH_OBJECT = "NoSuchObject";

    /** 注入受控自有存储。 */
    public PortfolioFontStorage(CosProperties properties) { this.properties = properties; }
    /** 返回服务端受控桶身份，用于跨配置迁移时拒绝错桶复用与删除。 */
    public String bucket() { return properties.getBucketName(); }

    /** 单次 PUT，不重定向、不使用 SDK 请求循环，也不使用 Apache 默认重试。 */
    String upload(Path file, String key, PortfolioFontBudget budget) throws Exception {
        HttpPut request = new HttpPut(endpoint(key));
        request.setEntity(new FileEntity(file.toFile(), ContentType.create(MIME)));
        request.setHeader("Content-Type", MIME); request.setHeader("Cache-Control", CACHE);
        execute(request, HttpMethodName.PUT, key, budget, false);
        budget.remaining();
        return properties.getPublicBaseUrl().replaceAll("/+$", "") + "/" + key;
    }

    /** 单次 DELETE，仅明确对象不存在视为成功。 */
    void delete(String key, PortfolioFontBudget budget) throws Exception {
        execute(new HttpDelete(endpoint(key)), HttpMethodName.DELETE, key, budget, true);
    }

    /** 使用受控地域端点，客户端不能借字体功能请求任意 URL。 */
    String endpoint(String key) {
        return "https://" + new RegionEndpointBuilder(new Region(properties.getRegion()))
                .buildGeneralApiEndpoint(properties.getBucketName()) + "/" + key;
    }

    /** 预算内完成签名与一次传输；取消通过 abort 中断当前 HTTP 请求。 */
    private void execute(HttpRequestBase request, HttpMethodName method, String key,
                         PortfolioFontBudget budget, boolean deletion) throws Exception {
        int millis = (int) Math.min(budget.remaining(), Integer.MAX_VALUE);
        var config = RequestConfig.custom().setConnectTimeout(millis).setSocketTimeout(millis)
                .setConnectionRequestTimeout(millis).build();
        try (var client = HttpClients.custom().disableAutomaticRetries().disableRedirectHandling()
                .disableCookieManagement().setDefaultRequestConfig(config).build()) {
            budget.attach(request);
            Map<String, String> headers = new HashMap<>();
            headers.put("Host", request.getURI().getHost());
            for (var header : request.getAllHeaders()) { headers.put(header.getName(), header.getValue()); }
            request.setHeader("Host", request.getURI().getHost());
            request.setHeader("Authorization", new COSSigner().buildAuthorizationStr(method, "/" + key,
                    headers, Map.of(), new BasicCOSCredentials(properties.getSecretId(), properties.getSecretKey()),
                    new Date(System.currentTimeMillis() + millis)));
            budget.remaining();
            log.info("字体 COS 请求: method={}, bucket={}, objectKey={}", method, bucket(), key);
            try (var response = client.execute(request)) {
                int status = response.getStatusLine().getStatusCode();
                log.info("字体 COS 响应: method={}, objectKey={}, status={}", method, key, status);
                if (status >= 200 && status < 300) { EntityUtils.consume(response.getEntity()); return; }
                byte[] body = response.getEntity() == null ? new byte[0] : EntityUtils.toByteArray(response.getEntity());
                if (deletion && status == 404 && objectAbsent(body)) { return; }
                throw new IllegalStateException(PortfolioFontMessage.COS_REQUEST_FAILED_PREFIX + status);
            }
        } finally { request.abort(); }
    }

    /** 安全解析受控 COS 错误正文，禁止实体与外部 DTD。 */
    private boolean objectAbsent(byte[] body) {
        try {
            var factory = DocumentBuilderFactory.newInstance();
            factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
            factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
            var document = factory.newDocumentBuilder().parse(new ByteArrayInputStream(body));
            var codes = document.getElementsByTagName("Code");
            String code = codes.getLength() == 1 ? codes.item(0).getTextContent() : "";
            return NO_SUCH_KEY.equals(code) || NO_SUCH_OBJECT.equals(code);
        } catch (Exception ignored) { return false; }
    }
}
