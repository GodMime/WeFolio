package com.jxc.wefolio.service.point;

import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Supplier;

/**
 * 测试环境的本地用户积分互斥实现。
 */
@Component
@Profile("test")
public class LocalUserPointMutex implements UserPointMutex {

    /** 按用户保存当前持有者和等待者共同引用的锁对象。 */
    private final ConcurrentHashMap<Long, LockReference> locks = new ConcurrentHashMap<>();

    /**
     * 在指定用户的互斥区间内执行业务动作，并在正常或异常退出时释放引用。
     */
    @Override
    public <T> T execute(Long userId, Supplier<T> action) {
        Objects.requireNonNull(userId, "用户 ID 不能为空");
        Objects.requireNonNull(action, "用户积分互斥动作不能为空");
        LockReference reference = retain(userId);
        reference.lock.lock();
        try {
            return action.get();
        } finally {
            releaseAfterTransaction(userId, reference);
        }
    }

    /** 当前动作位于事务中时延迟到提交或回滚完成后释放互斥。 */
    private void releaseAfterTransaction(Long userId, LockReference reference) {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCompletion(int status) {
                    unlockAndRelease(userId, reference);
                }
            });
            return;
        }
        unlockAndRelease(userId, reference);
    }

    /** 释放当前线程持有的锁并回收引用。 */
    private void unlockAndRelease(Long userId, LockReference reference) {
        reference.lock.unlock();
        release(userId, reference);
    }

    /** 获取或创建锁对象，并在同一键原子计算中增加引用数。 */
    private LockReference retain(Long userId) {
        return locks.compute(userId, (key, current) -> {
            LockReference reference = current == null ? new LockReference() : current;
            reference.references.incrementAndGet();
            return reference;
        });
    }

    /** 在同一键原子计算中释放引用，只在没有持有者和等待者时回收锁对象。 */
    private void release(Long userId, LockReference expected) {
        locks.compute(userId, (key, current) -> {
            if (current != expected) {
                throw new IllegalStateException("用户积分锁引用发生不一致");
            }
            int remaining = current.references.decrementAndGet();
            if (remaining < 0) {
                throw new IllegalStateException("用户积分锁引用计数不能为负数");
            }
            return remaining == 0 ? null : current;
        });
    }

    /** 返回当前仍有持有者或等待者的锁对象数量，仅供同包测试和诊断使用。 */
    int activeMutexCount() {
        return locks.size();
    }

    /** 单个用户的可重入锁及持有者、等待者总引用。 */
    private static final class LockReference {

        /** 公平锁避免持续竞争时等待线程长期饥饿。 */
        private final ReentrantLock lock = new ReentrantLock(true);

        /** 已取得该锁对象但尚未完成业务动作的线程数量。 */
        private final AtomicInteger references = new AtomicInteger();
    }
}
