package com.jxc.wefolio.job.service;

import com.jxc.wefolio.job.config.VideoFrameConfig;
import com.jxc.wefolio.job.config.VideoFrameProperties;
import com.jxc.wefolio.job.model.VideoFrameBackfillModels.Request;
import com.jxc.wefolio.job.model.VideoFrameBackfillModels.Submission;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.task.TaskExecutor;
import org.springframework.stereotype.Service;
import org.springframework.scheduling.annotation.Scheduled;

import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/** 无任务表的运维一次性补帧入口：认证、跨实例锁、异步执行与排空生命周期。 */
@Slf4j
@Service
public class VideoFrameBackfillExecutionService {
    /** Controller 只按应用结果映射 HTTP。 */
    public enum Outcome { ACCEPTED, EXISTING, UNAUTHORIZED, INVALID, UNAVAILABLE }
    /** 固定安全提示与本机运行状态。 */
    private static final String INVALID_SECRET = "job 运维密钥无效";
    private static final String UNAVAILABLE_MESSAGE = "历史视频补帧暂不可用，请检查队列配置或任务服务状态";
    private static final String UNSUPPORTED_RESUME = "历史视频补帧为一次性扫描，不支持 runId 续跑；请不传 runId 重新执行，已完成作品会自动跳过";
    private static final String RUNNING = "RUNNING";
    private static final String ALREADY_RUNNING_MESSAGE = "历史视频补帧正在执行，本次未重复提交";
    /** 清扫只占用共享资源，不代表运营提交的补帧已被接受。 */
    private static final String CLEANUP_BUSY_MESSAGE = "历史截图清扫正在执行，本次补帧未接受，请清扫结束后重新提交";
    /** 跨实例会话锁不携带操作类型，未知占用不能被推断为已有补帧。 */
    private static final String REMOTE_BUSY_MESSAGE = "历史视频补帧或截图清扫正在占用执行锁，本次补帧未接受，请稍后重新提交";
    /** 准入可能在取锁、队列检查或派发时失败，不能提前提供已接受编号。 */
    private static final String ADMISSION_BUSY_MESSAGE = "历史视频补帧正在检查提交条件，本次补帧未接受，请稍后重新提交";
    /** 本机提交或锁操作中断的固定阶段。 */
    private static final String PROCESS_INTERRUPTED = "PROCESS_INTERRUPTED";
    /** 定时补偿清理既有终态输出及过期原始截图，不提交截图；沿用专用单线程执行器。 */
    private static final long CLEANUP_INTERVAL_MILLIS = 60_000L;
    private static final String CLEANUP_EXECUTION_PREFIX = "cleanup-";
    /** 沿用现有运维密钥校验。 */
    private final AdminPointSecretValidator secrets;
    /** 单次历史扫描业务。 */
    private final VideoFrameBackfillService service;
    /** 接受执行前解析并冻结本次真实截图队列。 */
    private final VideoFrameSnapshotClient snapshots;
    /** 扫描、远端边界和单次截图预算。 */
    private final VideoFrameProperties properties;
    /** 停用排空控制覆盖全部异步工作。 */
    private final JobExecutionLifecycle lifecycle;
    /** MySQL 会话锁保护跨实例单飞，不维护新表。 */
    private final VideoFrameBackfillLock executionLocks;
    /** 专用单线程、零排队执行器。 */
    private final TaskExecutor executor;
    /** 本机操作类型与编号一起原子发布，避免把清扫误报为已有补帧。 */
    private final AtomicReference<LocalExecution> running = new AtomicReference<>();

    /** 两种操作共用执行器和跨实例锁，但只有补帧可以被判定为重复提交。 */
    private enum ExecutionKind { BACKFILL, CLEANUP }

    /** 当前操作身份在准入和执行期间保持不变，已派发标记单独原子发布。 */
    private record LocalExecution(String executionId, ExecutionKind kind, AtomicBoolean accepted) {
        /** 只有执行器确实接受后才可以把补帧报告为已存在。 */
        private LocalExecution(String executionId, ExecutionKind kind) {
            this(executionId, kind, new AtomicBoolean());
        }
    }

    /** 注入既有认证、生命周期和专用历史执行器。 */
    public VideoFrameBackfillExecutionService(AdminPointSecretValidator secrets,
            VideoFrameBackfillService service, VideoFrameSnapshotClient snapshots, VideoFrameProperties properties,
            JobExecutionLifecycle lifecycle, VideoFrameBackfillLock executionLocks,
            @Qualifier(VideoFrameConfig.BACKFILL_EXECUTOR_NAME) TaskExecutor executor) {
        this.secrets = secrets;
        this.service = service;
        this.snapshots = snapshots;
        this.properties = properties;
        this.lifecycle = lifecycle;
        this.executionLocks = executionLocks;
        this.executor = executor;
    }

    /** 接受前取得跨实例执行锁；executionId 只用于关联本次成功、跳过和失败日志。 */
    public Result execute(String secret, Request request) {
        if (!secrets.isValid(secret)) { return new Result(Outcome.UNAUTHORIZED, INVALID_SECRET, null); }
        if (request != null && request.runId() != null) { return new Result(Outcome.INVALID, UNSUPPORTED_RESUME, null); }
        int maxFrames = request == null || request.maxFrames() == null
                ? properties.getBackfillMaxFrames() : request.maxFrames();
        if (maxFrames < VideoFramePolicy.MIN_COUNT || maxFrames > properties.getBackfillMaxFrames()) {
            return new Result(Outcome.INVALID, "历史补帧截图预算非法", null);
        }
        JobExecutionLifecycle.ExecutionPermit permit = lifecycle.tryAcquire().orElse(null);
        if (permit == null) { return new Result(Outcome.UNAVAILABLE, UNAVAILABLE_MESSAGE, null); }
        String executionId = UUID.randomUUID().toString();
        LocalExecution local = new LocalExecution(executionId, ExecutionKind.BACKFILL);
        LocalExecution current = running.compareAndExchange(null, local);
        if (current != null) {
            permit.close();
            if (current.kind() == ExecutionKind.CLEANUP) {
                return new Result(Outcome.UNAVAILABLE, CLEANUP_BUSY_MESSAGE, null);
            }
            return current.accepted().get() ? existing(current.executionId())
                    : new Result(Outcome.UNAVAILABLE, ADMISSION_BUSY_MESSAGE, null);
        }
        VideoFrameBackfillLock.ExecutionLock acquired = null;
        try {
            acquired = executionLocks.tryAcquire().orElse(null);
            if (acquired == null) {
                running.compareAndSet(local, null);
                permit.close();
                // 其他实例可能仅在清扫，不能把未知锁持有者推断为已接受的补帧。
                return new Result(Outcome.UNAVAILABLE, REMOTE_BUSY_MESSAGE, null);
            }
            acquired.checkHeld();
            String queueId = snapshots.resolveQueueId();
            acquired.checkHeld();
            VideoFrameBackfillLock.ExecutionLock owned = acquired;
            executor.execute(() -> run(local, maxFrames, permit, owned, queueId));
            local.accepted().set(true);
            log.info("历史视频补帧执行已接受: executionId={}, maxFrames={}", executionId, maxFrames);
            return new Result(Outcome.ACCEPTED, "历史视频补帧执行已提交，结果请查看执行日志",
                    new Submission(true, null, RUNNING, executionId));
        } catch (RuntimeException exception) {
            if (acquired != null) {
                try { acquired.close(); }
                catch (RuntimeException releaseFailure) {
                    log.error("历史视频补帧提交失败后释放锁异常: executionId={}, exceptionType={}",
                            executionId, releaseFailure.getClass().getSimpleName());
                }
            }
            running.compareAndSet(local, null);
            permit.close();
            String stage = exception instanceof VideoFrameProcessingException processing
                    ? processing.getStage() : PROCESS_INTERRUPTED;
            String message = exception instanceof VideoFrameProcessingException
                    ? exception.getMessage() : UNAVAILABLE_MESSAGE;
            log.error("历史视频补帧执行未接受: executionId={}, stage={}, reason={}, exceptionType={}",
                    executionId, stage, message, exception.getClass().getSimpleName());
            return new Result(Outcome.UNAVAILABLE, message, null);
        }
    }

    /** 无需再次触发补帧，为终态原始输出持续扫尾，并回收供应商历史过期后的旧原始截图。 */
    @Scheduled(fixedDelay = CLEANUP_INTERVAL_MILLIS, initialDelay = CLEANUP_INTERVAL_MILLIS)
    public void cleanupFinishedSnapshots() {
        JobExecutionLifecycle.ExecutionPermit permit = lifecycle.tryAcquire().orElse(null);
        if (permit == null) { return; }
        String executionId = CLEANUP_EXECUTION_PREFIX + UUID.randomUUID();
        LocalExecution local = new LocalExecution(executionId, ExecutionKind.CLEANUP);
        if (!running.compareAndSet(null, local)) { permit.close(); return; }
        VideoFrameBackfillLock.ExecutionLock acquired = null;
        try {
            acquired = executionLocks.tryAcquire().orElse(null);
            if (acquired == null) { running.compareAndSet(local, null); permit.close(); return; }
            VideoFrameBackfillLock.ExecutionLock owned = acquired;
            executor.execute(() -> {
                try (permit; owned) {
                    try { snapshots.cleanupCompletedOutputs(owned); }
                    catch (RuntimeException exception) {
                        log.warn("历史 Snapshot 终态补偿失败，继续年龄兜底: executionId={}, exceptionType={}",
                                executionId, exception.getClass().getSimpleName());
                    }
                    snapshots.cleanupExpiredOutputs(owned);
                }
                catch (RuntimeException exception) {
                    log.warn("历史 Snapshot 定时补偿失败，保留输出: executionId={}, exceptionType={}",
                            executionId, exception.getClass().getSimpleName());
                } finally { running.compareAndSet(local, null); }
            });
        } catch (RuntimeException exception) {
            try { if (acquired != null) { acquired.close(); } }
            catch (RuntimeException releaseFailure) {
                log.warn("历史 Snapshot 补偿锁释放失败: exceptionType={}", releaseFailure.getClass().getSimpleName());
            } finally {
                running.compareAndSet(local, null);
                permit.close();
            }
            log.warn("历史 Snapshot 定时补偿未执行: executionId={}, exceptionType={}",
                    executionId, exception.getClass().getSimpleName());
        }
    }

    /** 全异步执行结束才释放跨实例锁和排空凭证。 */
    private void run(LocalExecution local, int maxFrames, JobExecutionLifecycle.ExecutionPermit permit,
            VideoFrameBackfillLock.ExecutionLock acquired, String queueId) {
        String executionId = local.executionId();
        try (permit; acquired) {
            service.run(executionId, maxFrames, acquired, queueId);
        } catch (RuntimeException exception) {
            log.error("历史视频补帧异步执行异常: executionId={}, stage=PROCESS_INTERRUPTED, exceptionType={}",
                    executionId, exception.getClass().getSimpleName());
        } finally {
            running.compareAndSet(local, null);
        }
    }

    /** 执行中只反馈本机已知编号；runId 保持为空。 */
    private Result existing(String executionId) {
        return new Result(Outcome.EXISTING, ALREADY_RUNNING_MESSAGE, new Submission(false, null, RUNNING, executionId));
    }

    /** 运维入口应用结果。 */
    public record Result(Outcome outcome, String message, Submission data) { }
}
