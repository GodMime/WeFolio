package com.jxc.wefolio.job.service;

import com.fasterxml.jackson.databind.node.ObjectNode;
import com.jxc.wefolio.job.config.PortfolioAiProperties;
import com.jxc.wefolio.job.entity.AiGenerationTaskEntity;
import com.jxc.wefolio.job.repo.AiGenerationTaskRepository;
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

    /** 创建有界工作线程及对应领取额度。 */
    @Autowired
    public PortfolioAiExecutionService(AiGenerationTaskRepository repository, PortfolioAiModelClient modelClient,
            PortfolioAiProperties properties, JobExecutionLifecycle lifecycle) {
        this(repository, modelClient, properties, lifecycle, Clock.system(ZoneId.of(BUSINESS_ZONE)),
                Executors.newFixedThreadPool(properties.getWorkerCount()));
    }

    /** 测试可传入确定性执行器。 */
    PortfolioAiExecutionService(AiGenerationTaskRepository repository, PortfolioAiModelClient modelClient,
            PortfolioAiProperties properties, JobExecutionLifecycle lifecycle, Clock clock, ExecutorService executor) {
        this.repository = repository; this.modelClient = modelClient; this.properties = properties;
        this.lifecycle = lifecycle; this.clock = clock; this.executor = executor;
        this.capacity = new Semaphore(properties.getWorkerCount());
    }

    /** 超时与中断恢复不受 AI 接单开关影响。 */
    public void maintain() {
        LocalDateTime now = now();
        for (AiGenerationTaskEntity task : repository.findRecoverable(now, properties.getTotalTimeoutSeconds(),
                properties.getRecoverySeconds(), properties.getBatchSize())) {
            if (expired(task, now)) { repository.fail(task, "TIMEOUT", PortfolioAiTaskMessage.TASK_TIMEOUT_MESSAGE, now); }
            else if (task.getAttemptCount() >= properties.getMaxAttempts()) {
                repository.fail(task, "ATTEMPTS_EXHAUSTED", "执行次数已用尽，现有内容已保留", now);
            } else { repository.recover(task, now, properties.getTotalTimeoutSeconds()); }
        }
    }

    /** 仅开关启用时领取；每条任务均在真实网络调用前完成 CAS。 */
    public void dispatch() {
        if (!properties.isEnabled()) { return; }
        for (AiGenerationTaskEntity task : repository.findPending(now(), Math.min(capacity.availablePermits(), properties.getBatchSize()))) {
            if (!capacity.tryAcquire()) { return; }
            var permit = lifecycle.tryAcquire();
            if (permit.isEmpty()) { capacity.release(); return; }
            boolean handedOff = false;
            try {
                LocalDateTime now = now();
                if (expired(task, now)) { repository.fail(task, "TIMEOUT", PortfolioAiTaskMessage.TASK_TIMEOUT_MESSAGE, now); continue; }
                if (task.getAttemptCount() >= properties.getMaxAttempts()) {
                    repository.fail(task, "ATTEMPTS_EXHAUSTED", "执行次数已用尽，现有内容已保留", now); continue;
                }
                ObjectNode request = request(task);
                AiPortfolioProtocol.checkVersion(request);
                PortfolioAiModelContract.validate(request, properties);
                if (!repository.claim(task, now, properties.getMaxAttempts(), properties.getTotalTimeoutSeconds())) { continue; }
                executor.execute(() -> {
                    try (var ignored = permit.orElseThrow()) { executeClaimed(task, request); }
                    finally { capacity.release(); }
                });
                handedOff = true;
            } catch (AiProtocolException exception) {
                repository.fail(task, exception.getCode(), exception.getMessage(), now());
            } catch (RejectedExecutionException exception) {
                repository.fail(task, "EXECUTION_INTERRUPTED", "执行器暂时不可用，现有内容已保留", now());
            } finally {
                if (!handedOff) { permit.orElseThrow().close(); capacity.release(); }
            }
        }
    }

    /** 执行当前代次，合法结果只写任务中间协议，晚到结果由 CAS 丢弃。 */
    void executeClaimed(AiGenerationTaskEntity task, ObjectNode request) {
        try {
            Duration remaining = Duration.between(now(), task.getCreatedAt().plusSeconds(properties.getTotalTimeoutSeconds()));
            if (remaining.isNegative() || remaining.isZero()) { failure(task, "TIMEOUT", PortfolioAiTaskMessage.TASK_TIMEOUT_MESSAGE, false); return; }
            PortfolioAiModelContract.validate(request, properties);
            String raw = modelClient.generate(task, request, remaining);
            ObjectNode output = AiPortfolioProtocol.validate(request, raw);
            ObjectNode result = StrictAiJson.mapper().createObjectNode();
            result.put("kind", "NEEDS_CLARIFICATION".equals(output.path("status").asText()) ? "CLARIFICATION" : "CANDIDATE");
            result.set("output", output);
            LocalDateTime now = now();
            if (!repository.succeed(task, result.toString(), now, properties.getTotalTimeoutSeconds()) && expired(task, now)) {
                repository.fail(task, "TIMEOUT", PortfolioAiTaskMessage.TASK_TIMEOUT_MESSAGE, now);
            }
        } catch (AiProtocolException exception) {
            failure(task, exception.getCode(), exception.getMessage(), !"CONTRACT_UNAVAILABLE".equals(exception.getCode()));
        } catch (PortfolioAiCallException exception) {
            failure(task, exception.getCode(), exception.getMessage(), exception.isRetryable());
        } catch (RuntimeException exception) {
            // 不记录堆栈或 exception.message，避免第三方异常包含原文、地址或密钥。
            log.error("高级 AI 内部执行失败 taskId={}, attempt={}, exceptionType={}", task.getId(), task.getAttemptCount(), exception.getClass().getSimpleName());
            failure(task, "INTERNAL_ERROR", PortfolioAiTaskMessage.TASK_FAILED_MESSAGE, false);
        }
    }

    /** 统一错误预算；重试与终态都匹配当前 RUNNING/version。 */
    private void failure(AiGenerationTaskEntity task, String code, String message, boolean retryable) {
        LocalDateTime now = now();
        if (expired(task, now)) { repository.fail(task, "TIMEOUT", PortfolioAiTaskMessage.TASK_TIMEOUT_MESSAGE, now); }
        else if (retryable && task.getAttemptCount() < properties.getMaxAttempts()) {
            if (!repository.retry(task, code, message, now, properties.getTotalTimeoutSeconds()) && expired(task, now())) {
                repository.fail(task, "TIMEOUT", PortfolioAiTaskMessage.TASK_TIMEOUT_MESSAGE, now());
            }
        } else { repository.fail(task, code, message, now); }
    }

    /** 小批清理到期终态，运行开关关闭时继续执行。 */
    public int cleanup() {
        LocalDateTime cutoff = now().minusDays(properties.getRetentionDays()); int total = 0;
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

    /** 根据创建时间解释不可延期的总截止。 */
    private boolean expired(AiGenerationTaskEntity task, LocalDateTime now) {
        return !now.isBefore(task.getCreatedAt().plusSeconds(properties.getTotalTimeoutSeconds()));
    }

    /** 所有扫描和执行共享业务时间。 */
    private LocalDateTime now() { return LocalDateTime.now(clock); }

    /** 停机取消尚未结束的工作，之后由数据库恢复规则处理，不重置次数。 */
    @PreDestroy
    public void close() { executor.shutdownNow(); }
}
