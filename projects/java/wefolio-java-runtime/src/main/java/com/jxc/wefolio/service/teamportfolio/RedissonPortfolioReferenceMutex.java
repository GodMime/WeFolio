package com.jxc.wefolio.service.teamportfolio;

import com.jxc.wefolio.common.lock.DistributedLockExecutor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.Objects;
import java.util.function.Supplier;

/** 使用 Redisson 公平锁保护跨实例作品集引用变更。 */
@Component
@ConditionalOnProperty(prefix = "redis", name = "enabled", havingValue = "true", matchIfMissing = true)
public class RedissonPortfolioReferenceMutex implements PortfolioReferenceMutex {

    /** 个人删除与团队引用写入共用的全局逻辑锁 key。 */
    private static final String PORTFOLIO_REFERENCE_LOCK_KEY = "lock:portfolio-reference";

    /** 分布式锁执行器。 */
    private final DistributedLockExecutor lockExecutor;

    /**
     * 创建作品集引用互斥适配器。
     *
     * @param lockExecutor 分布式锁执行器
     */
    public RedissonPortfolioReferenceMutex(DistributedLockExecutor lockExecutor) {
        this.lockExecutor = lockExecutor;
    }

    /** 全局公平锁延迟到事务提交或回滚后释放。 */
    @Override
    public <T> T execute(Supplier<T> action) {
        Objects.requireNonNull(action, "作品集引用互斥动作不能为空");
        return lockExecutor.executeFairUntilTransactionCompletion(
                PORTFOLIO_REFERENCE_LOCK_KEY,
                action
        );
    }
}
