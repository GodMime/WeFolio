package com.jxc.wefolio.job.service;

import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.ReentrantLock;

/**
 * 统一管理后台任务的准入、排空和停用状态。
 */
@Service
public class JobExecutionLifecycle {

    /** 提供暂停截止时间，生产环境固定使用 UTC 系统时钟，测试可替换。 */
    private final Clock clock;

    /** 保护生命周期状态与活动计数的互斥锁。 */
    private final ReentrantLock lifecycleLock = new ReentrantLock();

    /** 活动工作全部结束时唤醒停用等待线程的条件变量。 */
    private final Condition disabledCondition = lifecycleLock.newCondition();

    /** 标记当前线程正处于已经获得准入的调度回调内。 */
    private final ThreadLocal<Boolean> scheduledCallbackContext = new ThreadLocal<>();

    /** 活动计数越界时使用的固定异常消息。 */
    private static final String NEGATIVE_ACTIVE_TASK_COUNT_MESSAGE = "后台任务活动计数不能小于零";

    /** 当前生命周期状态，只允许在持有 {@link #lifecycleLock} 时读写。 */
    private Status status = Status.ACCEPTING;

    /** 暂停截止时间；接受新任务时为空，只允许在持有生命周期锁时读写。 */
    private Instant pausedUntil;

    /** 已获得凭证且尚未释放的工作数，只允许在持有生命周期锁时读写。 */
    private int activeTaskCount;

    /**
     * 使用 UTC 系统时钟创建生命周期，供非 Spring 场景和简单单元测试使用。
     */
    public JobExecutionLifecycle() {
        this(Clock.systemUTC());
    }

    /**
     * 使用可控时钟创建生命周期。
     *
     * @param clock 暂停截止时间使用的时钟
     */
    JobExecutionLifecycle(Clock clock) {
        this.clock = clock;
    }

    /**
     * 尝试为新的后台工作申请执行凭证。
     *
     * @return 接受新工作时返回凭证，停用过程中返回空
     */
    public Optional<ExecutionPermit> tryAcquire() {
        lifecycleLock.lock();
        try {
            reopenIfPauseExpiredUnsafe();
            if (status != Status.ACCEPTING) {
                return Optional.empty();
            }
            activeTaskCount++;
            return Optional.of(new ExecutionPermit());
        } finally {
            lifecycleLock.unlock();
        }
    }

    /**
     * 为已获准的调度回调申请异步延续凭证。
     *
     * <p>该入口只允许在调度装饰器建立的上下文内调用。即使停用已经开始，也允许已经开始的
     * 调度回调把实际工作安全交接给异步执行器。</p>
     *
     * @return 上下文有效且尚未完全停用时返回凭证，否则返回空
     */
    public Optional<ExecutionPermit> tryAcquireContinuation() {
        if (!Boolean.TRUE.equals(scheduledCallbackContext.get())) {
            return Optional.empty();
        }
        lifecycleLock.lock();
        try {
            reopenIfPauseExpiredUnsafe();
            if (status == Status.DISABLED) {
                return Optional.empty();
            }
            activeTaskCount++;
            return Optional.of(new ExecutionPermit());
        } finally {
            lifecycleLock.unlock();
        }
    }

    /**
     * 在统一调度准入控制下执行回调。
     *
     * @param callback 调度器即将执行的回调
     * @return 是否获得准入并实际执行了回调
     */
    public boolean runScheduled(Runnable callback) {
        Optional<ExecutionPermit> acquiredPermit = tryAcquire();
        if (acquiredPermit.isEmpty()) {
            return false;
        }
        Boolean previousContext = scheduledCallbackContext.get();
        scheduledCallbackContext.set(Boolean.TRUE);
        try (ExecutionPermit ignored = acquiredPermit.orElseThrow()) {
            callback.run();
            return true;
        } finally {
            if (previousContext == null) {
                scheduledCallbackContext.remove();
            } else {
                scheduledCallbackContext.set(previousContext);
            }
        }
    }

    /**
     * 暂停新工作直到指定时长结束，并返回切换后的快照。
     *
     * @param duration 本次暂停时长
     * @return 当前生命周期快照
     */
    public ExecutionSnapshot pause(Duration duration) {
        lifecycleLock.lock();
        try {
            pausedUntil = clock.instant().plus(duration);
            status = activeTaskCount == 0 ? Status.DISABLED : Status.DRAINING;
            if (status == Status.DISABLED) {
                disabledCondition.signalAll();
            }
            return snapshotUnsafe();
        } finally {
            lifecycleLock.unlock();
        }
    }

    /**
     * 在指定时限内等待全部已准入工作自然结束。
     *
     * @param timeout 最长等待时间
     * @return 已进入完全停用状态时返回 {@code true}
     * @throws InterruptedException 等待线程被中断
     */
    public boolean awaitDisabled(Duration timeout) throws InterruptedException {
        long remainingNanos = timeout.toNanos();
        lifecycleLock.lockInterruptibly();
        try {
            while (status != Status.DISABLED) {
                if (remainingNanos <= 0) {
                    return false;
                }
                remainingNanos = disabledCondition.awaitNanos(remainingNanos);
            }
            return true;
        } finally {
            lifecycleLock.unlock();
        }
    }

    /**
     * 获取一致的当前生命周期快照。
     *
     * @return 当前状态与活动工作数
     */
    public ExecutionSnapshot snapshot() {
        lifecycleLock.lock();
        try {
            reopenIfPauseExpiredUnsafe();
            return snapshotUnsafe();
        } finally {
            lifecycleLock.unlock();
        }
    }

    /**
     * 在已经持有生命周期锁时创建一致状态快照。
     *
     * @return 当前状态与活动工作数
     */
    private ExecutionSnapshot snapshotUnsafe() {
        return new ExecutionSnapshot(status, activeTaskCount, pausedUntil);
    }

    /**
     * 在准入或观测同步边界内惰性结束已到期暂停。
     */
    private void reopenIfPauseExpiredUnsafe() {
        if (pausedUntil != null && !clock.instant().isBefore(pausedUntil)) {
            pausedUntil = null;
            status = Status.ACCEPTING;
        }
    }

    /**
     * 释放一个执行凭证，并在排空完成时推进到完全停用状态。
     */
    private void release() {
        lifecycleLock.lock();
        try {
            activeTaskCount--;
            if (activeTaskCount < 0) {
                throw new IllegalStateException(NEGATIVE_ACTIVE_TASK_COUNT_MESSAGE);
            }
            if (status == Status.DRAINING && activeTaskCount == 0) {
                status = Status.DISABLED;
                disabledCondition.signalAll();
            }
        } finally {
            lifecycleLock.unlock();
        }
    }

    /**
     * 后台任务生命周期状态。
     */
    public enum Status {
        /** 接受新任务。 */
        ACCEPTING,
        /** 拒绝新任务并等待已准入任务结束。 */
        DRAINING,
        /** 已拒绝新任务且没有活动任务。 */
        DISABLED
    }

    /**
     * 生命周期的不可变观测快照。
     *
     * @param status 当前状态
     * @param activeTaskCount 当前活动工作数
     * @param pausedUntil 暂停截止时间，接受新任务时为空
     */
    public record ExecutionSnapshot(Status status, int activeTaskCount, Instant pausedUntil) {
    }

    /**
     * 单个已准入后台工作的可关闭执行凭证。
     */
    public final class ExecutionPermit implements AutoCloseable {

        /** 保证同一凭证最多释放一次活动计数。 */
        private final AtomicBoolean closed = new AtomicBoolean();

        private ExecutionPermit() {
        }

        /**
         * 幂等释放当前工作所占用的活动计数。
         */
        @Override
        public void close() {
            if (closed.compareAndSet(false, true)) {
                release();
            }
        }
    }
}
