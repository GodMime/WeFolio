package com.jxc.wefolio.job.service;

import com.alibaba.fastjson2.JSON;
import com.jxc.wefolio.job.config.WorkAuditProperties;
import com.jxc.wefolio.job.dict.AuditProviderDict;
import com.jxc.wefolio.job.dict.AuditResultDict;
import com.jxc.wefolio.job.dict.MediaTypeDict;
import com.jxc.wefolio.job.dict.WorkAuditStatusDict;
import com.jxc.wefolio.job.dict.WorkAuditTaskStatusDict;
import com.jxc.wefolio.job.entity.WorkAuditTaskEntity;
import com.jxc.wefolio.job.entity.WorkAuditWorkEntity;
import com.jxc.wefolio.job.repo.WorkAuditTaskRepository;
import com.jxc.wefolio.job.repo.WorkAuditWorkRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * 作品内容审核任务编排服务。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class WorkAuditService {

    /** 毫秒到秒的换算值 */
    private static final int MILLIS_PER_SECOND = 1000;

    /** 错误类型字段 */
    private static final String ERROR_TYPE_FIELD = "errorType";

    /** 错误消息字段 */
    private static final String ERROR_MESSAGE_FIELD = "message";

    /** 审核拒绝原因最大长度 */
    private static final int AUDIT_REJECT_REASON_MAX_LENGTH = 512;

    private final WorkAuditWorkRepository workRepository;

    private final WorkAuditTaskRepository taskRepository;

    private final WorkAuditClaimTransactionService claimTransactionService;

    private final TencentCiAuditClient auditClient;

    private final WorkAuditProperties properties;

    /**
     * 执行一轮作品审核任务。
     */
    public void runOneRound() {
        long startNanos = System.nanoTime();
        log.info("作品审核任务开始: maxQueryVideoPerRun={}, maxSubmitVideoPerRun={}, maxAuditImagePerRun={}, "
                        + "videoQueryMaxAttempts={}",
                properties.getMaxQueryVideoPerRun(), properties.getMaxSubmitVideoPerRun(),
                properties.getMaxAuditImagePerRun(), properties.getVideoQueryMaxAttempts());
        try {
            logAuditBacklogSummary();
            queryPendingVideoResults(properties.getMaxQueryVideoPerRun());
            submitPendingVideoAudits(properties.getMaxSubmitVideoPerRun());
            auditPendingImages(properties.getMaxAuditImagePerRun());
            queryAllPendingVideoResults(properties.getMaxQueryVideoPerRun());
        } finally {
            long durationMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startNanos);
            log.info("作品审核任务结束: 任务总耗时毫秒={}", durationMs);
        }
    }

    /**
     * 查询之前还没到结束态的视频审核结果。
     *
     * @param limit 查询数量上限
     */
    public void queryPendingVideoResults(int limit) {
        taskRepository.findQueryableVideoTasks(limit, properties.getVideoQueryMaxAttempts())
                .forEach(this::queryOneVideoResult);
    }

    /**
     * 提交待审核视频，提交后不等待结果。
     *
     * @param limit 提交数量上限
     */
    public void submitPendingVideoAudits(int limit) {
        workRepository.findPendingVideos(limit).forEach(this::submitOneVideo);
    }

    /**
     * 同步审核待审核图片。
     *
     * @param limit 审核数量上限
     */
    public void auditPendingImages(int limit) {
        workRepository.findPendingImages(limit).forEach(this::auditOneImage);
    }

    /**
     * 查询所有还没到结束态的视频审核结果。
     *
     * @param limit 查询数量上限
     */
    public void queryAllPendingVideoResults(int limit) {
        queryPendingVideoResults(limit);
    }

    private void logAuditBacklogSummary() {
        long pendingImageWorks = workRepository.countPendingImages();
        long pendingVideoWorks = workRepository.countPendingVideos();
        long queryableVideoTasks = taskRepository.countQueryableVideoTasks(properties.getVideoQueryMaxAttempts());
        long pendingTotalWorks = pendingImageWorks + pendingVideoWorks;

        log.info("作品审核待处理统计: 待审核图片作品数={}, 待审核视频作品数={}, 待审核作品总数={}, "
                        + "待查询视频任务数={}, 视频主动查询最大次数={}",
                pendingImageWorks, pendingVideoWorks, pendingTotalWorks, queryableVideoTasks,
                properties.getVideoQueryMaxAttempts());
    }

    private void submitOneVideo(WorkAuditWorkEntity work) {
        int snapshotCount = calculateSnapshotCount(work.getDurationMs());
        WorkAuditTaskEntity task = newSubmittingTask(work, MediaTypeDict.VIDEO);
        task.setSnapshotIntervalSeconds(properties.getVideoSnapshotIntervalSeconds());
        task.setSnapshotCount(snapshotCount);
        task = claimTransactionService.claimAndCreateSubmittingTask(work.getId(), task);
        if (task == null) {
            log.info("视频作品审核跳过: workId={}, userId={}, objectKey={}, reason=未抢占到作品",
                    work.getId(), work.getUserId(), work.getMediaObjectKey());
            return;
        }
        log.info("开始处理视频作品审核: workId={}, taskId={}, userId={}, objectKey={}, mediaSha256={}, "
                        + "durationMs={}, attemptCount={}, queryCount={}, snapshotIntervalSeconds={}, snapshotCount={}",
                work.getId(), task.getId(), work.getUserId(), work.getMediaObjectKey(), work.getMediaSha256(),
                work.getDurationMs(), task.getAttemptCount(), task.getQueryCount(),
                task.getSnapshotIntervalSeconds(), task.getSnapshotCount());

        try {
            TencentCiAuditResult result = auditClient.submitVideo(
                    work.getMediaObjectKey(),
                    properties.getVideoSnapshotIntervalSeconds(),
                    snapshotCount);
            claimTransactionService.markVideoSubmittedAndUpdateWorkAuditing(
                    task.getId(), work.getId(), result.ciJobId(), result.rawPayload());
            log.info("视频作品审核提交完成: workId={}, taskId={}, objectKey={}, ciJobId={}, ciState={}, "
                            + "auditResult={}, queryCount={}, maxQueryCount={}, nextStatus={}",
                    work.getId(), task.getId(), work.getMediaObjectKey(), result.ciJobId(), result.ciState(),
                    result.auditResult().getCode(), task.getQueryCount(), properties.getVideoQueryMaxAttempts(),
                    WorkAuditTaskStatusDict.SUBMITTED.getCode());
        } catch (RuntimeException ex) {
            String errorMessage = errorMessage(ex);
            String auditRejectReason = throwableReason("提交腾讯云视频审核失败", ex);
            log.warn("提交腾讯云视频审核失败: workId={}, taskId={}, objectKey={}, attemptCount={}, error={}",
                    work.getId(), task.getId(), work.getMediaObjectKey(), task.getAttemptCount(), errorMessage);
            claimTransactionService.markTaskFailedAndUpdateWorkFailed(
                    task.getId(), work.getId(), errorMessage, errorPayload(ex), auditRejectReason);
            log.info("视频作品审核简洁结果: workId={}, taskId={}, objectKey={}, auditStatus={}, auditRejectReason={}",
                    work.getId(), task.getId(), work.getMediaObjectKey(), WorkAuditStatusDict.FAILED.getCode(),
                    auditRejectReason);
        }
    }

    private void auditOneImage(WorkAuditWorkEntity work) {
        WorkAuditTaskEntity task = newSubmittingTask(work, MediaTypeDict.IMAGE);
        task = claimTransactionService.claimAndCreateSubmittingTask(work.getId(), task);
        if (task == null) {
            log.info("图片作品审核跳过: workId={}, userId={}, objectKey={}, reason=未抢占到作品",
                    work.getId(), work.getUserId(), work.getMediaObjectKey());
            return;
        }
        log.info("开始处理图片作品审核: workId={}, taskId={}, userId={}, objectKey={}, mediaSha256={}, "
                        + "attemptCount={}",
                work.getId(), task.getId(), work.getUserId(), work.getMediaObjectKey(), work.getMediaSha256(),
                task.getAttemptCount());

        try {
            TencentCiAuditResult result = auditClient.auditImage(work.getMediaObjectKey());
            if (result.auditResult() == AuditResultDict.UNKNOWN) {
                String auditRejectReason = unknownAuditResultReason("图片审核结果未知", result);
                claimTransactionService.markTaskFailedAndUpdateWorkFailed(
                        task.getId(), work.getId(), "图片审核结果未知", result.rawPayload(), auditRejectReason);
                log.info("图片作品审核简洁结果: workId={}, taskId={}, objectKey={}, ciJobId={}, ciState={}, "
                                + "auditResult={}, ciResult={}, ciLabel={}, ciScore={}, auditStatus={}, "
                                + "auditRejectReason={}",
                        work.getId(), task.getId(), work.getMediaObjectKey(), result.ciJobId(), result.ciState(),
                        result.auditResult().getCode(), result.ciResult(), result.ciLabel(), result.ciScore(),
                        WorkAuditStatusDict.FAILED.getCode(), auditRejectReason);
                return;
            }
            WorkAuditStatusDict auditStatus = mapWorkAuditStatus(result.auditResult());
            String auditRejectReason = auditRejectReason(result);
            claimTransactionService.markTaskSuccessAndUpdateWork(task.getId(), work.getId(), result.auditResult(),
                    result.ciState(), result.ciResult(), result.ciLabel(), result.ciScore(), result.rawPayload(),
                    auditStatus, auditRejectReason);
            log.info("图片作品审核简洁结果: workId={}, taskId={}, objectKey={}, ciJobId={}, ciState={}, "
                            + "auditResult={}, ciResult={}, ciLabel={}, ciScore={}, auditStatus={}, "
                            + "auditRejectReason={}",
                    work.getId(), task.getId(), work.getMediaObjectKey(), result.ciJobId(), result.ciState(),
                    result.auditResult().getCode(), result.ciResult(), result.ciLabel(), result.ciScore(),
                    auditStatus.getCode(), auditRejectReason);
        } catch (RuntimeException ex) {
            String errorMessage = errorMessage(ex);
            String auditRejectReason = throwableReason("图片审核调用腾讯云失败", ex);
            log.warn("调用腾讯云图片审核失败: workId={}, taskId={}, objectKey={}, attemptCount={}, error={}",
                    work.getId(), task.getId(), work.getMediaObjectKey(), task.getAttemptCount(), errorMessage);
            claimTransactionService.markTaskFailedAndUpdateWorkFailed(
                    task.getId(), work.getId(), errorMessage, errorPayload(ex), auditRejectReason);
            log.info("图片作品审核简洁结果: workId={}, taskId={}, objectKey={}, auditStatus={}, auditRejectReason={}",
                    work.getId(), task.getId(), work.getMediaObjectKey(), WorkAuditStatusDict.FAILED.getCode(),
                    auditRejectReason);
        }
    }

    private void queryOneVideoResult(WorkAuditTaskEntity task) {
        int nextQueryCount = nullToZero(task.getQueryCount()) + 1;
        if (!taskRepository.claimVideoQuery(task.getId(), properties.getLockOwnerPrefix(),
                lockedUntil(), properties.getVideoQueryMaxAttempts())) {
            log.info("视频审核结果查询跳过: workId={}, taskId={}, objectKey={}, ciJobId={}, queryCount={}, maxQueryCount={}, "
                            + "reason=未抢占到任务",
                    task.getWorkId(), task.getId(), task.getMediaObjectKey(), task.getCiJobId(),
                    task.getQueryCount(), properties.getVideoQueryMaxAttempts());
            return;
        }
        log.info("开始查询视频审核结果: workId={}, taskId={}, objectKey={}, ciJobId={}, queryCountBefore={}, "
                        + "queryCountAfterClaim={}, maxQueryCount={}",
                task.getWorkId(), task.getId(), task.getMediaObjectKey(), task.getCiJobId(), task.getQueryCount(),
                nextQueryCount, properties.getVideoQueryMaxAttempts());

        try {
            nextQueryCount = currentQueryCountAfterClaim(task, nextQueryCount);
            log.info("视频审核查询次数确认: workId={}, taskId={}, objectKey={}, ciJobId={}, queryCount={}, maxQueryCount={}",
                    task.getWorkId(), task.getId(), task.getMediaObjectKey(), task.getCiJobId(), nextQueryCount,
                    properties.getVideoQueryMaxAttempts());
            TencentCiAuditResult result = auditClient.queryVideo(task.getCiJobId());
            handleVideoQueryResult(task, nextQueryCount, result);
        } catch (RuntimeException ex) {
            log.warn("查询腾讯云视频审核结果失败: workId={}, taskId={}, objectKey={}, ciJobId={}, queryCount={}, "
                            + "maxQueryCount={}, error={}",
                    task.getWorkId(), task.getId(), task.getMediaObjectKey(), task.getCiJobId(), nextQueryCount,
                    properties.getVideoQueryMaxAttempts(), ex.getMessage());
            if (nextQueryCount >= properties.getVideoQueryMaxAttempts()) {
                String auditRejectReason = queryLimitReason(nextQueryCount);
                claimTransactionService.markTaskFailedAndUpdateWorkFailed(
                        task.getId(), task.getWorkId(), "视频审核查询次数超过上限", errorPayload(ex), auditRejectReason);
                log.info("视频作品审核简洁结果: workId={}, taskId={}, objectKey={}, ciJobId={}, auditStatus={}, "
                                + "queryCount={}, maxQueryCount={}, auditRejectReason={}",
                        task.getWorkId(), task.getId(), task.getMediaObjectKey(), task.getCiJobId(),
                        WorkAuditStatusDict.FAILED.getCode(), nextQueryCount, properties.getVideoQueryMaxAttempts(),
                        auditRejectReason);
                return;
            }
            claimTransactionService.markQueryFailureForNextRunAndKeepWorkAuditing(
                    task.getId(), task.getWorkId(), errorMessage(ex), errorPayload(ex));
            log.info("视频审核查询失败后回到运行中: workId={}, taskId={}, objectKey={}, ciJobId={}, queryCount={}, "
                            + "maxQueryCount={}, nextStatus={}",
                    task.getWorkId(), task.getId(), task.getMediaObjectKey(), task.getCiJobId(), nextQueryCount,
                    properties.getVideoQueryMaxAttempts(), WorkAuditTaskStatusDict.RUNNING.getCode());
        }
    }

    private void handleVideoQueryResult(WorkAuditTaskEntity task, int nextQueryCount, TencentCiAuditResult result) {
        if (result.providerFailed()) {
            String auditRejectReason = videoProviderFailedReason(task, result);
            claimTransactionService.markTaskFailedAndUpdateWorkFailed(
                    task.getId(), task.getWorkId(), "腾讯云视频审核任务失败", result.rawPayload(), auditRejectReason);
            log.info("视频作品审核简洁结果: workId={}, taskId={}, objectKey={}, ciJobId={}, ciState={}, auditResult={}, "
                            + "auditStatus={}, queryCount={}, maxQueryCount={}, auditRejectReason={}",
                    task.getWorkId(), task.getId(), task.getMediaObjectKey(), task.getCiJobId(), result.ciState(),
                    result.auditResult().getCode(), WorkAuditStatusDict.FAILED.getCode(), nextQueryCount,
                    properties.getVideoQueryMaxAttempts(), auditRejectReason);
            return;
        }
        if (result.terminal()) {
            if (result.auditResult() == AuditResultDict.UNKNOWN) {
                String auditRejectReason = unknownAuditResultReason("腾讯云视频审核结果未知", result);
                claimTransactionService.markTaskFailedAndUpdateWorkFailed(
                        task.getId(), task.getWorkId(), "腾讯云视频审核结果未知", result.rawPayload(), auditRejectReason);
                log.info("视频作品审核简洁结果: workId={}, taskId={}, objectKey={}, ciJobId={}, ciState={}, auditResult={}, "
                                + "auditStatus={}, queryCount={}, maxQueryCount={}, auditRejectReason={}",
                        task.getWorkId(), task.getId(), task.getMediaObjectKey(), task.getCiJobId(), result.ciState(),
                        result.auditResult().getCode(), WorkAuditStatusDict.FAILED.getCode(), nextQueryCount,
                        properties.getVideoQueryMaxAttempts(), auditRejectReason);
                return;
            }
            WorkAuditStatusDict auditStatus = mapWorkAuditStatus(result.auditResult());
            String auditRejectReason = auditRejectReason(result);
            claimTransactionService.markTaskSuccessAndUpdateWork(task.getId(), task.getWorkId(), result.auditResult(),
                    result.ciState(), result.ciResult(), result.ciLabel(), result.ciScore(), result.rawPayload(),
                    auditStatus, auditRejectReason);
            log.info("视频作品审核简洁结果: workId={}, taskId={}, objectKey={}, ciJobId={}, ciState={}, auditResult={}, "
                            + "ciResult={}, ciLabel={}, ciScore={}, auditStatus={}, queryCount={}, maxQueryCount={}, "
                            + "auditRejectReason={}",
                    task.getWorkId(), task.getId(), task.getMediaObjectKey(), task.getCiJobId(), result.ciState(),
                    result.auditResult().getCode(), result.ciResult(), result.ciLabel(), result.ciScore(),
                    auditStatus.getCode(), nextQueryCount, properties.getVideoQueryMaxAttempts(), auditRejectReason);
            return;
        }
        if (nextQueryCount >= properties.getVideoQueryMaxAttempts()) {
            String auditRejectReason = queryLimitReason(nextQueryCount);
            claimTransactionService.markTaskFailedAndUpdateWorkFailed(
                    task.getId(), task.getWorkId(), "视频审核查询次数超过上限", result.rawPayload(), auditRejectReason);
            log.info("视频作品审核简洁结果: workId={}, taskId={}, objectKey={}, ciJobId={}, ciState={}, auditResult={}, "
                            + "auditStatus={}, queryCount={}, maxQueryCount={}, auditRejectReason={}",
                    task.getWorkId(), task.getId(), task.getMediaObjectKey(), task.getCiJobId(), result.ciState(),
                    result.auditResult().getCode(), WorkAuditStatusDict.FAILED.getCode(), nextQueryCount,
                    properties.getVideoQueryMaxAttempts(), auditRejectReason);
            return;
        }
        claimTransactionService.markVideoRunningAndKeepWorkAuditing(
                task.getId(), task.getWorkId(), result.ciState(), result.rawPayload());
        log.info("视频审核仍在处理中: workId={}, taskId={}, objectKey={}, ciJobId={}, ciState={}, auditResult={}, "
                        + "queryCount={}, maxQueryCount={}, nextStatus={}",
                task.getWorkId(), task.getId(), task.getMediaObjectKey(), task.getCiJobId(), result.ciState(),
                result.auditResult().getCode(), nextQueryCount, properties.getVideoQueryMaxAttempts(),
                WorkAuditTaskStatusDict.RUNNING.getCode());
    }

    /**
     * 根据归一化审核结果生成作品拒绝原因，审核通过时返回空。
     *
     * @param result 腾讯云审核结果摘要
     * @return 作品拒绝原因
     */
    private String auditRejectReason(TencentCiAuditResult result) {
        return switch (result.auditResult()) {
            case PASS -> null;
            case BLOCK -> ciResultReason("腾讯云判定违规", result);
            case REVIEW -> ciResultReason("腾讯云判定疑似违规，需人工复核", result);
            case UNKNOWN -> unknownAuditResultReason("腾讯云审核结果未知", result);
        };
    }

    /**
     * 生成包含腾讯云标签、结果码和分数的审核原因。
     *
     * @param prefix 原因前缀
     * @param result 腾讯云审核结果摘要
     * @return 审核原因
     */
    private String ciResultReason(String prefix, TencentCiAuditResult result) {
        return truncateAuditRejectReason(prefix + "：label=" + result.ciLabel()
                + "，result=" + result.ciResult()
                + "，score=" + result.ciScore());
    }

    /**
     * 生成腾讯云返回未知结果时的失败原因。
     *
     * @param prefix 原因前缀
     * @param result 腾讯云审核结果摘要
     * @return 失败原因
     */
    private String unknownAuditResultReason(String prefix, TencentCiAuditResult result) {
        return truncateAuditRejectReason(prefix + "：state=" + result.ciState()
                + "，result=" + result.ciResult()
                + "，label=" + result.ciLabel());
    }

    /**
     * 生成腾讯云视频审核任务失败原因。
     *
     * @param task 审核任务
     * @param result 腾讯云审核结果摘要
     * @return 失败原因
     */
    private String videoProviderFailedReason(WorkAuditTaskEntity task, TencentCiAuditResult result) {
        return truncateAuditRejectReason("腾讯云视频审核任务失败：state=" + result.ciState()
                + "，jobId=" + task.getCiJobId());
    }

    /**
     * 生成视频主动查询次数超过上限的失败原因。
     *
     * @param queryCount 当前查询次数
     * @return 失败原因
     */
    private String queryLimitReason(int queryCount) {
        return truncateAuditRejectReason("视频审核查询次数超过上限：queryCount=" + queryCount
                + "，maxQueryCount=" + properties.getVideoQueryMaxAttempts());
    }

    /**
     * 生成远端调用异常对应的失败原因。
     *
     * @param prefix 原因前缀
     * @param ex 远端调用异常
     * @return 失败原因
     */
    private String throwableReason(String prefix, RuntimeException ex) {
        return truncateAuditRejectReason(prefix + "：" + errorMessage(ex));
    }

    /**
     * 将作品审核拒绝原因截断到数据库字段允许的最大码点数。
     *
     * @param reason 原始原因
     * @return 截断后的原因
     */
    private String truncateAuditRejectReason(String reason) {
        if (reason == null) {
            return null;
        }
        int codePointCount = reason.codePointCount(0, reason.length());
        if (codePointCount <= AUDIT_REJECT_REASON_MAX_LENGTH) {
            return reason;
        }
        int endIndex = reason.offsetByCodePoints(0, AUDIT_REJECT_REASON_MAX_LENGTH);
        return reason.substring(0, endIndex);
    }

    private int currentQueryCountAfterClaim(WorkAuditTaskEntity task, int fallbackQueryCount) {
        Integer currentQueryCount = taskRepository.findQueryCountById(task.getId());
        return currentQueryCount == null
                ? fallbackQueryCount
                : Math.max(fallbackQueryCount, nullToZero(currentQueryCount));
    }

    private WorkAuditTaskEntity newSubmittingTask(WorkAuditWorkEntity work, MediaTypeDict mediaType) {
        WorkAuditTaskEntity task = new WorkAuditTaskEntity();
        task.setWorkId(work.getId());
        task.setUserId(work.getUserId());
        task.setMediaType(mediaType.getCode());
        task.setMediaObjectKey(work.getMediaObjectKey());
        task.setMediaSha256(work.getMediaSha256());
        task.setProvider(AuditProviderDict.TENCENT_CI.getCode());
        task.setTaskStatus(WorkAuditTaskStatusDict.SUBMITTING.getCode());
        task.setAuditResult(AuditResultDict.UNKNOWN.getCode());
        task.setAttemptCount(1);
        task.setQueryCount(0);
        task.setStartedAt(LocalDateTime.now());
        task.setLockedBy(properties.getLockOwnerPrefix());
        task.setLockedUntil(lockedUntil());
        return task;
    }

    private int calculateSnapshotCount(Integer durationMs) {
        if (durationMs == null || durationMs <= 0) {
            return 1;
        }
        double intervalMillis = properties.getVideoSnapshotIntervalSeconds() * (double) MILLIS_PER_SECOND;
        int count = (int) Math.ceil(durationMs / intervalMillis);
        return Math.max(1, Math.min(count, properties.getMaxVideoSnapshotCount()));
    }

    private WorkAuditStatusDict mapWorkAuditStatus(AuditResultDict result) {
        return switch (result) {
            case PASS -> WorkAuditStatusDict.PASSED;
            case BLOCK -> WorkAuditStatusDict.REJECTED;
            case REVIEW -> WorkAuditStatusDict.REVIEW_REQUIRED;
            case UNKNOWN -> WorkAuditStatusDict.FAILED;
        };
    }

    private LocalDateTime lockedUntil() {
        return LocalDateTime.now().plusSeconds(properties.getTaskLockSeconds());
    }

    private int nullToZero(Integer value) {
        return value == null ? 0 : value;
    }

    private String errorMessage(RuntimeException ex) {
        String message = ex.getMessage();
        return message == null ? ex.getClass().getSimpleName() : message;
    }

    private String errorPayload(RuntimeException ex) {
        return JSON.toJSONString(Map.of(
                ERROR_TYPE_FIELD, ex.getClass().getName(),
                ERROR_MESSAGE_FIELD, errorMessage(ex)
        ));
    }
}
