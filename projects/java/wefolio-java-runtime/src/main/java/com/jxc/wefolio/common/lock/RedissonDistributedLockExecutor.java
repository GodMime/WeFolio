package com.jxc.wefolio.common.lock;

import com.jxc.wefolio.common.redis.RedisKeyNamespace;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.Objects;
import java.util.function.Supplier;

/** 基于 Redisson 看门狗锁的生产分布式锁执行器。 */
@Component
@ConditionalOnProperty(prefix = "redis", name = "enabled", havingValue = "true", matchIfMissing = true)
public class RedissonDistributedLockExecutor implements DistributedLockExecutor {

    /** Redisson 客户端。 */
    private final RedissonClient redissonClient;

    /** Redis 物理 key 命名空间。 */
    private final RedisKeyNamespace keyNamespace;

    /**
     * 创建生产分布式锁执行器。
     *
     * @param redissonClient Redisson 客户端
     * @param keyNamespace Redis 物理 key 命名空间
     */
    public RedissonDistributedLockExecutor(RedissonClient redissonClient, RedisKeyNamespace keyNamespace) {
        this.redissonClient = redissonClient;
        this.keyNamespace = keyNamespace;
    }

    /** 普通锁在临界区退出时立即释放。 */
    @Override
    public <T> T execute(String lockKey, Supplier<T> action) {
        Objects.requireNonNull(action, "分布式锁动作不能为空");
        RLock lock = redissonClient.getLock(keyNamespace.physicalKey(lockKey));
        lock.lock();
        return executeAndRelease(action, lock::unlock);
    }

    /** 公平锁在事务提交或回滚完成后释放，无事务时立即释放。 */
    @Override
    public <T> T executeFairUntilTransactionCompletion(String lockKey, Supplier<T> action) {
        Objects.requireNonNull(action, "分布式锁动作不能为空");
        RLock lock = redissonClient.getFairLock(keyNamespace.physicalKey(lockKey));
        lock.lock();
        return executeAndRelease(action, () -> releaseAfterTransaction(lock));
    }

    /**
     * 执行动作并保证释放；动作和释放同时失败时保留动作异常，将释放异常记为 suppressed。
     */
    private <T> T executeAndRelease(Supplier<T> action, Runnable releaseAction) {
        Throwable actionFailure = null;
        try {
            return action.get();
        } catch (RuntimeException | Error exception) {
            actionFailure = exception;
            throw exception;
        } finally {
            try {
                releaseAction.run();
            } catch (RuntimeException | Error releaseException) {
                if (actionFailure == null) {
                    throw releaseException;
                }
                if (actionFailure != releaseException) {
                    actionFailure.addSuppressed(releaseException);
                }
            }
        }
    }

    /** 根据当前事务同步状态决定立即释放或延迟释放。 */
    private void releaseAfterTransaction(RLock lock) {
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            lock.unlock();
            return;
        }
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
    }
}
