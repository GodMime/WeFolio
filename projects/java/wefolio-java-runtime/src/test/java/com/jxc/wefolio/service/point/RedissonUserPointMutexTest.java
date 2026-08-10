package com.jxc.wefolio.service.point;

import com.jxc.wefolio.common.lock.DistributedLockExecutor;
import org.junit.jupiter.api.Test;

import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;

/** Redis 用户积分互斥适配测试。 */
class RedissonUserPointMutexTest {

    /** 验证积分互斥使用按用户隔离、事务感知的公平锁 key。 */
    @Test
    void delegatesToTransactionAwareFairLockForReadableUserKey() {
        RecordingExecutor executor = new RecordingExecutor();
        RedissonUserPointMutex mutex = new RedissonUserPointMutex(executor);

        assertThat(mutex.execute(42L, () -> "done")).isEqualTo("done");
        assertThat(executor.lockKey).isEqualTo("lock:point:user:42");
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
