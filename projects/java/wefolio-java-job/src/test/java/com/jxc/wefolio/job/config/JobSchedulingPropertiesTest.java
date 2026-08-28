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
        assertThat(properties.getPauseDuration()).isEqualTo(Duration.ofMinutes(10));
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
    void shouldRejectMissingOrNonPositivePauseDuration() {
        JobSchedulingProperties properties = new JobSchedulingProperties();

        properties.setPauseDuration(null);
        assertThatThrownBy(properties::validate)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("job-scheduling.pause-duration");

        properties.setPauseDuration(Duration.ZERO);
        assertThatThrownBy(properties::validate)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("job-scheduling.pause-duration");

        properties.setPauseDuration(Duration.ofMinutes(-1));
        assertThatThrownBy(properties::validate)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("job-scheduling.pause-duration");
    }

    @Test
    void shouldRejectPauseDurationNotLongerThanDrainTimeout() {
        JobSchedulingProperties properties = new JobSchedulingProperties();
        properties.setDisableTimeout(Duration.ofSeconds(30));

        properties.setPauseDuration(Duration.ofSeconds(20));
        assertThatThrownBy(properties::validate)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("job-scheduling.pause-duration")
                .hasMessageContaining("job-scheduling.disable-timeout");

        properties.setPauseDuration(Duration.ofSeconds(30));
        assertThatThrownBy(properties::validate)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("job-scheduling.pause-duration")
                .hasMessageContaining("job-scheduling.disable-timeout");
    }

    @Test
    void shouldAcceptPauseDurationLongerThanDrainTimeout() {
        JobSchedulingProperties properties = new JobSchedulingProperties();
        properties.setDisableTimeout(Duration.ofSeconds(30));
        properties.setPauseDuration(Duration.ofSeconds(31));

        properties.validate();
    }

    @Test
    void applicationYamlShouldExposeDisableTimeoutEnvironmentVariable() throws IOException {
        String yaml = Files.readString(Path.of("src/main/resources/application.yml"));

        assertThat(yaml).contains(
                "disable-timeout: ${JOB_SCHEDULING_DISABLE_TIMEOUT:10s}");
        assertThat(yaml).contains(
                "pause-duration: ${JOB_SCHEDULING_PAUSE_DURATION:10m}");
    }
}
