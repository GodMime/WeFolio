package com.jxc.wefolio.job.config;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import static org.assertj.core.api.Assertions.assertThat;

/** 启动真实守卫，配置矛盾在接单前失败，不读取环境或连接外部服务。 */
class PortfolioAiMediaConfigurationGuardTest {
    /** 只创建相互依赖的两组属性和启动守卫。 */
    private ApplicationContextRunner context(boolean media, boolean frames) {
        PortfolioAiProperties ai = new PortfolioAiProperties(); ai.setMediaEnabled(media);
        WorkAuditProperties audit = new WorkAuditProperties(); audit.setVideoPersistentFramesEnabled(frames);
        return new ApplicationContextRunner().withBean(PortfolioAiProperties.class, () -> ai)
                .withBean(WorkAuditProperties.class, () -> audit)
                .withBean(PortfolioAiMediaConfigurationGuard.class);
    }

    /** 显式关闭依赖的帧写入时，启动错误必须能定位具体配置。 */
    @Test void mediaWithDisabledFramePersistenceFailsAtStartup() {
        context(true, false).run(application -> {
            assertThat(application).hasFailed();
            assertThat(application.getStartupFailure()).hasRootCauseMessage(
                    "启用 WEFOLIO_AI_MEDIA_ENABLED 必须开启 work-audit.video-persistent-frames-enabled（WORK_AUDIT_VIDEO_PERSISTENT_FRAMES_ENABLED）");
        });
    }

    /** 新媒体关闭时保持既有审核配置，启用全部依赖也能启动。 */
    @Test void compatibleSwitchCombinationsStartNormally() {
        context(false, false).run(application -> assertThat(application).hasNotFailed());
        context(false, true).run(application -> assertThat(application).hasNotFailed());
        context(true, true).run(application -> assertThat(application).hasNotFailed());
    }
}
