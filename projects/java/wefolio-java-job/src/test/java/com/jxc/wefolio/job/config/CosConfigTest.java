package com.jxc.wefolio.job.config;

import com.qcloud.cos.COSClient;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.context.ConfigurationPropertiesAutoConfiguration;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * COS 客户端配置测试。
 */
class CosConfigTest {

    /** 秒到毫秒的换算值 */
    private static final int MILLIS_PER_SECOND = 1000;

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(ConfigurationPropertiesAutoConfiguration.class))
            .withUserConfiguration(TestPropertiesConfig.class, CosConfig.class)
            .withPropertyValues(
                    "cos.secret-id=test-secret-id",
                    "cos.secret-key=test-secret-key",
                    "cos.region=ap-guangzhou",
                    "cos.bucket-name=test-bucket",
                    "work-audit.remote-call-timeout-seconds=12"
            );

    /**
     * COS 客户端应使用作品审核远端调用超时配置，避免运维配置失效。
     */
    @Test
    void cosClientShouldUseWorkAuditRemoteCallTimeout() {
        contextRunner.run(context -> {
            COSClient cosClient = context.getBean(COSClient.class);

            assertThat(cosClient.getClientConfig().getConnectionTimeout()).isEqualTo(12 * MILLIS_PER_SECOND);
            assertThat(cosClient.getClientConfig().getSocketTimeout()).isEqualTo(12 * MILLIS_PER_SECOND);
            cosClient.getClientConfig().setPrintShutdownStackTrace(false);
        });
    }

    /**
     * 测试用配置属性注册。
     */
    @Configuration(proxyBeanMethods = false)
    @EnableConfigurationProperties({CosProperties.class, WorkAuditProperties.class})
    static class TestPropertiesConfig {
    }
}
