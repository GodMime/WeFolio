package com.jxc.wefolio.job.service;

import com.jxc.wefolio.job.config.VirtualPaymentDispatchProperties;
import com.jxc.wefolio.job.repo.VirtualPaymentCandidateRepository;
import com.jxc.wefolio.job.repo.VirtualPaymentCandidateRepository.RecoveryCandidate;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Set;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.FutureTask;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.BiFunction;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * 微信虚拟支付候选任务有界并发分发服务。
 */
@Slf4j
@Service
public class VirtualPaymentDispatchService {

    /** 赠送订单日志任务类型。 */
    private static final String GIFT_ORDER_TASK_TYPE = "赠送订单";

    /** 扣币任务恢复日志任务类型。 */
    private static final String DEBIT_TASK_RECOVERY_TASK_TYPE = "扣币任务恢复";

    /** 退款余额恢复日志任务类型。 */
    private static final String REFUND_BALANCE_RECOVERY_TASK_TYPE = "退款余额恢复";

    /** 充值支付和退款核对日志任务类型。 */
    private static final String RECHARGE_RECONCILIATION_TASK_TYPE = "充值订单核对";

    /** 扣币任务日志任务类型。 */
    private static final String DEBIT_TASK_TYPE = "扣币任务";

    /** runtime 已执行或已持久化下次核对时间的结果，处理完成不等于资金结算成功。 */
    private static final Set<String> PROCESSED_OUTCOMES =
            Set.of("PROCESSED", "SUCCEEDED", "RETRY_WAIT", "WAITING_SESSION");

    /** 某类恢复查询异常时的可检索事件，原有活动扣币任务仍继续分发。 */
    private static final String RECOVERY_SCAN_FAILURE_EVENT = "VIRTUAL_PAYMENT_RECOVERY_SCAN_FAILED";

    /** 候选任务只读仓储。 */
    private final VirtualPaymentCandidateRepository candidateRepository;

    /** runtime 单任务客户端。 */
    private final RuntimeVirtualPaymentTaskClient runtimeTaskClient;

    /** 分发配置。 */
    private final VirtualPaymentDispatchProperties properties;

    /** 分发专用固定线程池。 */
    private final ExecutorService executor;

    /** 尚未由调度轮次回收的分发任务。 */
    private final Set<TrackedFutureTask> trackedTasks = ConcurrentHashMap.newKeySet();

    /** 分发服务是否继续接收新任务。 */
    private final AtomicBoolean accepting = new AtomicBoolean(true);

    /** 接单与关闭操作的互斥监视器。 */
    private final Object lifecycleMonitor = new Object();

    /** 两类恢复各自保存扫描位置，远端失败也不会把下一轮固定在最小账户 ID。 */
    private final AtomicLong missingDebitAccountCursor = new AtomicLong();

    /** 退款恢复扫描位置；到达尾部后回绕，进程重启可安全从头开始。 */
    private final AtomicLong refundAccountCursor = new AtomicLong();

    /** 充值核对独立订单游标，连接失败等未写回退避的订单也不会阻塞后续候选。 */
    private final AtomicLong rechargeOrderCursor = new AtomicLong();

    /** 创建配置固定工作线程数的分发服务。 */
    @Autowired
    public VirtualPaymentDispatchService(
            VirtualPaymentCandidateRepository candidateRepository,
            RuntimeVirtualPaymentTaskClient runtimeTaskClient,
            VirtualPaymentDispatchProperties properties
    ) {
        this(candidateRepository, runtimeTaskClient, properties,
                Executors.newFixedThreadPool(properties.getWorkerCount()));
    }

    /** 使用指定线程池构造分发服务，供并发契约测试使用。 */
    VirtualPaymentDispatchService(
            VirtualPaymentCandidateRepository candidateRepository,
            RuntimeVirtualPaymentTaskClient runtimeTaskClient,
            VirtualPaymentDispatchProperties properties,
            ExecutorService executor
    ) {
        this.candidateRepository = candidateRepository;
        this.runtimeTaskClient = runtimeTaskClient;
        this.properties = properties;
        this.executor = executor;
    }

    /** 发现并逐条分发赠送订单候选。 */
    public VirtualPaymentTaskDispatchSummary dispatchGiftOrders() {
        List<Long> orderIds = candidateRepository.findDueGiftOrderIds(properties.getBatchSize());
        DispatchCounters counters = dispatch(
                orderIds,
                orderId -> runtimeTaskClient.executeGiftOrder(orderId).getOutcome(),
                GIFT_ORDER_TASK_TYPE);
        return counters.toTaskSummary(orderIds.size());
    }

    /** 独立额度核对充值和退款、恢复账户，再分发现有扣币任务。 */
    public VirtualPaymentDebitDispatchSummary dispatchDebitTasks() {
        List<Long> rechargeOrderIds = scanRecoveryCandidates(
                RECHARGE_RECONCILIATION_TASK_TYPE, this::rotatingRechargeCandidates);
        DispatchCounters rechargeCounters = dispatch(rechargeOrderIds,
                orderId -> runtimeTaskClient.reconcileRechargeOrder(orderId).getOutcome(),
                RECHARGE_RECONCILIATION_TASK_TYPE);
        logDispatchSummary(RECHARGE_RECONCILIATION_TASK_TYPE,
                rechargeCounters.toTaskSummary(rechargeOrderIds.size()));
        List<Long> userIds = scanRecoveryCandidates(DEBIT_TASK_RECOVERY_TASK_TYPE, () -> rotatingRecoveryCandidates(
                candidateRepository::findAccountsMissingActiveDebitTasks, missingDebitAccountCursor));
        DispatchCounters recoveryCounters = dispatch(
                userIds,
                userId -> runtimeTaskClient.recoverDebitTask(userId).getOutcome(),
                DEBIT_TASK_RECOVERY_TASK_TYPE);
        Set<Long> recoveredUserIds = Set.copyOf(userIds);
        List<Long> refundUserIds = scanRecoveryCandidates(REFUND_BALANCE_RECOVERY_TASK_TYPE,
                () -> rotatingRecoveryCandidates(candidateRepository::findRefundStaleAccounts, refundAccountCursor)).stream()
                .filter(userId -> !recoveredUserIds.contains(userId))
                .toList();
        DispatchCounters refundCounters = dispatch(
                refundUserIds,
                userId -> runtimeTaskClient.recoverDebitTask(userId).getOutcome(),
                REFUND_BALANCE_RECOVERY_TASK_TYPE);
        logDispatchSummary(DEBIT_TASK_RECOVERY_TASK_TYPE, recoveryCounters.toTaskSummary(userIds.size()));
        logDispatchSummary(REFUND_BALANCE_RECOVERY_TASK_TYPE, refundCounters.toTaskSummary(refundUserIds.size()));
        List<Long> taskIds = candidateRepository.findDueDebitTaskIds(properties.getBatchSize());
        DispatchCounters taskCounters = dispatch(
                taskIds,
                taskId -> runtimeTaskClient.executeDebitTask(taskId).getOutcome(),
                DEBIT_TASK_TYPE);
        return new VirtualPaymentDebitDispatchSummary(
                userIds.size() + refundUserIds.size(),
                recoveryCounters.requestCount.get() + refundCounters.requestCount.get(),
                recoveryCounters.failedCount.get() + refundCounters.failedCount.get(),
                taskCounters.toTaskSummary(taskIds.size()));
    }

    /** 隔离迁移缺列、数据库读取等候选查询异常，让其余恢复类别与活动任务仍能分发。 */
    private List<Long> scanRecoveryCandidates(String taskType, Supplier<List<Long>> query) {
        try {
            return query.get();
        } catch (RuntimeException exception) {
            log.error("event={} taskType={} exceptionType={}",
                    RECOVERY_SCAN_FAILURE_EVENT, taskType, exception.getClass().getSimpleName());
            return List.of();
        }
    }

    /** 充值核对独立轮转，持久退避由 runtime 的 next_query_at 字段控制。 */
    private List<Long> rotatingRechargeCandidates() {
        long afterOrderId = rechargeOrderCursor.get();
        List<Long> candidates = candidateRepository.findDueRechargeOrderIds(afterOrderId, properties.getBatchSize());
        if (candidates.isEmpty() && afterOrderId != 0L) {
            candidates = candidateRepository.findDueRechargeOrderIds(0L, properties.getBatchSize());
        }
        rechargeOrderCursor.set(candidates.isEmpty() ? 0L : candidates.getLast());
        return candidates;
    }

    /** 分别输出各类恢复结果，避免总计掩盖某一类候选持续失败。 */
    private void logDispatchSummary(String taskType, VirtualPaymentTaskDispatchSummary summary) {
        log.info("微信虚拟支付恢复分发 taskType={} candidateCount={} requestCount={} processedCount={} "
                        + "skippedCount={} failedCount={}", taskType, summary.candidateCount(), summary.requestCount(),
                summary.processedCount(), summary.skippedCount(), summary.failedCount());
    }

    /** 每类读取一批；扫描尾部为空时回绕一次，推进游标不依赖远端请求是否成功。 */
    private List<Long> rotatingRecoveryCandidates(
            BiFunction<Long, Integer, List<RecoveryCandidate>> query, AtomicLong cursor
    ) {
        long afterAccountId = cursor.get();
        List<RecoveryCandidate> candidates = query.apply(afterAccountId, properties.getBatchSize());
        if (candidates.isEmpty() && afterAccountId != 0L) {
            candidates = query.apply(0L, properties.getBatchSize());
        }
        cursor.set(candidates.isEmpty() ? 0L : candidates.getLast().accountId());
        return candidates.stream().map(RecoveryCandidate::userId).toList();
    }

    /** 并发执行一批 ID，单条失败只记录且不在同轮重试。 */
    private DispatchCounters dispatch(List<Long> ids, Function<Long, String> action, String taskType) {
        DispatchCounters counters = new DispatchCounters();
        List<TrackedFutureTask> futures = ids.stream()
                .map(id -> submit(id, action, taskType, counters))
                .filter(task -> task != null)
                .toList();
        for (TrackedFutureTask future : futures) {
            await(future);
        }
        return counters;
    }

    /** 在服务仍接单时提交一条任务，并让关闭流程能够识别排队状态。 */
    private TrackedFutureTask submit(
            Long id,
            Function<Long, String> action,
            String taskType,
            DispatchCounters counters
    ) {
        synchronized (lifecycleMonitor) {
            if (!accepting.get()) {
                return null;
            }
            TrackedFutureTask task = new TrackedFutureTask(
                    () -> executeOne(id, action, taskType, counters));
            trackedTasks.add(task);
            try {
                executor.execute(task);
                return task;
            } catch (RejectedExecutionException exception) {
                trackedTasks.remove(task);
                if (accepting.get()) {
                    throw exception;
                }
                return null;
            }
        }
    }

    /** 等待单条任务结束，关闭时被取消的排队任务视为正常收敛。 */
    private void await(TrackedFutureTask future) {
        try {
            future.get();
        } catch (CancellationException exception) {
            log.debug("微信虚拟支付排队分发任务已在关闭期间取消");
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            log.info("微信虚拟支付分发等待被中断");
        } catch (ExecutionException exception) {
            log.warn("微信虚拟支付分发任务发生未捕获异常 exceptionType={}",
                    exception.getCause() == null
                            ? exception.getClass().getSimpleName()
                            : exception.getCause().getClass().getSimpleName());
        }
    }

    /** 执行单条 runtime 请求并隔离异常。 */
    private void executeOne(
            Long id,
            Function<Long, String> action,
            String taskType,
            DispatchCounters counters
    ) {
        counters.requestCount.incrementAndGet();
        try {
            String outcome = action.apply(id);
            if (PROCESSED_OUTCOMES.contains(outcome)) {
                counters.processedCount.incrementAndGet();
            } else {
                counters.skippedCount.incrementAndGet();
            }
        } catch (RuntimeException exception) {
            counters.failedCount.incrementAndGet();
            log.warn("微信虚拟支付分发失败 taskType={} targetId={} exceptionType={}",
                    taskType, id, exception.getClass().getSimpleName());
        }
    }

    /** 应用关闭时停止接单、取消排队任务，并有界等待活动 HTTP 请求自然结束。 */
    @PreDestroy
    public void shutdown() {
        List<TrackedFutureTask> tasks;
        synchronized (lifecycleMonitor) {
            if (!accepting.compareAndSet(true, false)) {
                return;
            }
            executor.shutdown();
            tasks = List.copyOf(trackedTasks);
        }
        tasks.forEach(TrackedFutureTask::cancelIfQueued);
        long graceMillis = properties.getRequestTimeout().multipliedBy(2L)
                .plusSeconds(1L).toMillis();
        try {
            if (!executor.awaitTermination(graceMillis, TimeUnit.MILLISECONDS)) {
                log.warn("微信虚拟支付分发线程池未在停机宽限期内结束 graceMillis={}", graceMillis);
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            log.info("等待微信虚拟支付分发线程池关闭时被中断");
        }
    }

    /** 可区分尚在队列与已经开始执行状态的 FutureTask。 */
    private final class TrackedFutureTask extends FutureTask<Void> {

        /** 是否已经由工作线程开始执行。 */
        private final AtomicBoolean started = new AtomicBoolean(false);

        /** 创建无返回值分发任务。 */
        private TrackedFutureTask(Runnable runnable) {
            super(runnable, null);
        }

        /** 工作线程取出任务时先标记开始，再执行实际请求。 */
        @Override
        public void run() {
            started.set(true);
            super.run();
        }

        /** 仅在任务真实完成或取消时从生命周期跟踪集合移除。 */
        @Override
        protected void done() {
            trackedTasks.remove(this);
        }

        /** 仅取消尚未被工作线程取出的排队任务。 */
        private void cancelIfQueued() {
            if (!started.get()) {
                cancel(false);
            }
        }
    }

    /** 并发安全累加一轮分发结果。 */
    private static final class DispatchCounters {

        /** 实际 runtime 请求数。 */
        private final AtomicInteger requestCount = new AtomicInteger();

        /** runtime 已处理数。 */
        private final AtomicInteger processedCount = new AtomicInteger();

        /** runtime 跳过数。 */
        private final AtomicInteger skippedCount = new AtomicInteger();

        /** 请求失败数。 */
        private final AtomicInteger failedCount = new AtomicInteger();

        /** 生成不可变任务汇总。 */
        private VirtualPaymentTaskDispatchSummary toTaskSummary(int candidateCount) {
            return new VirtualPaymentTaskDispatchSummary(
                    candidateCount,
                    requestCount.get(),
                    processedCount.get(),
                    skippedCount.get(),
                    failedCount.get());
        }
    }
}
