package com.jxc.wefolio.config;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

/** 内容数量上限配置测试。 */
class ContentLimitPropertiesTest {

    @Test
    void defaultsShouldMatchProductLimits() {
        ContentLimitProperties properties = new ContentLimitProperties();

        assertThat(properties.getWorkImageMaxCount()).isEqualTo(500);
        assertThat(properties.getWorkVideoMaxCount()).isEqualTo(100);
        assertThat(properties.getWorkAnimationMaxCount()).isEqualTo(100);
        assertThat(properties.getPersonalPortfolioMaxCount()).isEqualTo(10);
        assertThat(properties.getTeamPortfolioMaxCount()).isEqualTo(10);
    }

    @Test
    void positiveValuesShouldOverrideDefaults() {
        ContentLimitProperties properties = new ContentLimitProperties();

        properties.setWorkImageMaxCount(501);
        properties.setWorkVideoMaxCount(101);
        properties.setWorkAnimationMaxCount(102);
        properties.setPersonalPortfolioMaxCount(11);
        properties.setTeamPortfolioMaxCount(12);

        assertThat(properties.getWorkImageMaxCount()).isEqualTo(501);
        assertThat(properties.getWorkVideoMaxCount()).isEqualTo(101);
        assertThat(properties.getWorkAnimationMaxCount()).isEqualTo(102);
        assertThat(properties.getPersonalPortfolioMaxCount()).isEqualTo(11);
        assertThat(properties.getTeamPortfolioMaxCount()).isEqualTo(12);
    }

    @Test
    void nonPositiveValuesShouldBeRejected() {
        ContentLimitProperties properties = new ContentLimitProperties();

        assertThatIllegalArgumentException().isThrownBy(() -> properties.setWorkImageMaxCount(0));
        assertThatIllegalArgumentException().isThrownBy(() -> properties.setWorkVideoMaxCount(-1));
        assertThatIllegalArgumentException().isThrownBy(() -> properties.setWorkAnimationMaxCount(0));
        assertThatIllegalArgumentException().isThrownBy(() -> properties.setPersonalPortfolioMaxCount(0));
        assertThatIllegalArgumentException().isThrownBy(() -> properties.setTeamPortfolioMaxCount(-1));
    }

    @Test
    void applicationYamlShouldDeclareEnvironmentOverrides() throws IOException {
        String yaml = Files.readString(Path.of("src/main/resources/application.yml"));

        assertThat(yaml)
                .contains("work-image-max-count: ${WEFOLIO_WORK_IMAGE_MAX_COUNT:500}")
                .contains("work-video-max-count: ${WEFOLIO_WORK_VIDEO_MAX_COUNT:100}")
                .contains("work-animation-max-count: ${WEFOLIO_WORK_ANIMATION_MAX_COUNT:100}")
                .contains("personal-portfolio-max-count: ${WEFOLIO_PERSONAL_PORTFOLIO_MAX_COUNT:10}")
                .contains("team-portfolio-max-count: ${WEFOLIO_TEAM_PORTFOLIO_MAX_COUNT:10}");
    }
}
