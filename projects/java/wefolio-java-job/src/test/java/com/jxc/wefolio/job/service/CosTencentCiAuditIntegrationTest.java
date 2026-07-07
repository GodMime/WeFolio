package com.jxc.wefolio.job.service;

import com.alibaba.fastjson2.JSON;
import com.jxc.wefolio.job.config.CosConfig;
import com.jxc.wefolio.job.config.CosProperties;
import com.jxc.wefolio.job.config.WorkAuditProperties;
import com.jxc.wefolio.job.dict.AuditResultDict;
import com.qcloud.cos.COSClient;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.slf4j.LoggerFactory;

import java.net.URI;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.fail;

/**
 * 腾讯云数据万象真实接口连通性测试。
 *
 * <p>默认不执行，避免日常单元测试依赖外网、真实密钥和腾讯云计费资源。需要人工验证时显式开启：
 * {@code mvn -Dtest=CosTencentCiAuditIntegrationTest -Dwefolio.tencent-ci.integration-test=true test}</p>
 */
@EnabledIfSystemProperty(named = "wefolio.tencent-ci.integration-test", matches = "true")
class CosTencentCiAuditIntegrationTest {

    /** COS SDK 底层 HTTP 日志名称，异常时会包含签名请求头，测试中关闭 */
    private static final String COS_HTTP_CLIENT_LOGGER = "com.qcloud.cos.http.DefaultCosHttpClient";

    /** 真实接口测试开关 */
    private static final String INTEGRATION_TEST_PROPERTY = "wefolio.tencent-ci.integration-test";

    /** COS 访问密钥 ID 环境变量 */
    private static final String ENV_COS_SECRET_ID = "COS_SECRET_ID";

    /** COS 访问密钥 Secret 环境变量 */
    private static final String ENV_COS_SECRET_KEY = "COS_SECRET_KEY";

    /** COS 地域环境变量 */
    private static final String ENV_COS_REGION = "COS_REGION";

    /** COS 存储桶环境变量 */
    private static final String ENV_COS_BUCKET_NAME = "COS_BUCKET_NAME";

    /** 演示视频 URL */
    private static final String DEMO_VIDEO_URL = "https://cdn2.we-folio.dingchenyong.top/demo/demo-video-1.mp4";

    /** 演示图片 URL */
    private static final String DEMO_IMAGE_URL = "https://cdn2.we-folio.dingchenyong.top/demo/demo-image-1.jpg";

    /** 视频时长秒数 */
    private static final int DEMO_VIDEO_DURATION_SECONDS = 23;

    /** 视频画面审核截帧间隔秒数 */
    private static final int VIDEO_SNAPSHOT_INTERVAL_SECONDS = 60;

    /** 23 秒视频按 60 秒间隔截 1 帧 */
    private static final int VIDEO_SNAPSHOT_COUNT = 1;

    /** 视频结果最多轮询次数 */
    private static final int VIDEO_MAX_QUERY_ATTEMPTS = 20;

    /** 视频结果轮询间隔 */
    private static final Duration VIDEO_QUERY_INTERVAL = Duration.ofSeconds(10);

    /** 腾讯云远端调用超时时间 */
    private static final int REMOTE_CALL_TIMEOUT_SECONDS = 30;

    @BeforeAll
    static void suppressCosSdkSensitiveHttpLogs() {
        ch.qos.logback.classic.Logger logger =
                (ch.qos.logback.classic.Logger) LoggerFactory.getLogger(COS_HTTP_CLIENT_LOGGER);
        logger.setLevel(ch.qos.logback.classic.Level.OFF);
    }

    @Test
    void auditImageShouldCallTencentCiImageAudit() throws Exception {
        String objectKey = objectKeyFromUrl(DEMO_IMAGE_URL);
        try (TencentCiClientFixture fixture = createFixture()) {
            Map<String, Object> requestPayload = new LinkedHashMap<>();
            requestPayload.put("bucketName", fixture.cosProperties().getBucketName());
            requestPayload.put("objectKey", objectKey);
            requestPayload.put("sourceUrl", DEMO_IMAGE_URL);

            printRawPayload("腾讯云图片审核原始入参", requestPayload, fixture.cosProperties());
            TencentCiAuditResult result = fixture.client().auditImage(objectKey);
            printRawPayload("腾讯云图片审核原始出参", result.rawPayload(), fixture.cosProperties());

            assertThat(result.terminal()).isTrue();
            assertThat(result.providerFailed()).isFalse();
            assertThat(result.ciJobId()).isNotBlank();
            assertThat(result.auditResult()).isNotEqualTo(AuditResultDict.UNKNOWN);
        }
    }

    @Test
    void auditVideoShouldSubmitTencentCiVideoAuditAndPollUntilSuccess() throws Exception {
        String objectKey = objectKeyFromUrl(DEMO_VIDEO_URL);
        assertThat(DEMO_VIDEO_DURATION_SECONDS).isLessThanOrEqualTo(VIDEO_SNAPSHOT_INTERVAL_SECONDS);

        try (TencentCiClientFixture fixture = createFixture()) {
            Map<String, Object> submitRequestPayload = new LinkedHashMap<>();
            submitRequestPayload.put("bucketName", fixture.cosProperties().getBucketName());
            submitRequestPayload.put("objectKey", objectKey);
            submitRequestPayload.put("sourceUrl", DEMO_VIDEO_URL);
            submitRequestPayload.put("snapshotIntervalSeconds", VIDEO_SNAPSHOT_INTERVAL_SECONDS);
            submitRequestPayload.put("snapshotCount", VIDEO_SNAPSHOT_COUNT);

            printRawPayload("腾讯云视频审核提交原始入参", submitRequestPayload, fixture.cosProperties());
            TencentCiAuditResult submitResult = fixture.client()
                    .submitVideo(objectKey, VIDEO_SNAPSHOT_INTERVAL_SECONDS, VIDEO_SNAPSHOT_COUNT);
            printRawPayload("腾讯云视频审核提交原始出参", submitResult.rawPayload(), fixture.cosProperties());

            assertThat(submitResult.ciJobId()).isNotBlank();
            assertThat(submitResult.providerFailed()).isFalse();

            TencentCiAuditResult finalResult = pollVideoAuditResult(
                    fixture.client(), submitResult.ciJobId(), fixture.cosProperties());
            assertThat(finalResult.terminal()).isTrue();
            assertThat(finalResult.providerFailed()).isFalse();
            assertThat(finalResult.auditResult()).isNotEqualTo(AuditResultDict.UNKNOWN);
        }
    }

    private TencentCiAuditResult pollVideoAuditResult(TencentCiAuditClient client, String ciJobId,
                                                      CosProperties cosProperties)
            throws InterruptedException {
        for (int attempt = 1; attempt <= VIDEO_MAX_QUERY_ATTEMPTS; attempt++) {
            Thread.sleep(VIDEO_QUERY_INTERVAL.toMillis());

            Map<String, Object> queryRequestPayload = new LinkedHashMap<>();
            queryRequestPayload.put("ciJobId", ciJobId);
            queryRequestPayload.put("attempt", attempt);
            queryRequestPayload.put("maxAttempts", VIDEO_MAX_QUERY_ATTEMPTS);
            queryRequestPayload.put("intervalSeconds", VIDEO_QUERY_INTERVAL.toSeconds());

            printRawPayload("腾讯云视频审核查询原始入参", queryRequestPayload, cosProperties);
            TencentCiAuditResult queryResult = client.queryVideo(ciJobId);
            printRawPayload("腾讯云视频审核查询原始出参", queryResult.rawPayload(), cosProperties);

            if (queryResult.providerFailed()) {
                fail("腾讯云视频审核任务失败: ciJobId=%s, ciState=%s, rawPayload=%s",
                        ciJobId, queryResult.ciState(),
                        AuditLogSanitizer.sanitize(queryResult.rawPayload(), cosProperties));
            }
            if (queryResult.terminal()) {
                return queryResult;
            }
        }

        fail("腾讯云视频审核结果轮询超过 %s 次仍未成功: ciJobId=%s",
                VIDEO_MAX_QUERY_ATTEMPTS, ciJobId);
        throw new IllegalStateException("轮询失败分支不可达");
    }

    private TencentCiClientFixture createFixture() {
        CosProperties cosProperties = new CosProperties();
        cosProperties.setSecretId(requireEnv(ENV_COS_SECRET_ID));
        cosProperties.setSecretKey(requireEnv(ENV_COS_SECRET_KEY));
        cosProperties.setRegion(requireEnv(ENV_COS_REGION));
        cosProperties.setBucketName(requireEnv(ENV_COS_BUCKET_NAME));

        WorkAuditProperties workAuditProperties = new WorkAuditProperties();
        workAuditProperties.setRemoteCallTimeoutSeconds(REMOTE_CALL_TIMEOUT_SECONDS);

        COSClient cosClient = new CosConfig(cosProperties, workAuditProperties).cosClient();
        TencentCiAuditClient client = new CosTencentCiAuditClient(cosClient, cosProperties);
        return new TencentCiClientFixture(cosClient, cosProperties, client);
    }

    private String requireEnv(String name) {
        String value = System.getenv(name);
        if (value == null || value.isBlank()) {
            throw new IllegalStateException("运行真实腾讯云审核测试需要设置环境变量: " + name
                    + "，并设置 -D" + INTEGRATION_TEST_PROPERTY + "=true");
        }
        return value;
    }

    private String objectKeyFromUrl(String url) throws Exception {
        String path = URI.create(url).getPath();
        assertThat(path).isNotBlank();
        return path.startsWith("/") ? path.substring(1) : path;
    }

    private void printRawPayload(String title, Object payload, CosProperties cosProperties) {
        if (payload instanceof String text) {
            System.out.println("[" + title + "] " + AuditLogSanitizer.sanitize(text, cosProperties));
            return;
        }
        System.out.println("[" + title + "] " + AuditLogSanitizer.sanitize(JSON.toJSONString(payload), cosProperties));
    }

    private record TencentCiClientFixture(
            COSClient cosClient,
            CosProperties cosProperties,
            TencentCiAuditClient client
    ) implements AutoCloseable {

        @Override
        public void close() {
            cosClient.shutdown();
        }
    }
}
