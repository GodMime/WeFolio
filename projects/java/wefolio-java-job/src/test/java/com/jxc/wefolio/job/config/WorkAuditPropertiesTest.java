package com.jxc.wefolio.job.config;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 作品审核配置测试。
 */
class WorkAuditPropertiesTest {

    @Test
    void animationConfigurationShouldUseSafeDefaults() {
        WorkAuditProperties properties = new WorkAuditProperties();

        assertThat(properties.getMaxAuditAnimationPerRun()).isEqualTo(500);
        assertThat(properties.getAnimationMaxAttempts()).isEqualTo(3);
    }

    @Test
    void animationConfigurationShouldRejectNonPositiveValues() {
        WorkAuditProperties properties = new WorkAuditProperties();

        assertThatThrownBy(() -> properties.setMaxAuditAnimationPerRun(0))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> properties.setAnimationMaxAttempts(-1))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
