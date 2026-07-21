package com.jxc.wefolio.config;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** 作品集打开耗时日志配置测试。 */
class PortfolioOpenPerformancePropertiesTest {

    @Test
    void defaultsAndYamlKeysShouldMatchApprovedConfiguration() throws Exception {
        PortfolioOpenPerformanceProperties properties = new PortfolioOpenPerformanceProperties();
        String yaml = Files.readString(Path.of("src/main/resources/application.yml"));

        assertThat(properties.getSlowThreshold()).isEqualTo(Duration.ofSeconds(2));
        assertThat(properties.getNormalSampleRate()).isEqualTo(0.01D);
        assertThat(yaml).contains(
                "slow-threshold: ${PORTFOLIO_OPEN_SLOW_THRESHOLD:2s}",
                "normal-sample-rate: ${PORTFOLIO_OPEN_NORMAL_SAMPLE_RATE:0.01}");
    }

    @Test
    void rejectsInvalidThresholdAndSampleRate() {
        PortfolioOpenPerformanceProperties properties = new PortfolioOpenPerformanceProperties();

        assertThatThrownBy(() -> properties.setSlowThreshold(Duration.ZERO))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> properties.setNormalSampleRate(-0.01D))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> properties.setNormalSampleRate(1.01D))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
