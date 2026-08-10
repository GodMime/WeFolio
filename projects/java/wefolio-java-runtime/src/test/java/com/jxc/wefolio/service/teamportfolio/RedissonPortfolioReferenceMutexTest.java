package com.jxc.wefolio.service.teamportfolio;

import com.jxc.wefolio.common.lock.DistributedLockExecutor;
import org.junit.jupiter.api.Test;

import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;

/** Redis 作品集引用互斥适配测试。 */
class RedissonPortfolioReferenceMutexTest {

    /** 验证作品集引用互斥使用事务感知的全局公平锁 key。 */
    @Test
    void delegatesToTransactionAwareFairGlobalLock() {
        RecordingExecutor executor = new RecordingExecutor();
        RedissonPortfolioReferenceMutex mutex = new RedissonPortfolioReferenceMutex(executor);

        assertThat(mutex.execute(() -> "done")).isEqualTo("done");
        assertThat(executor.lockKey).isEqualTo("lock:portfolio-reference");
        assertThat(executor.fairTransactionAware).isTrue();
    }

    /** 记录互斥适配层选择的执行器入口。 */
    private static final class RecordingExecutor implements DistributedLockExecutor {

        /** 最近一次调用使用的逻辑锁 key。 */
        private String lockKey;

        /** 是否调用了事务感知公平锁入口。 */
        private boolean fairTransactionAware;

        /** 记录普通锁调用并执行传入动作。 */
        @Override
        public <T> T execute(String lockKey, Supplier<T> action) {
            this.lockKey = lockKey;
            return action.get();
        }

        /** 记录事务感知公平锁调用并执行传入动作。 */
        @Override
        public <T> T executeFairUntilTransactionCompletion(String lockKey, Supplier<T> action) {
            this.lockKey = lockKey;
            this.fairTransactionAware = true;
            return action.get();
        }
    }
}
