package com.jxc.wefolio.job.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.task.TaskExecutor;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 作品存储结算异步执行协调器，统一定时与手工触发的本机防重入状态。
 */
@Slf4j
@Service
public class WorkStorageBillingExecutionCoordinator {

    /** 执行器拒绝消息 */
    private static final String EXECUTOR_UNAVAILABLE_MESSAGE = "作品存储结算执行器暂不可用";

    /** 异步提交状态 */
    private static final String STATUS_ACCEPTED = "ACCEPTED";
    private static final String STATUS_ALREADY_RUNNING = "ALREADY_RUNNING";

    /** 结算批处理服务 */
    private final WorkStorageBillingService billingService;

    /** 专用异步执行器 */
    private final TaskExecutor taskExecutor;

    /** 当前执行状态 */
    private final AtomicReference<RunningExecution> running = new AtomicReference<>();

    /**
     * 创建协调器。
     *
     * @param billingService 结算服务
     * @param taskExecutor 专用执行器
     */
    public WorkStorageBillingExecutionCoordinator(
            WorkStorageBillingService billingService,
            @Qualifier("workStorageBillingExecutor") TaskExecutor taskExecutor
    ) {
        this.billingService = billingService;
        this.taskExecutor = taskExecutor;
    }

    /**
     * 提交异步结算。
     *
     * @param billingMonth 账期
     * @param source 触发来源
     * @return 提交结果
     */
    public Submission submit(LocalDate billingMonth, TriggerSource source) {
        RunningExecution candidate = new RunningExecution(
                UUID.randomUUID().toString(), billingMonth, source);
        while (!running.compareAndSet(null, candidate)) {
            RunningExecution current = running.get();
            if (current != null) {
                return new Submission(false, current.executionId(), current.billingMonth(), STATUS_ALREADY_RUNNING);
            }
        }
        try {
            taskExecutor.execute(() -> execute(candidate));
            return new Submission(true, candidate.executionId(), candidate.billingMonth(), STATUS_ACCEPTED);
        } catch (RuntimeException exception) {
            running.compareAndSet(candidate, null);
            throw new WorkStorageBillingUnavailableException(EXECUTOR_UNAVAILABLE_MESSAGE, exception);
        }
    }

    private void execute(RunningExecution execution) {
        try {
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
