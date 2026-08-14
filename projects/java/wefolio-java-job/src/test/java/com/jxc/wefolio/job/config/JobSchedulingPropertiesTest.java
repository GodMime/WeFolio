package com.jxc.wefolio.job.config;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 后台调度停用配置测试。
 */
class JobSchedulingPropertiesTest {

    @Test
    void shouldProvideTenSecondDefaultDisableTimeout() {
        JobSchedulingProperties properties = new JobSchedulingProperties();

        assertThat(properties.getDisableTimeout()).isEqualTo(Duration.ofSeconds(10));
        properties.validate();
    }

    @Test
    void shouldRejectMissingOrNonPositiveDisableTimeout() {
        JobSchedulingProperties properties = new JobSchedulingProperties();

        properties.setDisableTimeout(null);
        assertThatThrownBy(properties::validate)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("job-scheduling.disable-timeout");

        properties.setDisableTimeout(Duration.ZERO);
        assertThatThrownBy(properties::validate)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("job-scheduling.disable-timeout");

        properties.setDisableTimeout(Duration.ofSeconds(-1));
        assertThatThrownBy(properties::validate)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("job-scheduling.disable-timeout");
    }

    @Test
    void applicationYamlShouldExposeDisableTimeoutEnvironmentVariable() throws IOException {
        String yaml = Files.readString(Path.of("src/main/resources/application.yml"));

        assertThat(yaml).contains(
                "disable-timeout: ${JOB_SCHEDULING_DISABLE_TIMEOUT:10s}");
    }
}
