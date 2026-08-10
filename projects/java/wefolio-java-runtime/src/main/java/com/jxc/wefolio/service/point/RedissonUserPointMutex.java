package com.jxc.wefolio.service.point;

import com.jxc.wefolio.common.lock.DistributedLockExecutor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.Objects;
import java.util.function.Supplier;

/** 使用 Redisson 公平锁串行化同一用户积分变更。 */
@Component
@ConditionalOnProperty(prefix = "redis", name = "enabled", havingValue = "true", matchIfMissing = true)
public class RedissonUserPointMutex implements UserPointMutex {

    /** 用户积分锁逻辑 key 前缀。 */
    private static final String USER_POINT_LOCK_PREFIX = "lock:point:user:";

    /** 分布式锁执行器。 */
    private final DistributedLockExecutor lockExecutor;

    /**
     * 创建用户积分互斥适配器。
     *
     * @param lockExecutor 分布式锁执行器
     */
    public RedissonUserPointMutex(DistributedLockExecutor lockExecutor) {
        this.lockExecutor = lockExecutor;
    }

    /** 同一用户公平锁延迟到事务提交或回滚后释放。 */
    @Override
    public <T> T execute(Long userId, Supplier<T> action) {
        Objects.requireNonNull(userId, "用户 ID 不能为空");
        Objects.requireNonNull(action, "用户积分互斥动作不能为空");
        return lockExecutor.executeFairUntilTransactionCompletion(
                USER_POINT_LOCK_PREFIX + userId,
                action
        );
    }
}
