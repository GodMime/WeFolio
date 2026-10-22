package com.jxc.wefolio.job.service;

import com.fasterxml.jackson.databind.node.ObjectNode;
import com.jxc.wefolio.job.config.PortfolioAiProperties;
import com.jxc.wefolio.job.entity.AiGenerationTaskEntity;
import com.jxc.wefolio.job.repo.AiGenerationTaskRepository;
import com.jxc.wefolio.job.repo.PortfolioAiMediaRepository;
import com.jxc.wefolio.message.PortfolioAiTaskMessage;
import com.jxc.wefolio.service.portfolioai.AiPortfolioProtocol;
import com.jxc.wefolio.service.portfolioai.AiProtocolException;
import com.jxc.wefolio.service.portfolioai.StrictAiJson;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.Objects;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.Semaphore;

/** AI 短事务领取、有界执行及统一恢复；绝不保存作品集、生成字体或扣积分。 */
@Slf4j
@Service
public class PortfolioAiExecutionService {
    /** 与 Runtime 部署统一的业务时区。 */ private static final String BUSINESS_ZONE = "Asia/Shanghai";
    /** 任务条件更新仓储。 */ private final AiGenerationTaskRepository repository;
    /** 唯一官方模型客户端。 */ private final PortfolioAiModelClient modelClient;
    /** 启动配置。 */ private final PortfolioAiProperties properties;
    /** 统一部署排空入口。 */ private final JobExecutionLifecycle lifecycle;
    /** 业务时间来源，可替换以验证截止边界。 */ private final Clock clock;
    /** 固定工作线程池，不在内存维护无限候选队列。 */ private final ExecutorService executor;
    /** 领取前先获取并发额度，排队任务继续留在数据库。 */ private final Semaphore capacity;
    /** 附件并发受配置约束，至少为普通任务保留一个工作线程。 */
    private final Semaphore referenceCapacity;
    /** 关闭附件接单却存在等待附件时只告警一次，避免每轮刷屏。 */
    private boolean warnedDisabledReferences;
    /** 新媒体链路，旧冻结文字请求不经过该服务。 */ private final PortfolioAiMediaExecutionService mediaExecution;
    /** 摘要TTL清理，绝不删除持久帧。 */ private final PortfolioAiMediaRepository mediaRepository;
    /** 媒体执行续约独立于调度准入，排空期间仍保护当前实例活动代次。 */
    private final PortfolioAiMediaLeaseHeartbeat mediaHeartbeat;

    /** 创建有界工作线程及对应领取额度。 */
    @Autowired
    public PortfolioAiExecutionService(AiGenerationTaskRepository repository, PortfolioAiModelClient modelClient,
            PortfolioAiProperties properties, JobExecutionLifecycle lifecycle, PortfolioAiMediaExecutionService mediaExecution,
            PortfolioAiMediaRepository mediaRepository) {
        this(repository, modelClient, properties, lifecycle, Clock.system(ZoneId.of(BUSINESS_ZONE)),
                Executors.newFixedThreadPool(properties.getWorkerCount()), mediaExecution, mediaRepository);
    }

    /** 测试可注入媒体边界与统一时钟。 */
    PortfolioAiExecutionService(AiGenerationTaskRepository repository, PortfolioAiModelClient modelClient,
            PortfolioAiProperties properties, JobExecutionLifecycle lifecycle, Clock clock, ExecutorService executor,
            PortfolioAiMediaExecutionService mediaExecution, PortfolioAiMediaRepository mediaRepository) {
        this.repository = repository; this.modelClient = modelClient; this.properties = properties;
        this.lifecycle = lifecycle; this.clock = clock; this.executor = executor;
        this.capacity = new Semaphore(properties.getWorkerCount());
        this.referenceCapacity = new Semaphore(Math.max(0, Math.min(properties.getReferenceMaxActiveTasks(), properties.getWorkerCount() - 1)));
        // 文字测试也注入明确替身，禁止在启用媒体后才以 NPE 暴露不完整装配。
        this.mediaExecution = Objects.requireNonNull(mediaExecution, "媒体执行器不能为空");
        this.mediaRepository = Objects.requireNonNull(mediaRepository, "媒体仓储不能为空");
        this.mediaHeartbeat = new PortfolioAiMediaLeaseHeartbeat(repository, properties);
    }

    /** 超时与中断恢复不受 AI 接单开关影响。 */
    public void maintain() {
        LocalDateTime now = now();
        for (AiGenerationTaskEntity task : repository.findRecoverable(now, properties.getTotalTimeoutSeconds(),
                properties.getRecoverySeconds(), properties.getBatchSize())) {
            if (expired(task, now)) { repository.fail(task, "TIMEOUT", PortfolioAiTaskMessage.TASK_TIMEOUT_MESSAGE, now); }
            else if (requiresMediaExecution(task)) {
                // 失去心跳的媒体只结束当前任务，不能重新发起已可能计费的调用；SQL 重验租约竞争。
                repository.failInterruptedMedia(task, now, properties.getRecoverySeconds(), properties.getTotalTimeoutSeconds());
            }
            else if (task.getAttemptCount() >= properties.getMaxAttempts()) {
                repository.fail(task, "ATTEMPTS_EXHAUSTED", "执行次数已用尽，现有内容已保留", now);
            } else { repository.recover(task, now, properties.getTotalTimeoutSeconds()); }
        }
    }

    /** 仅开关启用时领取；每条任务均在真实网络调用前完成 CAS。 */
    public void dispatch() {
        if (!properties.isEnabled()) { return; }
        int limit = Math.min(capacity.availablePermits(), properties.getBatchSize());
        if (limit < 1) { return; }
        warnReferenceSwitchMismatch();
        boolean referencesAvailable = properties.isReferenceEnabled() && referenceCapacity.availablePermits() > 0;
        dispatchTasks(referencesAvailable ? repository.findPending(now(), limit)
                : repository.findPendingWithoutReferences(now(), limit));
        if (referencesAvailable && referenceCapacity.availablePermits() == 0 && capacity.availablePermits() > 0) {
            dispatchTasks(repository.findPendingWithoutReferences(now(), Math.min(capacity.availablePermits(), properties.getBatchSize())));
        }
    }

    /** 已被 Runtime 接受的附件在本机暂停领取时记录部署差异，恢复接单后允许下次再次告警。 */
    private void warnReferenceSwitchMismatch() {
        if (properties.isReferenceEnabled()) { warnedDisabledReferences = false; return; }
        boolean pending = repository.hasPendingReferences(now());
        if (pending && !warnedDisabledReferences) {
            log.warn("高级 AI 存在待执行附件，但 Job 附件接单已关闭，配置项=WEFOLIO_AI_REFERENCE_ENABLED");
        }
        warnedDisabledReferences = pending;
    }

    /** 在领取前占用附件槽位，整个真实执行完成后才归还。 */
    private void dispatchTasks(java.util.List<AiGenerationTaskEntity> tasks) {
        for (AiGenerationTaskEntity task : tasks) {
            if (!capacity.tryAcquire()) { return; }
            var permit = lifecycle.tryAcquire();
            if (permit.isEmpty()) { capacity.release(); return; }
            boolean handedOff = false;
            boolean referencePermit = false;
            try {
                if (PortfolioAiReferenceContract.hasReferences(task)) {
                    if (!properties.isReferenceEnabled()) { continue; }
                    referencePermit = referenceCapacity.tryAcquire();
                    if (!referencePermit) { continue; }
                }
                LocalDateTime now = now();
                if (expired(task, now)) { repository.fail(task, "TIMEOUT", PortfolioAiTaskMessage.TASK_TIMEOUT_MESSAGE, now); continue; }
                if (task.getAttemptCount() >= properties.getMaxAttempts()) {
                    repository.fail(task, "ATTEMPTS_EXHAUSTED", "执行次数已用尽，现有内容已保留", now); continue;
                }
                ObjectNode request = request(task);
                AiPortfolioProtocol.checkVersion(request);
                PortfolioAiModelContract.validate(request, properties);
                PortfolioAiModelContract.warnMediaSwitchMismatch(task, request, properties);
                if (!repository.claim(task, now, properties.getMaxAttempts(), properties.getTotalTimeoutSeconds())) { continue; }
                boolean releaseReference = referencePermit;
                executor.execute(() -> {
                    try (var ignored = permit.orElseThrow()) { executeClaimed(task, request); }
                    finally { capacity.release(); if (releaseReference) { referenceCapacity.release(); } }
                });
                handedOff = true;
            } catch (AiProtocolException exception) {
                repository.fail(task, exception.getCode(), exception.getMessage(), now());
            } catch (RejectedExecutionException exception) {
                repository.fail(task, "EXECUTION_INTERRUPTED", "执行器暂时不可用，现有内容已保留", now());
            } finally {
                if (!handedOff) { permit.orElseThrow().close(); capacity.release(); if (referencePermit) { referenceCapacity.release(); } }
            }
        }
    }

    /** 执行当前代次，合法结果只写任务中间协议，晚到结果由 CAS 丢弃。 */
    void executeClaimed(AiGenerationTaskEntity task, ObjectNode request) {
        PortfolioAiMediaLeaseHeartbeat.Lease lease = null;
        try {
            log.info("高级 AI 开始执行 taskId={}, requestedByUniqueCode={}, requestedBy={}, ownerType={}, ownerId={}, attempt={}",
                    task.getId(), task.getRequestedByUniqueCode(), task.getRequestedBy(), task.getOwnerType(), task.getOwnerId(), task.getAttemptCount());
            Duration remaining = Duration.between(now(), task.getCreatedAt().plusSeconds(properties.getTotalTimeoutSeconds()));
            if (remaining.isNegative() || remaining.isZero()) { failure(task, "TIMEOUT", PortfolioAiTaskMessage.TASK_TIMEOUT_MESSAGE, false); return; }
            PortfolioAiModelContract.validate(request, properties);
            String raw;
            if (requiresMediaExecution(task)) {
                lease = mediaHeartbeat.track(task);
                raw = mediaExecution.generate(task, request, lease::checkHeld);
                lease.checkHeld();
            } else { raw = modelClient.generate(task, request, remaining); }
            ObjectNode output = AiPortfolioProtocol.validate(request, raw);
            PortfolioAiTitlePolicy.validate(output);
            ObjectNode result = StrictAiJson.mapper().createObjectNode();
            result.put("kind", "NEEDS_CLARIFICATION".equals(output.path("status").asText()) ? "CLARIFICATION" : "CANDIDATE");
            result.set("output", output);
            LocalDateTime now = now();
            boolean succeeded = lease == null
                    ? repository.succeed(task, result.toString(), now, properties.getTotalTimeoutSeconds())
                    : repository.succeedMedia(task, result.toString(), now, properties.getTotalTimeoutSeconds(), properties.getRecoverySeconds());
            log.info("高级 AI 结果回写 taskId={}, requestedByUniqueCode={}, attempt={}, applied={}",
                    task.getId(), task.getRequestedByUniqueCode(), task.getAttemptCount(), succeeded);
            if (!succeeded && expired(task, now)) {
                repository.fail(task, "TIMEOUT", PortfolioAiTaskMessage.TASK_TIMEOUT_MESSAGE, now);
            }
        } catch (AiProtocolException exception) {
            if (!failLostLease(task, lease)) {
                failure(task, exception.getCode(), exception.getMessage(), !"CONTRACT_UNAVAILABLE".equals(exception.getCode()));
            }
        } catch (PortfolioAiCallException exception) {
            if (!failLostLease(task, lease)) { failure(task, exception.getCode(), exception.getMessage(), exception.isRetryable()); }
        } catch (RuntimeException exception) {
            // 不记录堆栈或 exception.message，避免第三方异常包含原文、地址或密钥。
            log.error("高级 AI 内部执行失败 taskId={}, requestedByUniqueCode={}, attempt={}, exceptionType={}",
                    task.getId(), task.getRequestedByUniqueCode(), task.getAttemptCount(), exception.getClass().getSimpleName());
            if (!failLostLease(task, lease)) { failure(task, "INTERNAL_ERROR", PortfolioAiTaskMessage.TASK_FAILED_MESSAGE, false); }
        } finally { if (lease != null) { lease.close(); } }
    }

    /** 已失权媒体不会因晚到的可重试异常回队列；终态与费用记录仍各自遵循原 CAS。 */
    private boolean failLostLease(AiGenerationTaskEntity task, PortfolioAiMediaLeaseHeartbeat.Lease lease) {
        if (lease == null || !lease.lostOwnership()) { return false; }
        failure(task, PortfolioAiMediaLeaseHeartbeat.INTERRUPTED_CODE, PortfolioAiMediaLeaseHeartbeat.INTERRUPTED_MESSAGE, false);
        return true;
    }

    /** 统一错误预算；重试与终态都匹配当前 RUNNING/version。 */
    private void failure(AiGenerationTaskEntity task, String code, String message, boolean retryable) {
        LocalDateTime now = now();
        boolean media = requiresMediaExecution(task);
        log.warn("高级 AI 执行失败 taskId={}, requestedByUniqueCode={}, attempt={}, errorCode={}, retryable={}, elapsedMs={}, remainingMs={}, maxAttempts={}",
                task.getId(), task.getRequestedByUniqueCode(), task.getAttemptCount(), code, retryable, Duration.between(task.getCreatedAt(), now).toMillis(),
                Math.max(0, Duration.between(now, task.getCreatedAt().plusSeconds(properties.getTotalTimeoutSeconds())).toMillis()),
                properties.getMaxAttempts());
        if (expired(task, now)) { repository.fail(task, "TIMEOUT", PortfolioAiTaskMessage.TASK_TIMEOUT_MESSAGE, now); }
        else if (retryable && !(media && PortfolioAiMediaLeaseHeartbeat.INTERRUPTED_CODE.equals(code))
                && task.getAttemptCount() < properties.getMaxAttempts()) {
            boolean retried = media
                    ? repository.retryMedia(task, code, message, now, properties.getTotalTimeoutSeconds(), properties.getRecoverySeconds())
                    : repository.retry(task, code, message, now, properties.getTotalTimeoutSeconds());
            log.info("高级 AI 重试入队 taskId={}, requestedByUniqueCode={}, attempt={}, errorCode={}, applied={}",
                    task.getId(), task.getRequestedByUniqueCode(), task.getAttemptCount(), code, retried);
            if (!retried && expired(task, now())) {
                repository.fail(task, "TIMEOUT", PortfolioAiTaskMessage.TASK_TIMEOUT_MESSAGE, now());
            }
        } else { repository.fail(task, code, message, now); }
    }

    /** 小批清理到期终态，运行开关关闭时继续执行。 */
    public int cleanup() {
        LocalDateTime cutoff = now().minusDays(properties.getRetentionDays()); int total = 0;
        mediaRepository.cleanup(now(), properties.getBatchSize());
        for (int i = 0; i < properties.getMaxCleanupBatches(); i++) {
            int deleted = repository.deleteExpired(cutoff, properties.getBatchSize()); total += deleted;
            if (deleted < properties.getBatchSize()) { break; }
        }
        return total;
    }

    /** 解析受控冻结请求；请求中的完整配置预算已由 Runtime 检查。 */
    private ObjectNode request(AiGenerationTaskEntity task) {
        return StrictAiJson.parseObject(task.getRequestJson(), StrictAiJson.RESPONSE_MAX_BYTES, StrictAiJson.MAX_DEPTH + 3);
    }

    /** 只认服务端媒体计划及独立附件列，原文字里出现同名词不改变恢复规则。 */
    private boolean requiresMediaExecution(AiGenerationTaskEntity task) {
        if (PortfolioAiReferenceContract.hasReferences(task)) { return true; }
        try { return request(task).path("facts").has("mediaPlan"); }
        catch (AiProtocolException exception) { return false; }
    }

    /** 根据创建时间解释不可延期的总截止。 */
    private boolean expired(AiGenerationTaskEntity task, LocalDateTime now) {
        return !now.isBefore(task.getCreatedAt().plusSeconds(properties.getTotalTimeoutSeconds()));
    }

    /** 所有扫描和执行共享上海业务时间，注入时钟只决定瞬间，不改变 DATETIME 口径。 */
    private LocalDateTime now() { return LocalDateTime.now(clock.withZone(ZoneId.of(BUSINESS_ZONE))); }

    /** 停机取消尚未结束的工作，之后由数据库恢复规则处理，不重置次数。 */
    @PreDestroy
    public void close() { executor.shutdownNow(); mediaHeartbeat.close(); }
}
