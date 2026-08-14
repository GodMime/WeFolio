package com.jxc.wefolio.job.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.task.TaskExecutor;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 作品存储结算异步执行协调器，统一定时与手工触发的本机防重入及生命周期凭证。
 *
 * <p>手工触发只在正常接收状态下准入；调度触发必须位于已经获得准入的调度回调上下文，
 * 以便停用竞态中通过 continuation 凭证完成异步工作交接。</p>
 */
@Slf4j
@Service
public class WorkStorageBillingExecutionCoordinator {

    /** 执行器拒绝消息 */
    private static final String EXECUTOR_UNAVAILABLE_MESSAGE = "作品存储结算执行器暂不可用";

    /** 停用后拒绝手工提交的消息 */
    private static final String STOPPING_MESSAGE = "job 服务正在停用，不再接受作品存储结算任务";

    /** 调度来源未处于受保护回调上下文时的消息 */
    private static final String INVALID_SCHEDULED_CONTEXT_MESSAGE =
            "作品存储结算调度上下文无效，无法提交异步任务";

    /** 异步提交状态 */
    private static final String STATUS_ACCEPTED = "ACCEPTED";
    private static final String STATUS_ALREADY_RUNNING = "ALREADY_RUNNING";

    /** 结算批处理服务 */
    private final WorkStorageBillingService billingService;

    /** 专用异步执行器 */
    private final TaskExecutor taskExecutor;

    /** 后台任务统一执行生命周期 */
    private final JobExecutionLifecycle executionLifecycle;

    /** 当前执行状态 */
    private final AtomicReference<RunningExecution> running = new AtomicReference<>();

    /**
     * 创建协调器。
     *
     * @param billingService 结算服务
     * @param taskExecutor 专用执行器
     * @param executionLifecycle 后台任务统一执行生命周期
     */
    public WorkStorageBillingExecutionCoordinator(
            WorkStorageBillingService billingService,
            @Qualifier("workStorageBillingExecutor") TaskExecutor taskExecutor,
            JobExecutionLifecycle executionLifecycle
    ) {
        this.billingService = billingService;
        this.taskExecutor = taskExecutor;
        this.executionLifecycle = executionLifecycle;
    }

    /**
     * 提交异步结算。
     *
     * @param billingMonth 账期
     * @param source 触发来源
     * @return 提交结果
     */
    public Submission submit(LocalDate billingMonth, TriggerSource source) {
        JobExecutionLifecycle.ExecutionPermit permit = acquirePermit(source);
        RunningExecution candidate = new RunningExecution(
                UUID.randomUUID().toString(), billingMonth, source);
        while (!running.compareAndSet(null, candidate)) {
            RunningExecution current = running.get();
            if (current != null) {
                permit.close();
                return new Submission(false, current.executionId(), current.billingMonth(), STATUS_ALREADY_RUNNING);
            }
        }
        try {
            taskExecutor.execute(() -> execute(candidate, permit));
            return new Submission(true, candidate.executionId(), candidate.billingMonth(), STATUS_ACCEPTED);
        } catch (RuntimeException exception) {
            running.compareAndSet(candidate, null);
            permit.close();
            throw new WorkStorageBillingUnavailableException(EXECUTOR_UNAVAILABLE_MESSAGE, exception);
        }
    }

    /**
     * 按触发来源获取普通工作凭证或受调度上下文约束的延续凭证。
     *
     * @param source 任务触发来源
     * @return 覆盖实际异步结算执行周期的凭证
     * @throws WorkStorageBillingUnavailableException 生命周期不再接收工作或调度上下文无效
     */
    private JobExecutionLifecycle.ExecutionPermit acquirePermit(TriggerSource source) {
        if (source == TriggerSource.SCHEDULED) {
            return executionLifecycle.tryAcquireContinuation()
                    .orElseThrow(() -> new WorkStorageBillingUnavailableException(
                            INVALID_SCHEDULED_CONTEXT_MESSAGE));
        }
        return executionLifecycle.tryAcquire()
                .orElseThrow(() -> new WorkStorageBillingUnavailableException(STOPPING_MESSAGE));
    }

    /**
     * 执行实际结算并在任何结束路径释放生命周期凭证与本机运行态。
     *
     * @param execution 当前结算执行信息
     * @param permit 覆盖实际异步工作的生命周期凭证
     */
    private void execute(
            RunningExecution execution,
            JobExecutionLifecycle.ExecutionPermit permit
    ) {
        try (permit) {
            billingService.run(execution.billingMonth(), execution.executionId());
        } catch (RuntimeException exception) {
            log.error("作品存储月度结算执行失败: executionId={}, billingMonth={}, source={}",
                    execution.executionId(), execution.billingMonth(), execution.source(), exception);
        } finally {
            running.compareAndSet(execution, null);
        }
    }

    /** 触发来源。 */
    public enum TriggerSource {
        SCHEDULED,
        MANUAL
    }

    /**
     * 提交响应。
     *
     * @param accepted 是否接受本次请求
     * @param executionId 当前执行 ID
     * @param billingMonth 当前执行账期
     * @param status 执行状态
     */
    public record Submission(boolean accepted, String executionId, LocalDate billingMonth, String status) {
    }

    /** 当前运行任务。 */
    private record RunningExecution(String executionId, LocalDate billingMonth, TriggerSource source) {
    }
}
