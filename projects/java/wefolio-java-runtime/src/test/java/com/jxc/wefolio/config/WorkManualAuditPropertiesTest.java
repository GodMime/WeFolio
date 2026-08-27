package com.jxc.wefolio.config;

import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

/** 作品人工审核配置测试，固定独立环境变量和超时契约。 */
class WorkManualAuditPropertiesTest {

    /** 配置必须使用专用前缀、环境变量、生产默认地址和固定超时。 */
    @Test
    void propertiesUseDedicatedVariablesAndProductionDefaultBaseUrl() throws IOException {
        WorkManualAuditProperties properties = new WorkManualAuditProperties();
        String yaml = Files.readString(Path.of("src/main/resources/application.yml"));
        String testYaml = Files.readString(Path.of("src/test/resources/application-test.yml"));

        assertThat(properties.getReviewApiBaseUrl())
                .isEqualTo("https://api.we-folio.dingchenyong.top");
        assertThat(properties.getConnectTimeout()).isEqualTo(Duration.ofSeconds(2));
        assertThat(properties.getReadTimeout()).isEqualTo(Duration.ofSeconds(3));
        assertThat(WorkManualAuditProperties.class.getAnnotation(ConfigurationProperties.class).prefix())
                .isEqualTo("wefolio.work-audit.manual-review");
        assertThat(yaml)
                .contains("webhook-url: ${FEISHU_WORK_AUDIT_WEBHOOK_URL:}")
                .contains("webhook-secret: ${FEISHU_WORK_AUDIT_WEBHOOK_SECRET:}")
                .contains("review-api-base-url: ${WORK_AUDIT_REVIEW_API_BASE_URL:https://api.we-folio.dingchenyong.top}")
                .contains("connect-timeout: 2s")
                .contains("read-timeout: 3s");
        assertThat(testYaml)
                .contains("webhook-url: https://open.feishu.cn/open-apis/bot/v2/hook/test-work-audit-webhook")
                .contains("webhook-secret: test-work-audit-webhook-secret")
                .contains("review-api-base-url: https://api.test.wefolio.example");
    }

    /** 缺失或非正数的 HTTP 超时必须拒绝。 */
    @Test
    void propertiesRejectMissingOrNonPositiveTimeouts() {
        WorkManualAuditProperties properties = new WorkManualAuditProperties();

        assertThatIllegalArgumentException().isThrownBy(
                () -> properties.setConnectTimeout(null));
        assertThatIllegalArgumentException().isThrownBy(
                () -> properties.setConnectTimeout(Duration.ZERO));
        assertThatIllegalArgumentException().isThrownBy(
                () -> properties.setReadTimeout(Duration.ofMillis(-1)));
    }

    /** 配置对象字符串不得泄露 Webhook 或签名密钥。 */
    @Test
    void propertiesToStringDoesNotExposeSensitiveValues() {
        WorkManualAuditProperties properties = new WorkManualAuditProperties();
        properties.setWebhookUrl("https://open.feishu.cn/hook/sentinel-work-audit-token");
        properties.setWebhookSecret("sentinel-work-audit-secret");

        assertThat(properties.toString())
                .doesNotContain("sentinel-work-audit-token")
                .doesNotContain("sentinel-work-audit-secret");
    }
}
