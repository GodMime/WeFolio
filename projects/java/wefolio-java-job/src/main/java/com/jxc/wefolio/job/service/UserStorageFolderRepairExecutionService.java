package com.jxc.wefolio.job.service;

import com.jxc.wefolio.job.dto.UserStorageFolderRepairExecutionResponse;
import com.jxc.wefolio.job.service.UserStorageFolderRepairExecutionCoordinator.Submission;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/**
 * 历史用户目录修复运维接口应用服务。
 */
@Service
@RequiredArgsConstructor
public class UserStorageFolderRepairExecutionService {

    /** 固定响应消息 */
    private static final String INVALID_SECRET_MESSAGE = "job 运维密钥无效";
    private static final String ACCEPTED_MESSAGE = "历史用户目录修复任务已提交";
    private static final String ALREADY_RUNNING_MESSAGE = "历史用户目录修复任务正在执行，本次未重复提交";

    private final AdminPointSecretValidator secretValidator;

    private final UserStorageFolderRepairExecutionCoordinator coordinator;

    /**
     * 校验密钥并提交无参异步修复。
     *
     * @param secret 运维密钥
     * @return 应用结果
     */
    public ExecutionResult execute(String secret) {
        if (!secretValidator.isValid(secret)) {
            return ExecutionResult.failure(ExecutionOutcome.UNAUTHORIZED, INVALID_SECRET_MESSAGE);
        }
        try {
            Submission submission = coordinator.submit();
            UserStorageFolderRepairExecutionResponse data =
                    new UserStorageFolderRepairExecutionResponse(
                            submission.accepted(),
                            submission.executionId(),
                            submission.status());
            if (submission.accepted()) {
                return new ExecutionResult(ExecutionOutcome.ACCEPTED, ACCEPTED_MESSAGE, data);
            }
            return new ExecutionResult(
                    ExecutionOutcome.ALREADY_RUNNING, ALREADY_RUNNING_MESSAGE, data);
        } catch (UserStorageFolderRepairUnavailableException exception) {
            return ExecutionResult.failure(ExecutionOutcome.UNAVAILABLE, exception.getMessage());
        }
    }

    /** Controller 可直接映射的结果类型。 */
    public enum ExecutionOutcome {
        ACCEPTED,
        ALREADY_RUNNING,
        UNAUTHORIZED,
        UNAVAILABLE
    }

    /**
     * 运维接口应用结果。
     *
     * @param outcome 结果类型
     * @param message 响应消息
     * @param data 异步提交数据
     */
    public record ExecutionResult(
            ExecutionOutcome outcome,
            String message,
            UserStorageFolderRepairExecutionResponse data
    ) {
        public static ExecutionResult failure(ExecutionOutcome outcome, String message) {
            return new ExecutionResult(outcome, message, null);
        }
    }
}
