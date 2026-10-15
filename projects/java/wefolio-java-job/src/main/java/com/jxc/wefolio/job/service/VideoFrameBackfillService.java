package com.jxc.wefolio.job.service;

import com.alibaba.fastjson2.JSON;
import com.jxc.wefolio.job.config.VideoFrameProperties;
import com.jxc.wefolio.job.model.VideoFrameBackfillModels.Summary;
import com.jxc.wefolio.job.model.VideoFrameBackfillModels.Work;
import com.jxc.wefolio.job.repo.VideoFrameBackfillRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** 历史一次性扫描：只持久化完整帧清单，成功和失败均通过执行编号关联日志。 */
@Service
@RequiredArgsConstructor
@Slf4j
public class VideoFrameBackfillService {
    /** 失败继续下一件；预算耗尽或执行锁丢失停止本次扫描。 */
    private enum Outcome { SUCCESS, SKIPPED, FAILED, STALE, BUDGET }
    /** 日志固定阶段与本次处理前缀。 */
    private static final String SOURCE_VERIFY = "SOURCE_VERIFY";
    private static final String FRAME_SUBMIT = "FRAME_SUBMIT";
    private static final String FRAME_QUERY = "FRAME_QUERY";
    private static final String FRAME_LIST = "FRAME_LIST";
    private static final String FRAME_UPLOAD = "FRAME_UPLOAD";
    private static final String FRAME_CLEANUP = "FRAME_CLEANUP";
    private static final String MANIFEST_SAVE = "MANIFEST_SAVE";
    /** 重建先验证旧对象边界，再以数据库 CAS 清空引用和派生摘要。 */
    private static final String MANIFEST_CLEAR = "MANIFEST_CLEAR";
    private static final String AUDIO_PREFLIGHT = "AUDIO_PREFLIGHT";
    private static final String AUDIO_TRANSCRIBE = "AUDIO_TRANSCRIBE";
    private static final String SUMMARY_PREFLIGHT = "SUMMARY_PREFLIGHT";
    private static final String SUMMARY_GENERATE = "SUMMARY_GENERATE";
    private static final String BACKFILL = "BACKFILL";
    private static final String REBUILD = "REBUILD";
    private static final String PROCESS_INTERRUPTED = "PROCESS_INTERRUPTED";
    private static final String EXECUTION_LOCK = "EXECUTION_LOCK";
    private static final String SUCCESS = "SUCCESS";
    private static final String COMPLETED_WITH_FAILURES = "COMPLETED_WITH_FAILURES";
    private static final String BUDGET_EXHAUSTED = "BUDGET_EXHAUSTED";
    private static final String HISTORY_TOKEN = "history-";
    private static final String RAW_DIRECTORY = "raw/";
    /** 只读作品扫描与完整清单 CAS。 */
    private final VideoFrameBackfillRepository repository;
    /** 新审核与历史共用持久帧存储。 */
    private final VideoFrameStorageService storage;
    /** 历史直接截图客户端，不隐藏重试。 */
    private final VideoFrameSnapshotClient snapshots;
    /** 单件期限、分页及远端边界。 */
    private final VideoFrameProperties properties;
    /** 三入口共用转写，预算和临时资源由模块统一管理。 */
    private final VideoAudioTranscriptionService audio;
    /** 原视频摘要独立于帧和音轨是否成功，使用本轮专属请求与费用预算。 */
    private final VideoSummaryService videoSummary;

    /**
     * 本次固定水位、游标和预算只留内存；重启后显式重新扫描，已发布清单自动跳过。
     * 无明细表，因此已付费但未保存的作品下次显式执行可能再次计费。
     * 队列由入口一次解析并冻结，逐作品提交只复用本次真实队列 ID。
     */
    public Summary run(String executionId, int maxFrames, VideoFrameBackfillLock.ExecutionLock executionLock,
            String queueId) {
        return runScan(executionId, maxFrames, 0, null, executionLock, queueId, false);
    }

    /** 显式范围内逐件清空后重建，游标只用于手动接续扫描，不代表持久化任务恢复。 */
    public Summary runRebuild(String executionId, int maxFrames, long afterWorkId, Long throughWorkId,
            VideoFrameBackfillLock.ExecutionLock executionLock, String queueId) {
        if (afterWorkId < 0 || throughWorkId != null && throughWorkId <= afterWorkId) {
            throw new IllegalArgumentException("视频帧重建作品范围非法");
        }
        return runScan(executionId, maxFrames, afterWorkId, throughWorkId, executionLock, queueId, true);
    }

    /** 补帧与重建共用截图、特征提取、上传和发布流程；重建仅增加前置清理步骤。 */
    private Summary runScan(String executionId, int maxFrames, long afterWorkId, Long throughWorkId,
            VideoFrameBackfillLock.ExecutionLock executionLock, String queueId, boolean rebuild) {
        MutableSummary summary = new MutableSummary(maxFrames);
        VideoAudioTranscriptionService.RunBudget audioRun = audio.newRun();
        VideoSummaryService.RunBudget summaryRun = videoSummary.newRun();
        long cursor = afterWorkId;
        long highWatermark = 0;
        String result = SUCCESS;
        try {
            executionLock.checkHeld();
            highWatermark = repository.highWatermark();
            if (throughWorkId != null) { highWatermark = Math.min(highWatermark, throughWorkId); }
            log.info("视频帧扫描开始: executionId={}, operation={}, afterWorkId={}, highWatermark={}, maxFrames={}",
                    executionId, rebuild ? REBUILD : BACKFILL, afterWorkId, highWatermark, maxFrames);
            while (true) {
                executionLock.checkHeld();
                List<Work> page = repository.scan(cursor, highWatermark, properties.getBackfillBatchSize());
                if (page.isEmpty()) { break; }
                for (Work work : page) {
                    // 每件开头检查，不把丢锁当成普通作品失败后继续处理其它作品。
                    executionLock.checkHeld();
                    if (Thread.currentThread().isInterrupted()) {
                        throw new IllegalStateException("历史补帧线程已中断，本次扫描停止");
                    }
                    summary.scanned++;
                    Outcome outcome = processOne(executionId, work, summary, executionLock, queueId, rebuild, audioRun, summaryRun);
                    if (outcome == Outcome.BUDGET) {
                        summary.budgetExhausted = true;
                        result = BUDGET_EXHAUSTED;
                        break;
                    }
                    switch (outcome) {
                        case SUCCESS -> summary.succeeded++;
                        case SKIPPED -> summary.skipped++;
                        case FAILED, STALE -> summary.failed++;
                        default -> throw new IllegalStateException("历史补帧执行结果非法");
                    }
                    if (work.id() <= cursor) { throw new IllegalStateException("历史补帧游标未推进"); }
                    cursor = work.id();
                }
                if (summary.budgetExhausted) { break; }
                log.info("历史视频补帧分页完成: executionId={}, cursor={}, highWatermark={}, chargedFrames={}",
                        executionId, cursor, highWatermark, summary.chargedFrames);
            }
            if (SUCCESS.equals(result) && (summary.failed > 0 || summary.audioBusy > 0 || summary.summaryBusy > 0)) { result = COMPLETED_WITH_FAILURES; }
        } catch (RuntimeException exception) {
            result = PROCESS_INTERRUPTED;
            log.error("历史视频补帧扫描中断: executionId={}, cursor={}, stage={}, exceptionType={}",
                    executionId, cursor, PROCESS_INTERRUPTED, exception.getClass().getSimpleName());
        }
        Summary completed = summary.snapshot();
        log.info("历史视频补帧执行结束: executionId={}, status={}, highWatermark={}, cursor={}, scanned={}, "
                        + "succeeded={}, skipped={}, failed={}, chargedFrames={}, maxFrames={}",
                executionId, result, highWatermark, cursor, completed.scanned(), completed.succeeded(),
                completed.skipped(), completed.failed(), completed.chargedFrames(), maxFrames);
        log.info("历史视频音频处理汇总: executionId={}, frameSucceeded={}, audioSaved={}, audioEmpty={}, "
                        + "audioFailed={}, audioSkipped={}, audioBusy={}, audioBudgetDeferred={}, audioDisabled={}",
                executionId, summary.frameSucceeded, summary.audioSaved, summary.audioEmpty, summary.audioFailed,
                summary.audioSkipped, summary.audioBusy, summary.audioBudgetDeferred, summary.audioDisabled);
        if (audioRun != null) {
            log.info("历史视频音频计量汇总: executionId={}, reservedRequests={}, reservedSeconds={}, sentRequests={}, "
                            + "knownTokens={}, usageUnknownCount={}, accountStopped={}", executionId, audioRun.requests(),
                    audioRun.seconds(), audioRun.sentRequests(), audioRun.knownTokens(), audioRun.unknownUsageCount(), audioRun.stopped());
        }
        log.info("历史视频摘要处理汇总: executionId={}, summarySaved={}, summaryFailed={}, summarySkipped={}, "
                        + "summaryBusy={}, summaryBudgetDeferred={}, summaryUnsupported={}, summaryDisabled={}",
                executionId, summary.summarySaved, summary.summaryFailed, summary.summarySkipped, summary.summaryBusy,
                summary.summaryBudgetDeferred, summary.summaryUnsupported, summary.summaryDisabled);
        if (summaryRun != null) {
            log.info("历史视频摘要计量汇总: executionId={}, reservedRequests={}, reservedCostFen={}, sentRequests={}, "
                            + "knownTokens={}, knownCostFen={}, usageUnknownCount={}, accountStopped={}", executionId,
                    summaryRun.requests(), summaryRun.reservedCostFen(), summaryRun.sentRequests(), summaryRun.knownTokens(),
                    summaryRun.knownCostFen(), summaryRun.unknownUsageCount(), summaryRun.stopped());
        }
        if (summary.audioFailed > 0 || summary.audioBusy > 0 || summary.audioBudgetDeferred > 0) {
            log.warn("历史视频有转写未完成: executionId={}, audioFailed={}, audioBusy={}, audioBudgetDeferred={}",
                    executionId, summary.audioFailed, summary.audioBusy, summary.audioBudgetDeferred);
        }
        if (rebuild) {
            log.info("视频帧重建扫描结束: executionId={}, status={}, afterWorkId={}, highWatermark={}, nextAfterWorkId={}, "
                            + "succeeded={}, failed={}, chargedFrames={}",
                    executionId, result, afterWorkId, highWatermark, cursor, completed.succeeded(),
                    completed.failed(), completed.chargedFrames());
        }
        return completed;
    }

    /**
     * 单件总期限覆盖三阶段；普通帧和音频失败不阻止原视频摘要独立复验。
     * 重建在清空前验证源和预算；摘要规格不支持只跳过摘要，帧和音频仍独立执行。
     * 单件预检失败保留旧数据并继续后续作品，摘要预留凭证跨阶段复用，不重复预留。
     */
    private Outcome processOne(String executionId, Work work, MutableSummary summary,
            VideoFrameBackfillLock.ExecutionLock executionLock, String queueId, boolean rebuild,
            VideoAudioTranscriptionService.RunBudget audioRun, VideoSummaryService.RunBudget summaryRun) {
        Instant started = Instant.now();
        Instant overallDeadline = started.plusSeconds(Math.max(15, properties.getBackfillItemSeconds())
                + audio.audioTimeoutSeconds() + videoSummary.summaryTimeoutSeconds());
        VideoAudioTranscriptionService.Preparation prepared = null;
        VideoSummaryService.Preparation summaryPrepared = null;
        String stage = SOURCE_VERIFY;
        try {
            if (!repository.ownedSource(work)) {
                throw new VideoFrameProcessingException(SOURCE_VERIFY, "历史视频目录不属于作品作者");
            }
            VideoFrameSource source = storage.inspectSource(work.objectKey(), work.sha256());
            Instant frameDeadline = earlier(started.plusSeconds(Math.max(15, properties.getBackfillItemSeconds())),
                    overallDeadline);
            boolean framesMissing = rebuild || !storage.hasValidManifest(work.manifest(), source, work.id(), frameDeadline);
            if (framesMissing && !summary.canReserve(VideoFramePolicy.calculateFrameCount(work.durationMs()))) {
                return Outcome.BUDGET;
            }
            if (rebuild) {
                stage = FRAME_CLEANUP;
                storage.validateOwnedManifestForCleanup(work.manifest(), source, work.id());
                checkRemainingTime(frameDeadline);
                stage = AUDIO_PREFLIGHT;
                prepared = audio.prepare(work.id(), true, audioRun, overallDeadline, executionLock::checkHeld);
                executionLock.checkHeld();
                if (!prepared.ready() && prepared.outcome() != VideoAudioTranscriptionService.Outcome.DISABLED) {
                    Outcome refusal = recordAudio(executionId, work.id(), prepared.outcome(), summary, false);
                    return refusal == Outcome.BUDGET ? Outcome.BUDGET : Outcome.FAILED;
                }
                stage = SUMMARY_PREFLIGHT;
                // 摘要预检仅核验源和保守费用，不持有音频已经领取的作品命名锁。
                summaryPrepared = videoSummary.prepare(work.id(), true, summaryRun, overallDeadline, executionLock::checkHeld);
                executionLock.checkHeld();
                if (!summaryPrepared.ready() && summaryPrepared.outcome() != VideoSummaryService.Outcome.DISABLED
                        && summaryPrepared.outcome() != VideoSummaryService.Outcome.NOT_SUPPORTED) {
                    Outcome refusal = recordSummary(executionId, work.id(), summaryPrepared.outcome(), summary);
                    return refusal == Outcome.BUDGET ? Outcome.BUDGET : Outcome.FAILED;
                }
                if (summaryPrepared.outcome() == VideoSummaryService.Outcome.NOT_SUPPORTED) {
                    log.info("历史视频摘要规格不支持，继续处理帧和音频: executionId={}, workId={}", executionId, work.id());
                }
                frameDeadline = earlier(Instant.now().plusSeconds(Math.max(15, properties.getBackfillItemSeconds())),
                        overallDeadline);
                storage.verifySource(source, frameDeadline);
            }
            boolean clearVideoSummary = summaryPrepared == null
                    || summaryPrepared.outcome() != VideoSummaryService.Outcome.NOT_SUPPORTED;
            Outcome frames = framesMissing ? processFrames(executionId, work, summary, executionLock, queueId,
                    rebuild, clearVideoSummary, source, frameDeadline) : Outcome.SKIPPED;
            if (frames == Outcome.BUDGET || frames == Outcome.STALE) { return frames; }
            Outcome audioResult = Outcome.SKIPPED;
            if (frames != Outcome.FAILED) {
                stage = AUDIO_TRANSCRIBE;
                VideoAudioTranscriptionService.Outcome audioOutcome;
                try {
                    audioOutcome = prepared == null
                            ? audio.transcribe(work.id(), audioRun, earlier(audio.audioDeadline(), overallDeadline),
                                    executionLock::checkHeld)
                            : audio.complete(prepared, overallDeadline);
                } catch (RuntimeException exception) {
                    log.warn("历史视频转写异常，独立尝试摘要: executionId={}, workId={}, exceptionType={}",
                            executionId, work.id(), exception.getClass().getSimpleName());
                    audioOutcome = VideoAudioTranscriptionService.Outcome.FAILED;
                }
                executionLock.checkHeld();
                audioResult = recordAudio(executionId, work.id(), audioOutcome, summary, frames == Outcome.SUCCESS);
                if (audioResult == Outcome.BUDGET || audioOutcome == VideoAudioTranscriptionService.Outcome.STALE
                        || audioOutcome == VideoAudioTranscriptionService.Outcome.BUSY) { return audioResult; }
            }
            // 摘要以自己的 JDBC 会话领取同一作品锁，必须先完整释放音频预检资源。
            if (prepared != null) { prepared.close(); prepared = null; }
            stage = SUMMARY_GENERATE;
            if (Thread.currentThread().isInterrupted()) { throw stopped(); }
            VideoSummaryService.Outcome generated;
            if (summaryPrepared != null && !summaryPrepared.ready()) {
                // 规格不支持的结果直接记账，不重新预检或调用模型，也不丢失已保存的音频。
                generated = summaryPrepared.outcome();
            } else {
                if (!Instant.now().isBefore(overallDeadline)) { return Outcome.FAILED; }
                generated = summaryPrepared == null
                        ? videoSummary.summarize(work.id(), work.objectKey(), work.sha256(), summaryRun,
                                earlier(videoSummary.summaryDeadline(), overallDeadline), executionLock::checkHeld)
                        : videoSummary.complete(summaryPrepared, overallDeadline);
            }
            executionLock.checkHeld();
            Outcome summaryResult = recordSummary(executionId, work.id(), generated, summary);
            if (summaryResult == Outcome.BUDGET) { return Outcome.BUDGET; }
            Outcome result = combine(frames, audioResult, summaryResult);
            if (result == Outcome.SKIPPED) {
                log.info("历史视频补帧跳过: executionId={}, workId={}, stage={}, reason=已完成或未开启派生阶段",
                        executionId, work.id(), SOURCE_VERIFY);
            }
            return result;
        } catch (RuntimeException exception) {
            if (exception instanceof VideoFrameProcessingException processing
                    && EXECUTION_LOCK.equals(processing.getStage())) { throw exception; }
            executionLock.checkHeld();
            if (Thread.currentThread().isInterrupted()) { throw stopped(); }
            String failureStage = exception instanceof VideoFrameProcessingException processing
                    ? processing.getStage() : stage;
            log.error("历史视频补帧失败: executionId={}, workId={}, stage={}, exceptionType={}",
                    executionId, work.id(), failureStage, exception.getClass().getSimpleName());
            if (AUDIO_PREFLIGHT.equals(stage) || AUDIO_TRANSCRIBE.equals(stage)) { summary.audioFailed++; }
            if (SUMMARY_PREFLIGHT.equals(stage) || SUMMARY_GENERATE.equals(stage)) { summary.summaryFailed++; }
            return Outcome.FAILED;
        } finally {
            if (prepared != null) { prepared.close(); }
            if (summaryPrepared != null) { summaryPrepared.close(); }
        }
    }

    /** 部分成功保留结果但仍报告补偿需求，关闭阶段不构成失败。 */
    private Outcome combine(Outcome frames, Outcome audioResult, Outcome summaryResult) {
        if (frames == Outcome.FAILED || audioResult == Outcome.FAILED || summaryResult == Outcome.FAILED) {
            return Outcome.FAILED;
        }
        return frames == Outcome.SUCCESS || audioResult == Outcome.SUCCESS || summaryResult == Outcome.SUCCESS
                ? Outcome.SUCCESS : Outcome.SKIPPED;
    }

    /** 原视频摘要独立统计，规格超限记为单件部分失败，不撤销帧和音频结果。 */
    private Outcome recordSummary(String executionId, long workId, VideoSummaryService.Outcome outcome,
            MutableSummary summary) {
        log.info("历史视频摘要结果: executionId={}, workId={}, outcome={}", executionId, workId, outcome);
        return switch (outcome) {
            case SAVED -> { summary.summarySaved++; yield Outcome.SUCCESS; }
            case ALREADY_COMPLETE -> { summary.summarySkipped++; yield Outcome.SKIPPED; }
            case DISABLED -> { summary.summaryDisabled++; yield Outcome.SKIPPED; }
            case BUSY, STALE -> { summary.summaryBusy++; yield Outcome.SKIPPED; }
            case BUDGET_EXHAUSTED -> { summary.summaryBudgetDeferred++; yield Outcome.BUDGET; }
            case NOT_SUPPORTED -> { summary.summaryUnsupported++; yield Outcome.FAILED; }
            case FAILED -> { summary.summaryFailed++; yield Outcome.FAILED; }
            case READY -> throw new IllegalStateException("视频摘要仍在预检阶段，不能作为最终结果");
        };
    }

    /** 转写与帧分别统计，文字失败不伪装为整件成功。 */
    private Outcome recordAudio(String executionId, long workId, VideoAudioTranscriptionService.Outcome outcome,
            MutableSummary summary, boolean framesSaved) {
        log.info("历史视频转写结果: executionId={}, workId={}, outcome={}", executionId, workId, outcome);
        return switch (outcome) {
            case SAVED -> { summary.audioSaved++; yield Outcome.SUCCESS; }
            case EMPTY -> { summary.audioEmpty++; yield Outcome.SUCCESS; }
            case ALREADY_COMPLETE -> { summary.audioSkipped++; yield framesSaved ? Outcome.SUCCESS : Outcome.SKIPPED; }
            case DISABLED -> { summary.audioDisabled++; yield framesSaved ? Outcome.SUCCESS : Outcome.SKIPPED; }
            case BUSY, STALE -> { summary.audioBusy++; yield Outcome.SKIPPED; }
            case BUDGET_EXHAUSTED -> { summary.audioBudgetDeferred++; yield Outcome.BUDGET; }
            case FAILED -> { summary.audioFailed++; yield Outcome.FAILED; }
            case READY -> throw new IllegalStateException("视频音频仍在预检阶段，不能作为最终结果");
        };
    }

    /** 两阶段均受整体截止时间限制，不把分阶段上限累加成无限重试。 */
    private Instant earlier(Instant left, Instant right) { return left.isBefore(right) ? left : right; }

    /** 已完成准入后只执行帧链路，独立处理提交未知和对象清理。 */
    private Outcome processFrames(String executionId, Work work, MutableSummary summary,
            VideoFrameBackfillLock.ExecutionLock executionLock, String queueId, boolean rebuild, boolean clearVideoSummary,
            VideoFrameSource source, Instant deadline) {
        Work target = work;
        String manifest = null;
        String rawPrefix = null;
        String stage = SOURCE_VERIFY;
        String jobId = null;
        int count = 0;
        boolean providerFinished = false;
        try {
            count = VideoFramePolicy.calculateFrameCount(work.durationMs());
            // 预算不足时绝不能清掉下一件作品；真正可能计费的额度仍在提交前才消耗。
            if (rebuild) {
                if (!summary.canReserve(count)) { return Outcome.BUDGET; }
                stage = EXECUTION_LOCK;
                executionLock.checkHeld();
                checkRemainingTime(deadline);
                stage = MANIFEST_CLEAR;
                target = clearVideoSummary ? repository.clearManifestForRebuild(work)
                        : repository.clearManifestForRebuild(work, false);
                if (target == null) {
                    throw new VideoFrameProcessingException(MANIFEST_CLEAR, "作品源、版本或清单已变化，本次未清空作品");
                }
                log.info("视频帧重建清单已清空: executionId={}, workId={}, sourceVersion={}, previousVersion={}, clearedVersion={}",
                        executionId, work.id(), source.sourceVersion(), work.version(), target.version());
                stage = EXECUTION_LOCK;
                executionLock.checkHeld();
                stage = FRAME_CLEANUP;
                storage.cleanupOwnedManifestStrict(work.manifest(), source, work.id(), deadline);
                log.info("视频帧重建旧对象已删除: executionId={}, workId={}", executionId, work.id());
            }
            String token = HISTORY_TOKEN + UUID.randomUUID();
            rawPrefix = storage.targetPrefix(source, work.id(), token) + RAW_DIRECTORY;
            stage = EXECUTION_LOCK;
            executionLock.checkHeld();
            checkRemainingTime(deadline);
            stage = FRAME_SUBMIT;
            if (!summary.reserve(count)) {
                log.error("历史视频补帧预算不足: executionId={}, workId={}, stage={}, chargedFrames={}, count={}, maxFrames={}",
                        executionId, work.id(), FRAME_SUBMIT, summary.chargedFrames, count, summary.maxFrames);
                return Outcome.BUDGET;
            }
            // 提交前计入本次预算；未知响应或后续失败不能退回已可能付费的额度。
            jobId = snapshots.submit(source, count, rawPrefix, queueId);
            stage = FRAME_QUERY;
            waitForJob(jobId, deadline, executionLock);
            providerFinished = true;
            stage = FRAME_LIST;
            List<VideoFrameInput> inputs = snapshots.outputFrames(rawPrefix, count);
            stage = FRAME_UPLOAD;
            manifest = storage.persist(source, work.id(), token, work.durationMs(), count, inputs, deadline);
            stage = SOURCE_VERIFY;
            storage.verifySource(source, deadline);
            stage = EXECUTION_LOCK;
            executionLock.checkHeld();
            stage = MANIFEST_SAVE;
            if (!repository.saveManifest(target, manifest)) {
                throw new VideoFrameProcessingException(MANIFEST_SAVE, "作品源、版本或清单已变化，本次结果不覆盖当前作品");
            }
            cleanupPreviousInvalidManifest(executionId, target, source, manifest);
            cleanupRawQuietly(executionId, work.id(), rawPrefix, count);
            logSuccess(executionId, work.id(), count);
            summary.frameSucceeded++;
            return Outcome.SUCCESS;
        } catch (Exception exception) {
            String failureStage = exception instanceof VideoFrameProcessingException processing
                    ? processing.getStage() : stage;
            if (manifest != null && source != null) {
                try {
                    if (repository.manifestPublished(work.id(), manifest)) {
                        cleanupPreviousInvalidManifest(executionId, target, source, manifest);
                        cleanupRawQuietly(executionId, work.id(), rawPrefix, count);
                        logSuccess(executionId, work.id(), count);
                        summary.frameSucceeded++;
                        return Outcome.SUCCESS;
                    }
                    storage.cleanupOwnedManifest(manifest, source, work.id());
                } catch (Exception publicationUnknown) {
                    // 数据库提交可能成功但响应丢失；不可读时保留对象，避免删掉已发布帧。
                    log.error("历史视频帧保存结果未知，保留本次对象: executionId={}, workId={}, stage={}, reason=数据库无法确认完整清单",
                            executionId, work.id(), MANIFEST_SAVE);
                    if (EXECUTION_LOCK.equals(failureStage)) { throw stopped(); }
                    return Outcome.FAILED;
                }
            }
            String reason = exception instanceof VideoFrameProcessingException processing
                    ? processing.getMessage() : FRAME_SUBMIT.equals(stage) && jobId == null
                    ? "截图提交结果未知，本次不重提，下次显式执行可能再次付费" : "历史帧处理失败";
            log.error("历史视频补帧失败: executionId={}, workId={}, stage={}, reason={}, exceptionType={}",
                    executionId, work.id(), failureStage, reason, exception.getClass().getSimpleName());
            // 未知响应先保留输出，定时终态扫尾从供应商清单恢复丢失的 JobId 后再安全删除。
            if (rawPrefix != null && jobId != null
                    && (providerFinished || exception instanceof VideoFrameSnapshotTerminalException)) {
                cleanupRawQuietly(executionId, work.id(), rawPrefix, count);
            }
            if (EXECUTION_LOCK.equals(failureStage) || Thread.currentThread().isInterrupted()) { throw stopped(); }
            return exception instanceof VideoFrameSourceChangedException || MANIFEST_SAVE.equals(failureStage)
                    || MANIFEST_CLEAR.equals(failureStage) ? Outcome.STALE : Outcome.FAILED;
        }
    }

    /** 丢锁必须上抛到扫描循环停止整次执行。 */
    private VideoFrameProcessingException stopped() {
        return new VideoFrameProcessingException(EXECUTION_LOCK, "历史补帧执行锁失效或线程中断，本次扫描停止");
    }

    /** 成功回写后，只有本次清单仍被当前作品引用才清理旧失效清单。 */
    private void cleanupPreviousInvalidManifest(String executionId, Work work, VideoFrameSource source, String manifest) {
        if (work.manifest() == null || work.manifest().isBlank()) { return; }
        try {
            if (!JSON.parseObject(work.manifest()).equals(JSON.parseObject(manifest))
                    && repository.manifestPublished(work.id(), manifest)) {
                storage.cleanupOwnedManifest(work.manifest(), source, work.id());
            }
        } catch (Exception exception) {
            log.warn("历史旧失效帧清理失败，保留对象: executionId={}, workId={}", executionId, work.id());
        }
    }

    /** 清理失败只记日志，不能改已保存的成功结果。 */
    private void cleanupRawQuietly(String executionId, long workId, String prefix, int count) {
        try { snapshots.cleanupOutput(prefix, count); }
        catch (Exception exception) {
            log.warn("历史截图输出清理失败: executionId={}, workId={}, stage={}", executionId, workId, FRAME_CLEANUP);
        }
    }

    /** 查询本次已知 JobId，超时或错误不重提；定时扫尾另行补偿已终态原始输出。 */
    private void waitForJob(String jobId, Instant deadline, VideoFrameBackfillLock.ExecutionLock executionLock) {
        while (true) {
            // 查询时也检查锁，避免失权后一直占用远端查询与单件期限。
            try { executionLock.checkHeld(); }
            catch (IllegalStateException exception) {
                throw new VideoFrameProcessingException(EXECUTION_LOCK, "执行锁已失效，本次扫描停止");
            }
            checkRemainingTime(deadline);
            if (snapshots.ready(jobId)) { return; }
            long sleepMillis = Math.min(Math.max(1, properties.getBackfillPollSeconds()) * 1000L,
                    Duration.between(Instant.now(), deadline).toMillis());
            if (sleepMillis <= 0) { throw new VideoFrameProcessingException(PROCESS_INTERRUPTED, "历史截图等待超时"); }
            try { Thread.sleep(sleepMillis); }
            catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                throw new VideoFrameProcessingException(PROCESS_INTERRUPTED, "历史截图等待被中断，本次不重提");
            }
        }
    }

    /** 清空引用、提交付费截图及远端查询前保留一次完整调用时间，超期或关闭中断立即停止当前作品。 */
    private void checkRemainingTime(Instant deadline) {
        if (Thread.currentThread().isInterrupted()
                || !Instant.now().plusSeconds(Math.max(1, properties.getRemoteTimeoutSeconds())).isBefore(deadline)) {
            throw new VideoFrameProcessingException(PROCESS_INTERRUPTED, "历史截图超过期限或线程中断，本次不重提");
        }
    }

    /** 每件成功日志可通过执行编号和作品编号定位。 */
    private void logSuccess(String executionId, long workId, int count) {
        log.info("历史视频补帧成功: executionId={}, workId={}, stage={}, frameCount={}",
                executionId, workId, MANIFEST_SAVE, count);
    }

    /** 只在本次串行线程内使用的统计与提交预算，失败不退回预算。 */
    private static final class MutableSummary {
        private final int maxFrames;
        private long scanned;
        private long succeeded;
        private long skipped;
        private long failed;
        private int chargedFrames;
        private boolean budgetExhausted;
        /** 帧与音频结果独立，避免部分成功掩盖补偿需求。 */
        private long frameSucceeded;
        private long audioSaved;
        private long audioEmpty;
        private long audioFailed;
        private long audioSkipped;
        private long audioBusy;
        private long audioBudgetDeferred;
        private long audioDisabled;
        /** 原视频摘要不借用截图或转写成功计数。 */
        private long summarySaved;
        private long summaryFailed;
        private long summarySkipped;
        private long summaryBusy;
        private long summaryBudgetDeferred;
        private long summaryUnsupported;
        private long summaryDisabled;
        private MutableSummary(int maxFrames) { this.maxFrames = maxFrames; }
        /** 提交前保守预留；差值比较避免加法溢出。 */
        private boolean reserve(int count) {
            if (!canReserve(count)) { return false; }
            chargedFrames += count;
            return true;
        }
        /** 破坏性重建前只检查剩余额度，不把尚未提交的截图记作可能已计费。 */
        private boolean canReserve(int count) { return count <= maxFrames - chargedFrames; }
        private Summary snapshot() {
            return new Summary(scanned, succeeded, skipped, failed, chargedFrames, budgetExhausted);
        }
    }
}
