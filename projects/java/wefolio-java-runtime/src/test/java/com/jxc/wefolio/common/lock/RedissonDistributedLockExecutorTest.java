package com.jxc.wefolio.common.lock;

import com.jxc.wefolio.common.redis.RedisKeyNamespace;
import com.jxc.wefolio.config.RedisProperties;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** Redisson 分布式锁执行器测试。 */
class RedissonDistributedLockExecutorTest {

    /** 每个用例结束后清理当前线程可能遗留的事务同步上下文。 */
    @AfterEach
    void clearTransactionSynchronization() {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.clearSynchronization();
        }
    }

    /** 验证普通分布式锁按加锁、执行业务、立即解锁的顺序运行。 */
    @Test
    void executesWithOrdinaryLockAndReleasesImmediately() {
        RedissonClient client = mock(RedissonClient.class);
        RLock lock = mock(RLock.class);
        when(client.getLock("wefolio:runtime:lock:wechat:app-id")).thenReturn(lock);
        RedissonDistributedLockExecutor executor = executor(client);

        String result = executor.execute("lock:wechat:app-id", () -> "done");

        assertThat(result).isEqualTo("done");
        org.mockito.InOrder order = inOrder(lock);
        order.verify(lock).lock();
        order.verify(lock).unlock();
        verify(client, never()).getFairLock("wefolio:runtime:lock:wechat:app-id");
    }

    /** 验证普通分布式锁在业务动作抛出异常时仍会释放。 */
    @Test
    void releasesOrdinaryLockWhenActionFails() {
        RedissonClient client = mock(RedissonClient.class);
        RLock lock = mock(RLock.class);
        when(client.getLock("wefolio:runtime:lock:auth:user:7")).thenReturn(lock);
        RedissonDistributedLockExecutor executor = executor(client);

        assertThatThrownBy(() -> executor.execute("lock:auth:user:7", () -> {
            throw new IllegalStateException("boom");
        })).isInstanceOf(IllegalStateException.class).hasMessage("boom");

        verify(lock).unlock();
    }

    /** 验证公平锁在事务同步开启时延迟到事务完成后释放。 */
    @Test
    void usesFairLockAndDefersReleaseUntilTransactionCompletion() {
        RedissonClient client = mock(RedissonClient.class);
        RLock fairLock = mock(RLock.class);
        when(client.getFairLock("wefolio:runtime:lock:point:user:7")).thenReturn(fairLock);
        RedissonDistributedLockExecutor executor = executor(client);
        TransactionSynchronizationManager.initSynchronization();

        assertThat(executor.executeFairUntilTransactionCompletion(
                "lock:point:user:7",
                () -> "done"
        )).isEqualTo("done");

        verify(fairLock).lock();
        verify(fairLock, never()).unlock();
        List<TransactionSynchronization> synchronizations =
                TransactionSynchronizationManager.getSynchronizations();
        assertThat(synchronizations).hasSize(1);

        synchronizations.getFirst().afterCompletion(TransactionSynchronization.STATUS_COMMITTED);
        verify(fairLock).unlock();
        verify(client, never()).getLock("wefolio:runtime:lock:point:user:7");
    }

    /** 验证不存在事务同步时公平锁会在动作结束后立即释放。 */
    @Test
    void releasesFairLockImmediatelyWithoutTransaction() {
        RedissonClient client = mock(RedissonClient.class);
        RLock fairLock = mock(RLock.class);
        when(client.getFairLock("wefolio:runtime:lock:portfolio-reference")).thenReturn(fairLock);

        executor(client).executeFairUntilTransactionCompletion(
                "lock:portfolio-reference",
                () -> null
        );

        verify(fairLock).unlock();
    }

    /** 验证业务动作与解锁同时失败时保留业务异常并附加解锁异常。 */
    @Test
    void preservesActionFailureWhenFairLockReleaseAlsoFails() {
        RedissonClient client = mock(RedissonClient.class);
        RLock fairLock = mock(RLock.class);
        when(client.getFairLock("wefolio:runtime:lock:point:user:7")).thenReturn(fairLock);
        IllegalStateException actionFailure = new IllegalStateException("业务失败");
        IllegalMonitorStateException releaseFailure = new IllegalMonitorStateException("释放失败");
        doThrow(releaseFailure).when(fairLock).unlock();

        assertThatThrownBy(() -> executor(client).executeFairUntilTransactionCompletion(
                "lock:point:user:7",
                () -> {
                    throw actionFailure;
                }
        )).isSameAs(actionFailure)
                .satisfies(exception -> assertThat(exception.getSuppressed())
                        .containsExactly(releaseFailure));
    }

    /** 使用统一测试命名空间创建分布式锁执行器。 */
    private RedissonDistributedLockExecutor executor(RedissonClient client) {
        return new RedissonDistributedLockExecutor(client, new RedisKeyNamespace(new RedisProperties()));
    }
}
