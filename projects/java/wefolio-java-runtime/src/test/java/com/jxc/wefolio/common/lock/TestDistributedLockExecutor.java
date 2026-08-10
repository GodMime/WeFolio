package com.jxc.wefolio.common.lock;

import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Supplier;

/** 单元测试使用的进程内分布式锁替身。 */
@Component
@Profile("test")
public class TestDistributedLockExecutor implements DistributedLockExecutor {

    /** 按逻辑 key 保存测试进程内公平锁。 */
    private final ConcurrentHashMap<String, ReentrantLock> locks = new ConcurrentHashMap<>();

    /** 使用普通锁执行动作并在动作结束后立即释放。 */
    @Override
    public <T> T execute(String lockKey, Supplier<T> action) {
        return executeWithLock(lockKey, action, false);
    }

    /** 使用公平锁执行动作并在事务完成后释放。 */
    @Override
    public <T> T executeFairUntilTransactionCompletion(String lockKey, Supplier<T> action) {
        return executeWithLock(lockKey, action, true);
    }

    /** 获取公平本地锁，并按调用模式决定释放时点。 */
    private <T> T executeWithLock(String lockKey, Supplier<T> action, boolean transactionAware) {
        Objects.requireNonNull(lockKey, "测试锁 key 不能为空");
        Objects.requireNonNull(action, "测试锁动作不能为空");
        ReentrantLock lock = locks.computeIfAbsent(lockKey, key -> new ReentrantLock(true));
        lock.lock();
        try {
            return action.get();
        } finally {
            if (transactionAware && TransactionSynchronizationManager.isSynchronizationActive()) {
                try {
                    TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                        @Override
                        public void afterCompletion(int status) {
                            lock.unlock();
                        }
                    });
                } catch (RuntimeException exception) {
                    lock.unlock();
                    throw exception;
                }
            } else {
                lock.unlock();
            }
        }
    }
}
