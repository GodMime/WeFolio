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
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

/**
 * 作品内容审核任务编排服务。
 */
@Slf4j
@Service
public class WorkAuditService {

    /** 毫秒到秒的换算值 */
    private static final int MILLIS_PER_SECOND = 1000;

    /** 错误类型字段 */
    private static final String ERROR_TYPE_FIELD = "errorType";

    /** 错误消息字段 */
    private static final String ERROR_MESSAGE_FIELD = "message";

    /** 审核拒绝原因最大长度 */
    private static final int AUDIT_REJECT_REASON_MAX_LENGTH = 512;

    /** 领取 token 分隔符 */
    private static final String CLAIM_TOKEN_SEPARATOR = "-";

    /** 锁实例前缀最大长度 */
    private static final int LOCK_OWNER_PREFIX_MAX_LENGTH = 80;

    /** 锁实例前缀为空时的兜底值 */
    private static final String DEFAULT_LOCK_OWNER_PREFIX = "local";

    /** 视频提交最后一次尝试中断错误摘要 */
    private static final String VIDEO_SUBMIT_INTERRUPTED_ERROR = "视频审核提交任务在最后一次尝试中断";

    /** 视频提交连续中断并达到恢复上限的作品原因 */
    private static final String VIDEO_SUBMIT_RECOVERY_LIMIT_REASON = "视频审核提交任务连续中断并达到最大恢复次数";

    /** 视频审核查询次数达到上限错误摘要 */
    private static final String VIDEO_QUERY_LIMIT_ERROR = "视频审核查询次数超过上限";

    /** 腾讯云视频审核任务失败错误摘要 */
    private static final String VIDEO_PROVIDER_FAILED_ERROR = "腾讯云视频审核任务失败";

    /** 腾讯云视频审核结果未知错误摘要 */
    private static final String VIDEO_UNKNOWN_RESULT_ERROR = "腾讯云视频审核结果未知";

    /** 任务领取竞争失败原因 */
    private static final String TASK_CLAIM_NOT_ACQUIRED_REASON = "未抢占到任务";

    /** 视频任务租约或作品审核轮次失效原因 */
    private static final String VIDEO_CLAIM_OR_ROUND_STALE_REASON = "任务租约已失效或作品审核轮次已变化";

    private final WorkAuditWorkRepository workRepository;

    private final WorkAuditTaskRepository taskRepository;

    private final WorkAuditClaimTransactionService claimTransactionService;

    private final TencentCiAuditClient auditClient;

    private final WorkAuditProperties properties;

    private final AnimationFrameSampler animationFrameSampler;

    private final AnimationAuditResultAggregator animationResultAggregator;

    private final AnimationFrameCosService animationFrameCosService;

    /**
     * 创建完整的作品审核编排服务。
     */
    @Autowired
    public WorkAuditService(
            WorkAuditWorkRepository workRepository,
            WorkAuditTaskRepository taskRepository,
            WorkAuditClaimTransactionService claimTransactionService,
            TencentCiAuditClient auditClient,
            WorkAuditProperties properties,
            AnimationFrameSampler animationFrameSampler,
            AnimationAuditResultAggregator animationResultAggregator,
            AnimationFrameCosService animationFrameCosService
    ) {
        this.workRepository = workRepository;
        this.taskRepository = taskRepository;
        this.claimTransactionService = claimTransactionService;
        this.auditClient = auditClient;
        this.properties = properties;
        this.animationFrameSampler = animationFrameSampler;
        this.animationResultAggregator = animationResultAggregator;
        this.animationFrameCosService = animationFrameCosService;
    }

    /**
     * 保留原有单元测试和轻量调用方构造方式。
     */
    WorkAuditService(
            WorkAuditWorkRepository workRepository,
            WorkAuditTaskRepository taskRepository,
            WorkAuditClaimTransactionService claimTransactionService,
            TencentCiAuditClient auditClient,
            WorkAuditProperties properties
    ) {
        this(workRepository, taskRepository, claimTransactionService, auditClient, properties,
                new AnimationFrameSampler(), new AnimationAuditResultAggregator(), null);
    }

    /**
     * 执行一轮作品审核任务。
     */
    public void runOneRound() {
        long startNanos = System.nanoTime();
        log.info("作品审核任务开始: 单轮视频查询任务上限={}, 单轮视频提交作品上限={}, 视频提交最大尝试次数={}, "
                        + "单轮图片审核作品上限={}, 单轮动图审核任务上限={}, 动图最大尝试次数={}, "
                        + "视频主动查询最大次数={}",
                properties.getMaxQueryVideoPerRun(), properties.getMaxSubmitVideoPerRun(),
                properties.getVideoSubmitMaxAttempts(), properties.getMaxAuditImagePerRun(),
                properties.getMaxAuditAnimationPerRun(),
                properties.getAnimationMaxAttempts(), properties.getVideoQueryMaxAttempts());
        try {
            LocalDateTime roundNow = LocalDateTime.now();
            logAuditBacklogSummary(roundNow);
            int remainingVideoSubmitCapacity = recoverExpiredVideoSubmissions(
                    properties.getMaxSubmitVideoPerRun(), roundNow);
            recoverExhaustedVideoQueries(properties.getMaxQueryVideoPerRun(), roundNow);
            queryPendingVideoResults(properties.getMaxQueryVideoPerRun(), roundNow);
            submitPendingVideoAudits(remainingVideoSubmitCapacity);
            auditPendingImages(properties.getMaxAuditImagePerRun());
            auditPendingAnimations(properties.getMaxAuditAnimationPerRun());
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
        queryPendingVideoResults(limit, LocalDateTime.now());
    }

    /**
     * 使用本轮统一时间查询可执行的视频审核任务。
     *
     * @param limit 查询数量上限
     * @param now 本轮统一时间
     */
    void queryPendingVideoResults(int limit, LocalDateTime now) {
        taskRepository.findQueryableVideoTasks(limit, properties.getVideoQueryMaxAttempts(), now)
                .forEach(task -> queryOneVideoResult(task, now));
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
     * 先终态回收达到上限的提交任务，再重领仍可重提的过期任务。
     *
     * @param limit 本轮视频提交容量
     * @param now 本轮统一时间
     * @return 新视频可使用的剩余提交容量
     */
    int recoverExpiredVideoSubmissions(int limit, LocalDateTime now) {
        int remainingCapacity = Math.max(limit, 0);
        int terminalRecovered = 0;
        int retried = 0;
        List<WorkAuditTaskEntity> exhaustedTasks = taskRepository.findExhaustedExpiredVideoSubmitTasks(
                Math.max(limit, 0), properties.getVideoSubmitMaxAttempts(), now);
        for (WorkAuditTaskEntity task : exhaustedTasks) {
            String claimToken = newClaimToken();
            if (!taskRepository.claimExhaustedExpiredVideoSubmit(
                    task.getId(), claimToken, now, lockedUntil(now),
                    properties.getVideoSubmitMaxAttempts())) {
                logVideoSubmitTerminalRecoverySkipped(task, TASK_CLAIM_NOT_ACQUIRED_REASON);
                continue;
            }
            task.setLockedBy(claimToken);
            boolean updated = claimTransactionService.markVideoTaskFailedAndUpdateWorkFailed(
                    task.getId(), task.getWorkId(), task.getAuditRound(), claimToken,
                    VIDEO_SUBMIT_INTERRUPTED_ERROR, null, VIDEO_SUBMIT_RECOVERY_LIMIT_REASON);
            if (updated) {
                terminalRecovered++;
            } else {
                logVideoSubmitTerminalRecoverySkipped(task, VIDEO_CLAIM_OR_ROUND_STALE_REASON);
            }
        }

        List<WorkAuditTaskEntity> retryableTasks = taskRepository.findRetryableExpiredVideoSubmitTasks(
                remainingCapacity, properties.getVideoSubmitMaxAttempts(), now);
        for (WorkAuditTaskEntity task : retryableTasks) {
            if (remainingCapacity == 0) {
                break;
            }
            String claimToken = newClaimToken();
            if (!taskRepository.claimExpiredVideoSubmit(
                    task.getId(), claimToken, now, lockedUntil(now),
                    properties.getVideoSubmitMaxAttempts())) {
                log.info("视频提交恢复跳过: workId={}, taskId={}, auditRound={}, attemptCount={}, "
                                + "maxAttempts={}, 原锁过期时间={}, reason={}",
                        task.getWorkId(), task.getId(), task.getAuditRound(), task.getAttemptCount(),
                        properties.getVideoSubmitMaxAttempts(), task.getLockedUntil(),
                        TASK_CLAIM_NOT_ACQUIRED_REASON);
                continue;
            }
            task.setLockedBy(claimToken);
            task.setAttemptCount(nullToZero(task.getAttemptCount()) + 1);
            submitClaimedVideoTask(task);
            remainingCapacity--;
            retried++;
        }
        log.info("视频提交恢复本轮统计: 终态回收数={}, 恢复提交数={}, 剩余新提交容量={}, 单轮上限={}",
                terminalRecovered, retried, remainingCapacity, limit);
        return remainingCapacity;
    }

    /**
     * 回收达到查询次数上限且租约已过期的视频任务。
     *
     * @param limit 回收数量上限
     * @param now 本轮统一时间
     */
    void recoverExhaustedVideoQueries(int limit, LocalDateTime now) {
        int recovered = 0;
        List<WorkAuditTaskEntity> tasks = taskRepository.findExhaustedExpiredVideoQueryTasks(
                Math.max(limit, 0), properties.getVideoQueryMaxAttempts(), now);
        for (WorkAuditTaskEntity task : tasks) {
            String claimToken = newClaimToken();
            if (!taskRepository.claimExhaustedExpiredVideoQuery(
                    task.getId(), claimToken, now, lockedUntil(now),
                    properties.getVideoQueryMaxAttempts())) {
                logVideoQueryTerminalRecoverySkipped(task, TASK_CLAIM_NOT_ACQUIRED_REASON);
                continue;
            }
            task.setLockedBy(claimToken);
            boolean updated = claimTransactionService.markVideoTaskFailedAndUpdateWorkFailed(
                    task.getId(), task.getWorkId(), task.getAuditRound(), claimToken,
                    VIDEO_QUERY_LIMIT_ERROR, null,
                    queryLimitReason(nullToZero(task.getQueryCount())));
            if (updated) {
                recovered++;
            } else {
                logVideoQueryTerminalRecoverySkipped(task, VIDEO_CLAIM_OR_ROUND_STALE_REASON);
            }
        }
        log.info("视频查询终态回收本轮统计: 回收数={}, 每轮上限={}", recovered, limit);
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
     * 先执行历史重试任务，再用剩余容量创建并立即执行新动图任务。
     *
     * @param limit 本轮实际尝试任务上限
     */
    public void auditPendingAnimations(int limit) {
        int remainingCapacity = Math.max(limit, 0);
        int retriedTasks = 0;
        int newTasks = 0;
        int recoveredTasks = recoverExhaustedAnimationTasks(remainingCapacity);
        List<WorkAuditTaskEntity> runnableTasks = taskRepository.findRunnableAnimationTasks(
                remainingCapacity, properties.getAnimationMaxAttempts(), LocalDateTime.now());
        for (WorkAuditTaskEntity task : runnableTasks) {
            if (remainingCapacity == 0) {
                break;
            }
            if (claimAndExecuteAnimationTask(task)) {
                remainingCapacity--;
                retriedTasks++;
            }
        }

        if (remainingCapacity > 0) {
            List<WorkAuditWorkEntity> pendingWorks = workRepository.findPendingAnimations(remainingCapacity);
            for (WorkAuditWorkEntity work : pendingWorks) {
                if (remainingCapacity == 0) {
                    break;
                }
                WorkAuditTaskEntity task = createPendingAnimationTask(work);
                if (task != null && claimAndExecuteAnimationTask(task)) {
                    remainingCapacity--;
                    newTasks++;
                }
            }
        }
        log.info("动图审核本轮统计: 崩溃终态回收数={}, 重试任务尝试数={}, 新建并立即执行数={}, "
                        + "剩余容量={}, 每轮上限={}",
                recoveredTasks, retriedTasks, newTasks, remainingCapacity, limit);
    }

    /**
     * 查询所有还没到结束态的视频审核结果。
     *
     * @param limit 查询数量上限
     */
    public void queryAllPendingVideoResults(int limit) {
        queryPendingVideoResults(limit);
    }

    private void logAuditBacklogSummary(LocalDateTime now) {
        long pendingImageWorks = workRepository.countPendingImages();
        long pendingVideoWorks = workRepository.countPendingVideos();
        long pendingAnimationWorks = workRepository.countPendingAnimations();
        long queryableVideoTasks = taskRepository.countQueryableVideoTasks(
                properties.getVideoQueryMaxAttempts(), now);
        long pendingTotalWorks = pendingImageWorks + pendingVideoWorks + pendingAnimationWorks;

        log.info("作品审核待处理统计: 待审核图片作品数={}, 待审核视频作品数={}, 待审核动图作品数={}, "
                        + "待审核作品总数={}, 待查询视频任务数={}, 视频主动查询最大次数={}",
                pendingImageWorks, pendingVideoWorks, pendingAnimationWorks, pendingTotalWorks,
                queryableVideoTasks, properties.getVideoQueryMaxAttempts());
        log.info("动图审核配置: 每轮上限={}, 最大尝试次数={}",
                properties.getMaxAuditAnimationPerRun(), properties.getAnimationMaxAttempts());
    }

    private WorkAuditTaskEntity createPendingAnimationTask(WorkAuditWorkEntity work) {
        List<Integer> sampledFrames = animationFrameSampler.sample(work.getFrameCount());
        WorkAuditTaskEntity task = newSubmittingTask(work, MediaTypeDict.ANIMATION);
        task = claimTransactionService.claimAndCreatePendingAnimationTask(
                work.getId(), task, sampledFrames);
        if (task == null) {
            log.info("动图作品审核跳过: workId={}, userId={}, objectKey={}, reason=未抢占到作品",
                    work.getId(), work.getUserId(), work.getMediaObjectKey());
        }
        return task;
    }

    private boolean claimAndExecuteAnimationTask(WorkAuditTaskEntity task) {
        String claimToken = newClaimToken();
        if (!taskRepository.claimAnimationTask(
                task.getId(),
                claimToken,
                lockedUntil(),
                properties.getAnimationMaxAttempts())) {
            log.info("动图审核任务跳过: workId={}, taskId={}, objectKey={}, reason=未抢占到任务",
                    task.getWorkId(), task.getId(), task.getMediaObjectKey());
            return false;
        }
        task.setLockedBy(claimToken);
        executeAnimationTask(task);
        return true;
    }

    private int recoverExhaustedAnimationTasks(int limit) {
        int recovered = 0;
        List<WorkAuditTaskEntity> tasks = taskRepository.findExhaustedExpiredAnimationTasks(
                Math.max(limit, 0), properties.getAnimationMaxAttempts(), LocalDateTime.now());
        for (WorkAuditTaskEntity task : tasks) {
            String claimToken = newClaimToken();
            if (!taskRepository.claimExhaustedAnimationTask(
                    task.getId(), claimToken, lockedUntil(), properties.getAnimationMaxAttempts())) {
                continue;
            }
            boolean updated = claimTransactionService.retryOrFailAnimationTask(
                    task.getId(),
                    task.getWorkId(),
                    task.getAuditRound(),
                    claimToken,
                    "动图审核任务在最后一次尝试中断",
                    null,
                    properties.getAnimationMaxAttempts());
            if (updated) {
                recovered++;
            }
        }
        return recovered;
    }

    private void executeAnimationTask(WorkAuditTaskEntity task) {
        List<Integer> frames;
        List<String> tempKeys = new ArrayList<>(2);
        try {
            frames = parsePersistedFrames(task.getSampledFrameNumbers());
            List<TencentCiAuditResult> results = new ArrayList<>(2);
            for (Integer frame : frames) {
                String generatedKey = animationFrameCosService.generate(task, frame);
                tempKeys.add(generatedKey);
                results.add(auditClient.auditImage(generatedKey));
            }
            TencentCiAuditResult outcome = animationResultAggregator.aggregate(frames, results);
            if (outcome.auditResult() == AuditResultDict.UNKNOWN) {
                claimTransactionService.retryOrFailAnimationTask(
                        task.getId(),
                        task.getWorkId(),
                        task.getAuditRound(),
                        task.getLockedBy(),
                        "动图审核结果未知",
                        outcome.rawPayload(),
                        properties.getAnimationMaxAttempts());
                return;
            }
            WorkAuditStatusDict auditStatus = mapWorkAuditStatus(outcome.auditResult());
            String auditRejectReason = auditRejectReason(outcome);
            boolean updated = claimTransactionService.markAnimationTaskSuccessAndUpdateWork(
                    task.getId(),
                    task.getWorkId(),
                    task.getAuditRound(),
                    task.getLockedBy(),
                    outcome.auditResult(),
                    outcome.ciState(),
                    outcome.ciResult(),
                    outcome.ciLabel(),
                    outcome.ciScore(),
                    outcome.risks(),
                    outcome.rawPayload(),
                    auditStatus,
                    auditRejectReason);
            if (!updated) {
                log.info("动图审核结果丢弃: workId={}, taskId={}, reason=任务租约已失效或作品审核轮次已变化",
                        task.getWorkId(), task.getId());
                return;
            }
            log.info("动图作品审核完成: workId={}, taskId={}, objectKey={}, sampledFrames={}, "
                            + "auditResult={}, auditStatus={}",
                    task.getWorkId(), task.getId(), task.getMediaObjectKey(), task.getSampledFrameNumbers(),
                    outcome.auditResult().getCode(), auditStatus.getCode());
        } catch (RuntimeException exception) {
            claimTransactionService.retryOrFailAnimationTask(
                    task.getId(),
                    task.getWorkId(),
                    task.getAuditRound(),
                    task.getLockedBy(),
                    errorMessage(exception),
                    errorPayload(exception),
                    properties.getAnimationMaxAttempts());
            log.warn("动图审核任务执行失败: workId={}, taskId={}, objectKey={}, sampledFrames={}, error={}",
                    task.getWorkId(), task.getId(), task.getMediaObjectKey(),
                    task.getSampledFrameNumbers(), errorMessage(exception));
        } finally {
            tempKeys.forEach(key -> animationFrameCosService.deleteQuietly(key, task.getId()));
        }
    }

    private String newClaimToken() {
        String prefix = properties.getLockOwnerPrefix();
        String normalizedPrefix = prefix == null ? "" : prefix.strip();
        if (normalizedPrefix.isEmpty()) {
            normalizedPrefix = DEFAULT_LOCK_OWNER_PREFIX;
        }
        if (normalizedPrefix.length() > LOCK_OWNER_PREFIX_MAX_LENGTH) {
            normalizedPrefix = normalizedPrefix.substring(0, LOCK_OWNER_PREFIX_MAX_LENGTH);
        }
        return normalizedPrefix
                + CLAIM_TOKEN_SEPARATOR
                + UUID.randomUUID().toString().replace(CLAIM_TOKEN_SEPARATOR, "");
    }

    private List<Integer> parsePersistedFrames(String sampledFrameNumbers) {
        List<Integer> frames;
        try {
            frames = JSON.parseArray(sampledFrameNumbers, Integer.class);
        } catch (RuntimeException exception) {
            throw new IllegalArgumentException("动图审核抽样帧 JSON 非法", exception);
        }
        if (frames == null || frames.size() != 2
                || frames.stream().anyMatch(frame -> frame == null || frame < 1 || frame > 300)
                || frames.get(0).equals(frames.get(1))) {
            throw new IllegalArgumentException("动图审核必须保存两个不同且有效的帧号");
        }
        return frames.stream().sorted(Comparator.naturalOrder()).toList();
    }

    private void submitOneVideo(WorkAuditWorkEntity work) {
        int snapshotCount = calculateSnapshotCount(work.getDurationMs());
        WorkAuditTaskEntity task = newSubmittingTask(work, MediaTypeDict.VIDEO);
        task.setLockedBy(newClaimToken());
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

        submitClaimedVideoTask(task);
    }

    private void submitClaimedVideoTask(WorkAuditTaskEntity task) {
        try {
            TencentCiAuditResult result = auditClient.submitVideo(
                    task.getMediaObjectKey(),
                    task.getSnapshotIntervalSeconds(),
                    task.getSnapshotCount());
            boolean updated = claimTransactionService.markVideoSubmittedAndKeepWorkAuditing(
                    task.getId(), task.getWorkId(), task.getAuditRound(), task.getLockedBy(),
                    result.ciJobId(), result.rawPayload());
            if (!updated) {
                logDiscardedVideoResult(task);
                return;
            }
            log.info("视频作品审核提交完成: workId={}, taskId={}, objectKey={}, ciJobId={}, ciState={}, "
                            + "auditResult={}, queryCount={}, maxQueryCount={}, nextStatus={}",
                    task.getWorkId(), task.getId(), task.getMediaObjectKey(), result.ciJobId(), result.ciState(),
                    result.auditResult().getCode(), task.getQueryCount(), properties.getVideoQueryMaxAttempts(),
                    WorkAuditTaskStatusDict.SUBMITTED.getCode());
        } catch (RuntimeException ex) {
            String errorMessage = errorMessage(ex);
            String auditRejectReason = throwableReason("提交腾讯云视频审核失败", ex);
            log.warn("提交腾讯云视频审核失败: workId={}, taskId={}, objectKey={}, attemptCount={}, error={}",
                    task.getWorkId(), task.getId(), task.getMediaObjectKey(), task.getAttemptCount(), errorMessage);
            boolean updated = claimTransactionService.markVideoTaskFailedAndUpdateWorkFailed(
                    task.getId(), task.getWorkId(), task.getAuditRound(), task.getLockedBy(),
                    errorMessage, errorPayload(ex), auditRejectReason);
            if (!updated) {
                logDiscardedVideoResult(task);
                return;
            }
            log.info("视频作品审核简洁结果: workId={}, taskId={}, objectKey={}, auditStatus={}, auditRejectReason={}",
                    task.getWorkId(), task.getId(), task.getMediaObjectKey(),
                    WorkAuditStatusDict.FAILED.getCode(), auditRejectReason);
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
                    result.ciState(), result.ciResult(), result.ciLabel(), result.ciScore(), result.risks(),
                    result.rawPayload(), auditStatus, auditRejectReason);
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

    private void queryOneVideoResult(WorkAuditTaskEntity task, LocalDateTime now) {
        int nextQueryCount = nullToZero(task.getQueryCount()) + 1;
        String claimToken = newClaimToken();
        if (!taskRepository.claimVideoQuery(task.getId(), claimToken, now,
                lockedUntil(now), properties.getVideoQueryMaxAttempts())) {
            log.info("视频审核结果查询跳过: workId={}, taskId={}, objectKey={}, ciJobId={}, queryCount={}, maxQueryCount={}, "
                            + "reason={}",
                    task.getWorkId(), task.getId(), task.getMediaObjectKey(), task.getCiJobId(),
                    task.getQueryCount(), properties.getVideoQueryMaxAttempts(), TASK_CLAIM_NOT_ACQUIRED_REASON);
            return;
        }
        task.setLockedBy(claimToken);
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
                boolean updated = claimTransactionService.markVideoTaskFailedAndUpdateWorkFailed(
                        task.getId(), task.getWorkId(), task.getAuditRound(), task.getLockedBy(),
                        VIDEO_QUERY_LIMIT_ERROR, errorPayload(ex), auditRejectReason);
                if (!updated) {
                    logDiscardedVideoResult(task);
                    return;
                }
                log.info("视频作品审核简洁结果: workId={}, taskId={}, objectKey={}, ciJobId={}, auditStatus={}, "
                                + "queryCount={}, maxQueryCount={}, auditRejectReason={}",
                        task.getWorkId(), task.getId(), task.getMediaObjectKey(), task.getCiJobId(),
                        WorkAuditStatusDict.FAILED.getCode(), nextQueryCount, properties.getVideoQueryMaxAttempts(),
                        auditRejectReason);
                return;
            }
            boolean updated = claimTransactionService.markVideoQueryFailureForNextRunAndKeepWorkAuditing(
                    task.getId(), task.getWorkId(), task.getAuditRound(), task.getLockedBy(),
                    errorMessage(ex), errorPayload(ex));
            if (!updated) {
                logDiscardedVideoResult(task);
                return;
            }
            log.info("视频审核查询失败后回到运行中: workId={}, taskId={}, objectKey={}, ciJobId={}, queryCount={}, "
                            + "maxQueryCount={}, nextStatus={}",
                    task.getWorkId(), task.getId(), task.getMediaObjectKey(), task.getCiJobId(), nextQueryCount,
                    properties.getVideoQueryMaxAttempts(), WorkAuditTaskStatusDict.RUNNING.getCode());
        }
    }

    private void handleVideoQueryResult(WorkAuditTaskEntity task, int nextQueryCount, TencentCiAuditResult result) {
        if (result.providerFailed()) {
            String auditRejectReason = videoProviderFailedReason(task, result);
            boolean updated = claimTransactionService.markVideoTaskFailedAndUpdateWorkFailed(
                    task.getId(), task.getWorkId(), task.getAuditRound(), task.getLockedBy(),
                    VIDEO_PROVIDER_FAILED_ERROR, result.rawPayload(), auditRejectReason);
            if (!updated) {
                logDiscardedVideoResult(task);
                return;
            }
            log.info("视频作品审核简洁结果: workId={}, taskId={}, objectKey={}, ciJobId={}, ciState={}, auditResult={}, "
                            + "auditStatus={}, queryCount={}, maxQueryCount={}, auditRejectReason={}",
                    task.getWorkId(), task.getId(), task.getMediaObjectKey(), task.getCiJobId(), result.ciState(),
                    result.auditResult().getCode(), WorkAuditStatusDict.FAILED.getCode(), nextQueryCount,
                    properties.getVideoQueryMaxAttempts(), auditRejectReason);
            return;
        }
        if (result.terminal()) {
            if (result.auditResult() == AuditResultDict.UNKNOWN) {
                String auditRejectReason = unknownAuditResultReason(VIDEO_UNKNOWN_RESULT_ERROR, result);
                boolean updated = claimTransactionService.markVideoTaskFailedAndUpdateWorkFailed(
                        task.getId(), task.getWorkId(), task.getAuditRound(), task.getLockedBy(),
                        VIDEO_UNKNOWN_RESULT_ERROR, result.rawPayload(), auditRejectReason);
                if (!updated) {
                    logDiscardedVideoResult(task);
                    return;
                }
                log.info("视频作品审核简洁结果: workId={}, taskId={}, objectKey={}, ciJobId={}, ciState={}, auditResult={}, "
                                + "auditStatus={}, queryCount={}, maxQueryCount={}, auditRejectReason={}",
                        task.getWorkId(), task.getId(), task.getMediaObjectKey(), task.getCiJobId(), result.ciState(),
                        result.auditResult().getCode(), WorkAuditStatusDict.FAILED.getCode(), nextQueryCount,
                        properties.getVideoQueryMaxAttempts(), auditRejectReason);
                return;
            }
            WorkAuditStatusDict auditStatus = mapWorkAuditStatus(result.auditResult());
            String auditRejectReason = auditRejectReason(result);
            boolean updated = claimTransactionService.markVideoTaskSuccessAndUpdateWork(
                    task.getId(), task.getWorkId(), task.getAuditRound(), task.getLockedBy(),
                    result.auditResult(), result.ciState(), result.ciResult(), result.ciLabel(), result.ciScore(),
                    result.risks(), result.rawPayload(), auditStatus, auditRejectReason);
            if (!updated) {
                logDiscardedVideoResult(task);
                return;
            }
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
            boolean updated = claimTransactionService.markVideoTaskFailedAndUpdateWorkFailed(
                    task.getId(), task.getWorkId(), task.getAuditRound(), task.getLockedBy(),
                    VIDEO_QUERY_LIMIT_ERROR, result.rawPayload(), auditRejectReason);
            if (!updated) {
                logDiscardedVideoResult(task);
                return;
            }
            log.info("视频作品审核简洁结果: workId={}, taskId={}, objectKey={}, ciJobId={}, ciState={}, auditResult={}, "
                            + "auditStatus={}, queryCount={}, maxQueryCount={}, auditRejectReason={}",
                    task.getWorkId(), task.getId(), task.getMediaObjectKey(), task.getCiJobId(), result.ciState(),
                    result.auditResult().getCode(), WorkAuditStatusDict.FAILED.getCode(), nextQueryCount,
                    properties.getVideoQueryMaxAttempts(), auditRejectReason);
            return;
        }
        boolean updated = claimTransactionService.markVideoRunningAndKeepWorkAuditing(
                task.getId(), task.getWorkId(), task.getAuditRound(), task.getLockedBy(),
                result.ciState(), result.rawPayload());
        if (!updated) {
            logDiscardedVideoResult(task);
            return;
        }
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
        return truncateAuditRejectReason(VIDEO_PROVIDER_FAILED_ERROR + "：state=" + result.ciState()
                + "，jobId=" + task.getCiJobId());
    }

    /**
     * 生成视频主动查询次数超过上限的失败原因。
     *
     * @param queryCount 当前查询次数
     * @return 失败原因
     */
    private String queryLimitReason(int queryCount) {
        return truncateAuditRejectReason(VIDEO_QUERY_LIMIT_ERROR + "：queryCount=" + queryCount
                + "，maxQueryCount=" + properties.getVideoQueryMaxAttempts());
    }

    /**
     * 记录达到视频提交重试上限的任务未能完成终态回收。
     *
     * @param task 审核任务
     * @param reason 跳过原因
     */
    private void logVideoSubmitTerminalRecoverySkipped(WorkAuditTaskEntity task, String reason) {
        log.info("视频提交终态回收跳过: workId={}, taskId={}, auditRound={}, attemptCount={}, "
                        + "maxAttempts={}, 原锁过期时间={}, reason={}",
                task.getWorkId(), task.getId(), task.getAuditRound(), task.getAttemptCount(),
                properties.getVideoSubmitMaxAttempts(), task.getLockedUntil(), reason);
    }

    /**
     * 记录达到视频查询上限的任务未能完成终态回收。
     *
     * @param task 审核任务
     * @param reason 跳过原因
     */
    private void logVideoQueryTerminalRecoverySkipped(WorkAuditTaskEntity task, String reason) {
        log.info("视频查询终态回收跳过: workId={}, taskId={}, auditRound={}, queryCount={}, "
                        + "maxQueryCount={}, 原锁过期时间={}, reason={}",
                task.getWorkId(), task.getId(), task.getAuditRound(), task.getQueryCount(),
                properties.getVideoQueryMaxAttempts(), task.getLockedUntil(), reason);
    }

    /**
     * 记录因任务租约或作品审核轮次失效而丢弃的视频审核结果。
     *
     * @param task 审核任务
     */
    private void logDiscardedVideoResult(WorkAuditTaskEntity task) {
        log.info("视频审核结果丢弃: workId={}, taskId={}, reason={}",
                task.getWorkId(), task.getId(), VIDEO_CLAIM_OR_ROUND_STALE_REASON);
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
        task.setAuditRound(work.getAuditRound() == null ? 1 : work.getAuditRound());
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

    /**
     * 根据指定基准时间计算任务租约到期时间。
     *
     * @param now 租约开始时间
     * @return 租约到期时间
     */
    private LocalDateTime lockedUntil(LocalDateTime now) {
        return now.plusSeconds(properties.getTaskLockSeconds());
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
