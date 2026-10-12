package com.jxc.wefolio.job.service;

import com.jxc.wefolio.job.config.PortfolioAiProperties;
import com.jxc.wefolio.job.entity.AiGenerationTaskEntity;
import com.jxc.wefolio.job.repo.AiGenerationTaskRepository;
import lombok.extern.slf4j.Slf4j;

import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

/** 仅为正在执行的媒体代次续约，独立于调度准入，部署排空期间仍保护活动 worker。 */
@Slf4j
final class PortfolioAiMediaLeaseHeartbeat implements AutoCloseable {
    /** 租约丢失沿用既有错误码及前端可识别的失败状态。 */
    static final String INTERRUPTED_CODE = "EXECUTION_INTERRUPTED";
    /** 固定安全文案，不推断崩溃前未落库的真实阶段。 */
    static final String INTERRUPTED_MESSAGE = "媒体执行中断，现有内容已保留，请重新发起";
    /** 最大心跳间隔，同时随恢复窗口缩短。 */
    private static final int MAX_HEARTBEAT_SECONDS = 10;
    /** 保留足够恢复窗口，避免低恢复配置下两次心跳就超时。 */
    private static final int HEARTBEATS_PER_WINDOW = 3;
    /** 独立线程名称，不使用部署准入装饰器。 */
    private static final String THREAD_NAME = "portfolio-ai-media-heartbeat";
    /** 任务租约的短 CAS 边界。 */
    private final AiGenerationTaskRepository repository;
    /** 与执行和恢复共用的配置。 */
    private final PortfolioAiProperties properties;
    /** 单线程独立续约；注册数量由已有 workerCount 上限约束。 */
    private final ScheduledExecutorService scheduler;
    /** 停机使所有既有凭证失效，不能只停止未来回调而放行旧 worker。 */
    private volatile boolean stopped;

    /** 每实例只创建一个续约线程，不随媒体数量创建线程。 */
    PortfolioAiMediaLeaseHeartbeat(AiGenerationTaskRepository repository, PortfolioAiProperties properties) {
        this(repository, properties, Executors.newSingleThreadScheduledExecutor(callback -> {
            Thread thread = new Thread(callback, THREAD_NAME); thread.setDaemon(true); return thread;
        }));
    }

    /** 测试可注入调度器，精确验证排空、竞争及停止续约。 */
    PortfolioAiMediaLeaseHeartbeat(AiGenerationTaskRepository repository, PortfolioAiProperties properties,
            ScheduledExecutorService scheduler) {
        this.repository = repository; this.properties = properties; this.scheduler = scheduler;
    }

    /** 在媒体执行开始时同步确认所有权，执行结束必须关闭凭证。 */
    Lease track(AiGenerationTaskEntity task) {
        Lease lease = new Lease(task); lease.checkHeld();
        int interval = Math.max(1, Math.min(MAX_HEARTBEAT_SECONDS, properties.getRecoverySeconds() / HEARTBEATS_PER_WINDOW));
        lease.future = scheduler.scheduleAtFixedRate(lease::renewSafely, interval, interval, TimeUnit.SECONDS);
        return lease;
    }

    /** 停机结束独立心跳，执行器中的凭证仍由各自 finally 幂等关闭。 */
    @Override public void close() { stopped = true; scheduler.shutdownNow(); }

    /** 固定代次凭证；续约失权后永远不重新领取，阶段检查阻止后续付费。 */
    final class Lease implements AutoCloseable {
        /** 本轮已经领取的任务与固定业务版本。 */
        private final AiGenerationTaskEntity task;
        /** 当前续约计划，结束后取消。 */
        private ScheduledFuture<?> future;
        /** 凭证是否已经随当前执行结束。 */
        private boolean closed;
        /** 首次失权即永久失效，不因随后数据库恢复而复活。 */
        private boolean lost;

        /** 只允许成功领取的执行入口登记。 */
        private Lease(AiGenerationTaskEntity task) { this.task = task; }

        /** 阶段和付费前同步确认当前租约，数据库异常保守停止本次执行。 */
        synchronized void checkHeld() {
            if (closed || lost || stopped || Thread.currentThread().isInterrupted()) { lost = true; throw interrupted(); }
            try {
                if (!renew()) { throw interrupted(); }
            } catch (PortfolioAiCallException exception) { throw exception; }
            catch (RuntimeException exception) {
                lost = true;
                log.warn("媒体阶段租约检查失败 taskId={}, attempt={}, exceptionType={}",
                        task.getId(), task.getAttemptCount(), exception.getClass().getSimpleName());
                throw interrupted();
            }
        }

        /** 短暂数据库故障不推断进程已死，下次续约仍须通过数据库未过租约条件。 */
        private synchronized void renewSafely() {
            if (closed || lost || stopped) { return; }
            try { renew(); }
            catch (RuntimeException exception) {
                log.warn("媒体心跳续约失败 taskId={}, attempt={}, exceptionType={}",
                        task.getId(), task.getAttemptCount(), exception.getClass().getSimpleName());
            }
        }

        /** 续约只能作用于仍有效的代次，超时、取消、回收和旧快照均不能复活。 */
        private boolean renew() {
            boolean owned = repository.renewMediaLease(task, properties.getRecoverySeconds(), properties.getTotalTimeoutSeconds());
            if (!owned) { lost = true; if (future != null) { future.cancel(false); } }
            return owned;
        }

        /** 执行异常发生后重新确认，失权不能被普通网络错误转换为自动重试。 */
        synchronized boolean lostOwnership() {
            try { checkHeld(); return false; }
            catch (PortfolioAiCallException exception) { return true; }
        }

        /** 成功、失败及取消全部停止该代次续约，不能延长已结束任务的寿命。 */
        @Override public synchronized void close() {
            if (closed) { return; } closed = true;
            if (future != null) { future.cancel(false); }
        }
    }

    /** 租约丢失绝不自动重试，未知费用保留在原任务。 */
    private static PortfolioAiCallException interrupted() {
        return new PortfolioAiCallException(INTERRUPTED_CODE, INTERRUPTED_MESSAGE, false);
    }
}
