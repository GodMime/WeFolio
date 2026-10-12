package com.jxc.wefolio.job.config;

import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/** 媒体能力依赖持久视频帧，在接单前拒绝相互矛盾的部署配置。 */
@Component
@RequiredArgsConstructor
public class PortfolioAiMediaConfigurationGuard {
    /** 本实例预期执行的媒体能力。 */
    private final PortfolioAiProperties ai;
    /** 新视频审核完成后的帧写入开关。 */
    private final WorkAuditProperties audit;

    /** 默认开关联动；显式关闭帧写入时给出具体配置项，不能等选长视频才失败。 */
    @PostConstruct
    public void validate() {
        if (ai.isMediaEnabled() && !audit.isVideoPersistentFramesEnabled()) {
            throw new IllegalStateException("启用 WEFOLIO_AI_MEDIA_ENABLED 必须开启 work-audit.video-persistent-frames-enabled（WORK_AUDIT_VIDEO_PERSISTENT_FRAMES_ENABLED）");
        }
    }
}
