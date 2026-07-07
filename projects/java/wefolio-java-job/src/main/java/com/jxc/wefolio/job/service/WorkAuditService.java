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

    private final WorkAuditWorkRepository workRepository;

    private final WorkAuditTaskRepository taskRepository;

    private final WorkAuditClaimTransactionService claimTransactionService;

    private final TencentCiAuditClient auditClient;

    private final WorkAuditProperties properties;

    /**
     * 执行一轮作品审核任务。
     */
    public void runOneRound() {
        log.info("作品审核任务开始: maxQueryVideoPerRun={}, maxSubmitVideoPerRun={}, maxAuditImagePerRun={}, "
                        + "videoQueryMaxAttempts={}",
                properties.getMaxQueryVideoPerRun(), properties.getMaxSubmitVideoPerRun(),
                properties.getMaxAuditImagePerRun(), properties.getVideoQueryMaxAttempts());
        logAuditBacklogSummary();
        queryPendingVideoResults(properties.getMaxQueryVideoPerRun());
        submitPendingVideoAudits(properties.getMaxSubmitVideoPerRun());
        auditPendingImages(properties.getMaxAuditImagePerRun());
        queryAllPendingVideoResults(properties.getMaxQueryVideoPerRun());
        log.info("作品审核任务结束");
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

        log.info("作品审核待处理统计: pendingImageWorks={}, pendingVideoWorks={}, pendingTotalWorks={}, "
                        + "queryableVideoTasks={}, videoQueryMaxAttempts={}",
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
            taskRepository.markVideoSubmitted(task.getId(), result.ciJobId(), result.rawPayload());
            log.info("视频作品审核提交完成: workId={}, taskId={}, objectKey={}, ciJobId={}, ciState={}, "
                            + "auditResult={}, queryCount={}, maxQueryCount={}, nextStatus={}",
                    work.getId(), task.getId(), work.getMediaObjectKey(), result.ciJobId(), result.ciState(),
                    result.auditResult().getCode(), task.getQueryCount(), properties.getVideoQueryMaxAttempts(),
                    WorkAuditTaskStatusDict.SUBMITTED.getCode());
        } catch (RuntimeException ex) {
            log.warn("提交腾讯云视频审核失败: workId={}, taskId={}, objectKey={}, attemptCount={}, error={}",
                    work.getId(), task.getId(), work.getMediaObjectKey(), task.getAttemptCount(), ex.getMessage());
            taskRepository.markFailed(task.getId(), errorMessage(ex), errorPayload(ex));
            workRepository.updateAuditStatus(work.getId(), WorkAuditStatusDict.FAILED);
            log.info("视频作品审核简洁结果: workId={}, taskId={}, objectKey={}, auditStatus={}, reason={}",
                    work.getId(), task.getId(), work.getMediaObjectKey(), WorkAuditStatusDict.FAILED.getCode(),
                    errorMessage(ex));
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
                taskRepository.markFailed(task.getId(), "图片审核结果未知", result.rawPayload());
                workRepository.updateAuditStatus(work.getId(), WorkAuditStatusDict.FAILED);
                log.info("图片作品审核简洁结果: workId={}, taskId={}, objectKey={}, ciJobId={}, ciState={}, "
                                + "auditResult={}, ciResult={}, ciLabel={}, ciScore={}, auditStatus={}",
                        work.getId(), task.getId(), work.getMediaObjectKey(), result.ciJobId(), result.ciState(),
                        result.auditResult().getCode(), result.ciResult(), result.ciLabel(), result.ciScore(),
                        WorkAuditStatusDict.FAILED.getCode());
                return;
            }
            taskRepository.markSuccess(task.getId(), result.auditResult(), result.ciState(), result.ciResult(),
                    result.ciLabel(), result.ciScore(), result.rawPayload());
            WorkAuditStatusDict auditStatus = mapWorkAuditStatus(result.auditResult());
            workRepository.updateAuditStatus(work.getId(), auditStatus);
            log.info("图片作品审核简洁结果: workId={}, taskId={}, objectKey={}, ciJobId={}, ciState={}, "
                            + "auditResult={}, ciResult={}, ciLabel={}, ciScore={}, auditStatus={}",
                    work.getId(), task.getId(), work.getMediaObjectKey(), result.ciJobId(), result.ciState(),
                    result.auditResult().getCode(), result.ciResult(), result.ciLabel(), result.ciScore(),
                    auditStatus.getCode());
        } catch (RuntimeException ex) {
            log.warn("调用腾讯云图片审核失败: workId={}, taskId={}, objectKey={}, attemptCount={}, error={}",
                    work.getId(), task.getId(), work.getMediaObjectKey(), task.getAttemptCount(), ex.getMessage());
            taskRepository.markFailed(task.getId(), errorMessage(ex), errorPayload(ex));
            workRepository.updateAuditStatus(work.getId(), WorkAuditStatusDict.FAILED);
            log.info("图片作品审核简洁结果: workId={}, taskId={}, objectKey={}, auditStatus={}, reason={}",
                    work.getId(), task.getId(), work.getMediaObjectKey(), WorkAuditStatusDict.FAILED.getCode(),
                    errorMessage(ex));
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
                taskRepository.markFailed(task.getId(), "视频审核查询次数超过上限", errorPayload(ex));
                workRepository.updateAuditStatus(task.getWorkId(), WorkAuditStatusDict.FAILED);
                log.info("视频作品审核简洁结果: workId={}, taskId={}, objectKey={}, ciJobId={}, auditStatus={}, "
                                + "queryCount={}, maxQueryCount={}, reason={}",
                        task.getWorkId(), task.getId(), task.getMediaObjectKey(), task.getCiJobId(),
                        WorkAuditStatusDict.FAILED.getCode(), nextQueryCount, properties.getVideoQueryMaxAttempts(),
                        errorMessage(ex));
                return;
            }
            taskRepository.markQueryFailureForNextRun(task.getId(), errorMessage(ex), errorPayload(ex));
            log.info("视频审核查询失败后回到运行中: workId={}, taskId={}, objectKey={}, ciJobId={}, queryCount={}, "
                            + "maxQueryCount={}, nextStatus={}",
                    task.getWorkId(), task.getId(), task.getMediaObjectKey(), task.getCiJobId(), nextQueryCount,
                    properties.getVideoQueryMaxAttempts(), WorkAuditTaskStatusDict.RUNNING.getCode());
        }
    }

    private void handleVideoQueryResult(WorkAuditTaskEntity task, int nextQueryCount, TencentCiAuditResult result) {
        if (result.providerFailed()) {
            taskRepository.markFailed(task.getId(), "腾讯云视频审核任务失败", result.rawPayload());
            workRepository.updateAuditStatus(task.getWorkId(), WorkAuditStatusDict.FAILED);
            log.info("视频作品审核简洁结果: workId={}, taskId={}, objectKey={}, ciJobId={}, ciState={}, auditResult={}, "
                            + "auditStatus={}, queryCount={}, maxQueryCount={}, reason=腾讯云任务失败",
                    task.getWorkId(), task.getId(), task.getMediaObjectKey(), task.getCiJobId(), result.ciState(),
                    result.auditResult().getCode(), WorkAuditStatusDict.FAILED.getCode(), nextQueryCount,
                    properties.getVideoQueryMaxAttempts());
            return;
        }
        if (result.terminal()) {
            if (result.auditResult() == AuditResultDict.UNKNOWN) {
                taskRepository.markFailed(task.getId(), "腾讯云视频审核结果未知", result.rawPayload());
                workRepository.updateAuditStatus(task.getWorkId(), WorkAuditStatusDict.FAILED);
                log.info("视频作品审核简洁结果: workId={}, taskId={}, objectKey={}, ciJobId={}, ciState={}, auditResult={}, "
                                + "auditStatus={}, queryCount={}, maxQueryCount={}, reason=审核结果未知",
                        task.getWorkId(), task.getId(), task.getMediaObjectKey(), task.getCiJobId(), result.ciState(),
                        result.auditResult().getCode(), WorkAuditStatusDict.FAILED.getCode(), nextQueryCount,
                        properties.getVideoQueryMaxAttempts());
                return;
            }
            taskRepository.markSuccess(task.getId(), result.auditResult(), result.ciState(), result.ciResult(),
                    result.ciLabel(), result.ciScore(), result.rawPayload());
            WorkAuditStatusDict auditStatus = mapWorkAuditStatus(result.auditResult());
            workRepository.updateAuditStatus(task.getWorkId(), auditStatus);
            log.info("视频作品审核简洁结果: workId={}, taskId={}, objectKey={}, ciJobId={}, ciState={}, auditResult={}, "
                            + "ciResult={}, ciLabel={}, ciScore={}, auditStatus={}, queryCount={}, maxQueryCount={}",
                    task.getWorkId(), task.getId(), task.getMediaObjectKey(), task.getCiJobId(), result.ciState(),
                    result.auditResult().getCode(), result.ciResult(), result.ciLabel(), result.ciScore(),
                    auditStatus.getCode(), nextQueryCount, properties.getVideoQueryMaxAttempts());
            return;
        }
        if (nextQueryCount >= properties.getVideoQueryMaxAttempts()) {
            taskRepository.markFailed(task.getId(), "视频审核查询次数超过上限", result.rawPayload());
            workRepository.updateAuditStatus(task.getWorkId(), WorkAuditStatusDict.FAILED);
            log.info("视频作品审核简洁结果: workId={}, taskId={}, objectKey={}, ciJobId={}, ciState={}, auditResult={}, "
                            + "auditStatus={}, queryCount={}, maxQueryCount={}, reason=查询次数超过上限",
                    task.getWorkId(), task.getId(), task.getMediaObjectKey(), task.getCiJobId(), result.ciState(),
                    result.auditResult().getCode(), WorkAuditStatusDict.FAILED.getCode(), nextQueryCount,
                    properties.getVideoQueryMaxAttempts());
            return;
        }
        taskRepository.markVideoRunning(task.getId(), result.ciState(), result.rawPayload());
        log.info("视频审核仍在处理中: workId={}, taskId={}, objectKey={}, ciJobId={}, ciState={}, auditResult={}, "
                        + "queryCount={}, maxQueryCount={}, nextStatus={}",
                task.getWorkId(), task.getId(), task.getMediaObjectKey(), task.getCiJobId(), result.ciState(),
                result.auditResult().getCode(), nextQueryCount, properties.getVideoQueryMaxAttempts(),
                WorkAuditTaskStatusDict.RUNNING.getCode());
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
