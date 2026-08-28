package com.jxc.wefolio.job.config;

import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.context.ConfigurationPropertiesAutoConfiguration;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 反馈附件过期清理配置测试。
 */
class FeedbackUploadCleanupPropertiesTest {

    /** 仅加载反馈附件清理配置的轻量上下文。 */
    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(ConfigurationPropertiesAutoConfiguration.class))
            .withUserConfiguration(TestPropertiesConfig.class);

    /** 验证默认值适合低峰期的有界批量清理。 */
    @Test
    void shouldProvideBoundedLowTrafficDefaults() {
        FeedbackUploadCleanupProperties properties = new FeedbackUploadCleanupProperties();

        assertThat(properties.isEnabled()).isTrue();
        assertThat(properties.getCron()).isEqualTo("0 40 3 * * ?");
        assertThat(properties.getZone()).isEqualTo("Asia/Shanghai");
        assertThat(properties.getBatchSize()).isEqualTo(200);
        assertThat(properties.getMaxBatches()).isEqualTo(10);
    }

    /** 验证 Spring 能完整绑定清理配置。 */
    @Test
    void shouldBindAllPropertiesThroughSpringBinder() {
        contextRunner.withPropertyValues(
                "feedback-upload-cleanup.enabled=false",
                "feedback-upload-cleanup.cron=0 15 4 * * ?",
                "feedback-upload-cleanup.zone=UTC",
                "feedback-upload-cleanup.batch-size=321",
                "feedback-upload-cleanup.max-batches=7"
        ).run(context -> {
            assertThat(context).hasNotFailed();
            FeedbackUploadCleanupProperties properties =
                    context.getBean(FeedbackUploadCleanupProperties.class);
            assertThat(properties.isEnabled()).isFalse();
            assertThat(properties.getCron()).isEqualTo("0 15 4 * * ?");
            assertThat(properties.getZone()).isEqualTo("UTC");
            assertThat(properties.getBatchSize()).isEqualTo(321);
            assertThat(properties.getMaxBatches()).isEqualTo(7);
        });
    }

    /** 验证批量上下界非法时上下文启动失败。 */
    @Test
    void binderShouldRejectInvalidBatchBounds() {
        assertInvalid("feedback-upload-cleanup.batch-size=0", "batch-size");
        assertInvalid("feedback-upload-cleanup.batch-size=1001", "batch-size");
        assertInvalid("feedback-upload-cleanup.max-batches=0", "max-batches");
        assertInvalid("feedback-upload-cleanup.max-batches=101", "max-batches");
    }

    /** 验证非法时区在配置绑定阶段被拒绝。 */
    @Test
    void binderShouldRejectInvalidZone() {
        assertInvalid("feedback-upload-cleanup.zone=Not/AZone", "zone");
    }

    /** 验证 application.yml 暴露全部清理环境变量。 */
    @Test
    void applicationYamlShouldBindAllCleanupEnvironmentVariables() throws IOException {
        String yaml = Files.readString(Path.of("src/main/resources/application.yml"));

        assertThat(yaml)
                .contains("enabled: ${FEEDBACK_UPLOAD_CLEANUP_ENABLED:true}")
                .contains("cron: \"${FEEDBACK_UPLOAD_CLEANUP_CRON:0 40 3 * * ?}\"")
                .contains("zone: ${FEEDBACK_UPLOAD_CLEANUP_ZONE:Asia/Shanghai}")
                .contains("batch-size: ${FEEDBACK_UPLOAD_CLEANUP_BATCH_SIZE:200}")
                .contains("max-batches: ${FEEDBACK_UPLOAD_CLEANUP_MAX_BATCHES:10}");
    }

    /** 断言指定配置会导致上下文以预期原因启动失败。 */
    private void assertInvalid(String property, String expectedMessagePart) {
        contextRunner.withPropertyValues(property).run(context -> {
            assertThat(context).hasFailed();
            assertThat(context.getStartupFailure())
                    .hasRootCauseInstanceOf(IllegalStateException.class)
                    .rootCause()
                    .hasMessageContaining(expectedMessagePart);
        });
    }

    /** 注册待测试的真实配置属性绑定。 */
    @Configuration(proxyBeanMethods = false)
    @EnableConfigurationProperties(FeedbackUploadCleanupProperties.class)
    static class TestPropertiesConfig {
    }
}
