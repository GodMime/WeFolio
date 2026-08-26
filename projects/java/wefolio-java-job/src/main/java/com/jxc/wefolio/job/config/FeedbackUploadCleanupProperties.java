package com.jxc.wefolio.job.config;

import jakarta.annotation.PostConstruct;
import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.time.DateTimeException;
import java.time.ZoneId;

/**
 * 反馈附件上传任务清理配置。
 */
@Data
@Component
@ConfigurationProperties(prefix = "feedback-upload-cleanup")
public class FeedbackUploadCleanupProperties {

    /** 单批任务数量最大值 */
    private static final int MAX_BATCH_SIZE = 1000;

    /** 单轮批次数最大值 */
    private static final int MAX_BATCHES_LIMIT = 100;

    /** 非法单批任务数量消息 */
    private static final String INVALID_BATCH_SIZE_MESSAGE =
            "feedback-upload-cleanup.batch-size 必须在 1 至 1000 之间";

    /** 非法单轮批次数消息 */
    private static final String INVALID_MAX_BATCHES_MESSAGE =
            "feedback-upload-cleanup.max-batches 必须在 1 至 100 之间";

    /** 非法时区消息 */
    private static final String INVALID_ZONE_MESSAGE =
            "feedback-upload-cleanup.zone 必须是有效时区";

    /** 是否启用清理调度 */
    private boolean enabled = true;

    /** 每日低峰执行的调度表达式 */
    private String cron = "0 40 3 * * ?";

    /** cron 调度时区 */
    private String zone = "Asia/Shanghai";

    /** 单轮最多清理的上传任务数 */
    private int batchSize = 200;

    /** 单轮最多执行的 keyset 批次数 */
    private int maxBatches = 10;

    /** 校验清理任务的有界执行参数和调度时区。 */
    @PostConstruct
    public void validate() {
        if (batchSize <= 0 || batchSize > MAX_BATCH_SIZE) {
            throw new IllegalStateException(INVALID_BATCH_SIZE_MESSAGE);
        }
        if (maxBatches <= 0 || maxBatches > MAX_BATCHES_LIMIT) {
            throw new IllegalStateException(INVALID_MAX_BATCHES_MESSAGE);
        }
        try {
            ZoneId.of(zone);
        } catch (DateTimeException | NullPointerException ignored) {
            throw new IllegalStateException(INVALID_ZONE_MESSAGE);
        }
    }
}
