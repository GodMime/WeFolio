package com.jxc.wefolio.job.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.task.TaskExecutor;
import org.springframework.stereotype.Service;

import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 历史用户目录修复异步执行协调器。
 */
@Slf4j
@Service
public class UserStorageFolderRepairExecutionCoordinator {

    /** 执行状态 */
    private static final String STATUS_ACCEPTED = "ACCEPTED";
    private static final String STATUS_ALREADY_RUNNING = "ALREADY_RUNNING";

    /** 执行器不可用消息 */
    private static final String EXECUTOR_UNAVAILABLE_MESSAGE = "历史用户目录修复执行器暂不可用";

    /** 停用后拒绝新提交的消息 */
    private static final String STOPPING_MESSAGE = "job 服务正在停用，不再接受历史用户目录修复任务";

    private final UserStorageFolderRepairService repairService;

    private final TaskExecutor taskExecutor;

    /** 后台任务统一执行生命周期。 */
    private final JobExecutionLifecycle executionLifecycle;

    private final AtomicReference<RunningExecution> running = new AtomicReference<>();

    /**
     * 创建历史用户目录修复异步执行协调器。
     *
     * @param repairService 目录修复服务
     * @param taskExecutor 专用异步执行器
     * @param executionLifecycle 后台任务统一执行生命周期
     */
    public UserStorageFolderRepairExecutionCoordinator(
            UserStorageFolderRepairService repairService,
            @Qualifier("userStorageFolderRepairExecutor") TaskExecutor taskExecutor,
            JobExecutionLifecycle executionLifecycle
    ) {
        this.repairService = repairService;
        this.taskExecutor = taskExecutor;
        this.executionLifecycle = executionLifecycle;
    }

    /**
     * 提交一次无业务参数的异步修复。
     *
     * @return 提交结果
     */
    public Submission submit() {
        JobExecutionLifecycle.ExecutionPermit permit = executionLifecycle.tryAcquire()
                .orElseThrow(() -> new UserStorageFolderRepairUnavailableException(STOPPING_MESSAGE));
        RunningExecution candidate = new RunningExecution(UUID.randomUUID().toString());
        while (!running.compareAndSet(null, candidate)) {
            RunningExecution current = running.get();
            if (current != null) {
                permit.close();
                return new Submission(false, current.executionId(), STATUS_ALREADY_RUNNING);
            }
        }
        try {
            taskExecutor.execute(() -> execute(candidate, permit));
            return new Submission(true, candidate.executionId(), STATUS_ACCEPTED);
        } catch (RuntimeException exception) {
            running.compareAndSet(candidate, null);
            permit.close();
            throw new UserStorageFolderRepairUnavailableException(
                    EXECUTOR_UNAVAILABLE_MESSAGE, exception);
        }
    }

    /**
     * 执行实际目录修复并在任何结束路径释放生命周期凭证与本机运行态。
     *
     * @param execution 当前修复执行信息
     * @param permit 覆盖实际异步工作的生命周期凭证
     */
    private void execute(
            RunningExecution execution,
            JobExecutionLifecycle.ExecutionPermit permit
    ) {
        try (permit) {
            repairService.run(execution.executionId());
        } catch (RuntimeException exception) {
            log.error("历史用户 COS 目录修复执行失败: executionId={}",
                    execution.executionId(), exception);
        } finally {
            running.compareAndSet(execution, null);
        }
    }

    /**
     * 提交响应。
     *
     * @param accepted 是否接受
     * @param executionId 当前执行 ID
     * @param status 执行状态
     */
    public record Submission(boolean accepted, String executionId, String status) {
    }

    /** 当前运行任务。 */
    private record RunningExecution(String executionId) {
    }
}
