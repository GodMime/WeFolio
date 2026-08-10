package com.jxc.wefolio.job.task;

import com.jxc.wefolio.job.config.VirtualPaymentDispatchProperties;
import com.jxc.wefolio.job.config.VirtualPaymentSchedulingConfig;
import com.jxc.wefolio.job.service.VirtualPaymentDebitDispatchSummary;
import com.jxc.wefolio.job.service.VirtualPaymentDispatchService;
import com.jxc.wefolio.job.service.VirtualPaymentTaskDispatchSummary;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.TimeUnit;

/**
 * 微信虚拟支付扣币任务候选分发调度器。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class PointDebitTaskDispatchJob {

    /** 固定延迟配置表达式。 */
    private static final String FIXED_DELAY =
            "${virtual-payment-dispatch.debit-scan-interval:30s}";

    /** 结构化日志中的调度操作名称。 */
    private static final String DISPATCH_OPERATION = "待扣任务候选分发";

    /** 当前 job 实例是否已有一轮扣币分发在执行。 */
    private final AtomicBoolean running = new AtomicBoolean(false);

    /** 虚拟支付候选分发服务。 */
    private final VirtualPaymentDispatchService dispatchService;

    /** 虚拟支付分发配置。 */
    private final VirtualPaymentDispatchProperties properties;

    /** 上一轮结束固定延迟后恢复并分发扣币任务候选。 */
    @Scheduled(
            fixedDelayString = FIXED_DELAY,
            scheduler = VirtualPaymentSchedulingConfig.TASK_SCHEDULER_BEAN_NAME)
    public void execute() {
        log.info("微信虚拟支付调度 operation={} result=STARTED enabled={}",
                DISPATCH_OPERATION, properties.isEnabled());
        if (!properties.isEnabled()) {
            log.info("微信虚拟支付调度 operation={} result=SKIPPED_DISABLED", DISPATCH_OPERATION);
            return;
        }
        if (!running.compareAndSet(false, true)) {
            log.info("微信虚拟支付调度 operation={} result=SKIPPED_RUNNING", DISPATCH_OPERATION);
            return;
        }
        long startNanos = System.nanoTime();
        try {
            VirtualPaymentDebitDispatchSummary summary = dispatchService.dispatchDebitTasks();
            VirtualPaymentTaskDispatchSummary taskSummary = summary.taskSummary();
            log.info("微信虚拟支付调度 operation={} result=COMPLETED recoveryUserCount={} "
                            + "recoveryRequestCount={} recoveryFailedCount={} candidateCount={} "
                            + "requestCount={} processedCount={} skippedCount={} failedCount={} durationMs={}",
                    DISPATCH_OPERATION,
                    summary.recoveryUserCount(),
                    summary.recoveryRequestCount(),
                    summary.recoveryFailedCount(),
                    taskSummary.candidateCount(),
                    taskSummary.requestCount(),
                    taskSummary.processedCount(),
                    taskSummary.skippedCount(),
                    taskSummary.failedCount(),
                    elapsedMillis(startNanos));
        } catch (RuntimeException exception) {
            log.info("微信虚拟支付调度 operation={} result=FAILED durationMs={} exceptionType={}",
                    DISPATCH_OPERATION, elapsedMillis(startNanos), exception.getClass().getSimpleName());
            throw exception;
        } finally {
            running.set(false);
        }
    }

    /** 计算本轮调度已耗费毫秒数。 */
    private long elapsedMillis(long startNanos) {
        return TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startNanos);
    }
}
