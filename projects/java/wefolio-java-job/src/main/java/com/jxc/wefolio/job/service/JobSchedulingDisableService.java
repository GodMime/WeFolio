package com.jxc.wefolio.job.service;

import com.jxc.wefolio.job.config.JobSchedulingProperties;
import com.jxc.wefolio.job.dto.JobSchedulingDisableResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.concurrent.TimeUnit;

/**
 * 发布旧服务前停用后台调度并等待活动工作排空的应用服务。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class JobSchedulingDisableService {

    /** 运维密钥无效时的固定响应消息。 */
    private static final String INVALID_SECRET_MESSAGE = "job 运维密钥无效";

    /** 已完成停用和排空时的固定响应消息。 */
    private static final String DISABLED_MESSAGE = "定时调度任务已停用，运行中任务已结束";

    /** 等待活动工作排空超时时的固定响应消息。 */
    private static final String TIMEOUT_MESSAGE = "定时调度任务已停止接收新任务，但仍有运行中任务未结束";

    /** 停用等待线程被中断时的固定响应消息。 */
    private static final String INTERRUPTED_MESSAGE = "定时调度任务停用等待被中断";

    /** 复用现有运维密钥的校验器。 */
    private final AdminPointSecretValidator secretValidator;

    /** 统一控制后台工作的准入和排空状态。 */
    private final JobExecutionLifecycle executionLifecycle;

    /** 提供停用接口的有界等待配置。 */
    private final JobSchedulingProperties schedulingProperties;

    /**
     * 校验运维密钥，停止后续调度，并有界等待已准入任务自然结束。
     *
     * @param requestedSecret 请求携带的运维密钥
     * @return Controller 可直接映射的停用结果
     */
    public ExecutionResult disable(String requestedSecret) {
        if (!secretValidator.isValid(requestedSecret)) {
            return ExecutionResult.unauthorized(INVALID_SECRET_MESSAGE);
        }

        long startedNanos = System.nanoTime();
        executionLifecycle.pause(schedulingProperties.getPauseDuration());

        try {
            if (executionLifecycle.awaitDisabled(schedulingProperties.getDisableTimeout())) {
                ExecutionResult completedResult = result(ExecutionOutcome.DISABLED, DISABLED_MESSAGE);
                log.info("停用定时调度完成 status={} activeTaskCount={} durationMs={}",
                        completedResult.data().getStatus(), completedResult.data().getActiveTaskCount(),
                        elapsedMillis(startedNanos));
                return completedResult;
            }
            ExecutionResult timeoutResult = result(ExecutionOutcome.TIMEOUT, TIMEOUT_MESSAGE);
            log.warn("停用定时调度等待超时 status={} activeTaskCount={} durationMs={}",
                    timeoutResult.data().getStatus(), timeoutResult.data().getActiveTaskCount(),
                    elapsedMillis(startedNanos));
            return timeoutResult;
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            log.warn("停用定时调度等待被中断 durationMs={}", elapsedMillis(startedNanos));
            return result(ExecutionOutcome.UNAVAILABLE, INTERRUPTED_MESSAGE);
        }
    }

    /**
     * 使用单次生命周期快照构造一致的应用结果。
     *
     * @param outcome 结果类型
     * @param message 固定响应消息
     * @return 携带当前状态、活动数和等待秒数的结果
     */
    private ExecutionResult result(ExecutionOutcome outcome, String message) {
        JobExecutionLifecycle.ExecutionSnapshot snapshot = executionLifecycle.snapshot();
        JobSchedulingDisableResponse data = new JobSchedulingDisableResponse(
                snapshot.status().name(),
                snapshot.activeTaskCount(),
                timeoutSeconds());
        return new ExecutionResult(outcome, message, data);
    }

    /**
     * 把正数 Duration 向上取整为对运维响应无歧义的秒数。
     *
     * @return 至少为一的配置等待秒数
     */
    private long timeoutSeconds() {
        long wholeSeconds = schedulingProperties.getDisableTimeout().getSeconds();
        if (schedulingProperties.getDisableTimeout().getNano() == 0
                || wholeSeconds == Long.MAX_VALUE) {
            return wholeSeconds;
        }
        return wholeSeconds + 1;
    }

    /**
     * 计算停用请求从指定起点到当前时刻的耗时。
     *
     * @param startedNanos 单调时钟起点
     * @return 已经过的毫秒数
     */
    private long elapsedMillis(long startedNanos) {
        return TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedNanos);
    }

    /**
     * 停用用例结果类型。
     */
    public enum ExecutionOutcome {
        /** 已成功停用并排空。 */
        DISABLED,
        /** 运维密钥无效。 */
        UNAUTHORIZED,
        /** 等待活动工作超时。 */
        TIMEOUT,
        /** 等待过程暂不可用。 */
        UNAVAILABLE
    }

    /**
     * 停用应用结果。
     *
     * @param outcome 结果类型
     * @param message 响应消息
     * @param data 生命周期状态，未授权时为空
     */
    public record ExecutionResult(
            ExecutionOutcome outcome,
            String message,
            JobSchedulingDisableResponse data
    ) {
        /**
         * 创建不携带生命周期数据的未授权结果。
         *
         * @param message 响应消息
         * @return 未授权结果
         */
        public static ExecutionResult unauthorized(String message) {
            return new ExecutionResult(ExecutionOutcome.UNAUTHORIZED, message, null);
        }
    }
}
