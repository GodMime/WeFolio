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

    private final UserStorageFolderRepairService repairService;

    private final TaskExecutor taskExecutor;

    private final AtomicReference<RunningExecution> running = new AtomicReference<>();

    public UserStorageFolderRepairExecutionCoordinator(
            UserStorageFolderRepairService repairService,
            @Qualifier("userStorageFolderRepairExecutor") TaskExecutor taskExecutor
    ) {
        this.repairService = repairService;
        this.taskExecutor = taskExecutor;
    }

    /**
     * 提交一次无业务参数的异步修复。
     *
     * @return 提交结果
     */
    public Submission submit() {
        RunningExecution candidate = new RunningExecution(UUID.randomUUID().toString());
        while (!running.compareAndSet(null, candidate)) {
            RunningExecution current = running.get();
            if (current != null) {
                return new Submission(false, current.executionId(), STATUS_ALREADY_RUNNING);
            }
        }
        try {
            taskExecutor.execute(() -> execute(candidate));
            return new Submission(true, candidate.executionId(), STATUS_ACCEPTED);
        } catch (RuntimeException exception) {
            running.compareAndSet(candidate, null);
            throw new UserStorageFolderRepairUnavailableException(
                    EXECUTOR_UNAVAILABLE_MESSAGE, exception);
        }
    }

    private void execute(RunningExecution execution) {
        try {
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
