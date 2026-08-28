package com.jxc.wefolio.job.task;

import com.jxc.wefolio.job.config.FeedbackUploadCleanupProperties;
import com.jxc.wefolio.job.config.FeedbackUploadCleanupSchedulingConfig;
import com.jxc.wefolio.job.service.FeedbackUploadCleanupService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 反馈附件过期清理调度任务。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class FeedbackUploadCleanupJob {

    /** 当前实例是否已有一轮清理在执行 */
    private final AtomicBoolean running = new AtomicBoolean(false);

    /** 反馈附件清理服务 */
    private final FeedbackUploadCleanupService service;

    /** 清理调度配置 */
    private final FeedbackUploadCleanupProperties properties;

    /**
     * 每日低峰清理一批过期且未确认的反馈附件。
     */
    @Scheduled(
            cron = "${feedback-upload-cleanup.cron:0 40 3 * * ?}",
            zone = "${feedback-upload-cleanup.zone:Asia/Shanghai}",
            scheduler = FeedbackUploadCleanupSchedulingConfig.TASK_SCHEDULER_BEAN_NAME
    )
    public void execute() {
        if (!properties.isEnabled()) {
            log.debug("反馈附件过期清理任务未启用，跳过本轮执行");
            return;
        }
        if (!running.compareAndSet(false, true)) {
            log.info("上一轮反馈附件过期清理任务尚未结束，跳过本轮执行");
            return;
        }
        try {
            FeedbackUploadCleanupService.CleanupResult result = service.cleanupExpiredUploads();
            log.info("反馈附件过期清理调度完成: selectedCount={}, expiredCount={}, failedCount={}, "
                            + "concurrentlyChangedCount={}, batchCount={}, limitReached={}",
                    result.selectedCount(), result.expiredCount(), result.failedCount(),
                    result.concurrentlyChangedCount(), result.batchCount(), result.limitReached());
        } finally {
            running.set(false);
        }
    }
}
