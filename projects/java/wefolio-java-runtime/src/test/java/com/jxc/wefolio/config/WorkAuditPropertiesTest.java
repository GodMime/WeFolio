package com.jxc.wefolio.config;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

/** 作品审核轮次配置测试。 */
class WorkAuditPropertiesTest {

    @Test
    void defaultMaxRoundsShouldBeThree() {
        WorkAuditProperties properties = new WorkAuditProperties();

        assertThat(properties.getMaxRounds()).isEqualTo(3);
    }

    @Test
    void positiveMaxRoundsShouldOverrideDefault() {
        WorkAuditProperties properties = new WorkAuditProperties();

        properties.setMaxRounds(5);

        assertThat(properties.getMaxRounds()).isEqualTo(5);
    }

    @Test
    void maxRoundsBelowOneShouldFailFast() {
        WorkAuditProperties properties = new WorkAuditProperties();

        assertThatIllegalArgumentException()
                .isThrownBy(() -> properties.setMaxRounds(0))
                .withMessage("作品审核总轮次必须大于等于 1");
    }

    @Test
    void applicationYamlShouldDeclareEnvironmentOverride() throws IOException {
        String yaml = Files.readString(Path.of("src/main/resources/application.yml"));

        assertThat(yaml).contains("max-rounds: ${WEFOLIO_WORK_AUDIT_MAX_ROUNDS:3}");
    }
}
