package com.jxc.wefolio.common.lock;

import java.util.function.Supplier;

/**
 * 统一封装普通分布式锁与事务完成后释放的公平分布式锁。
 *
 * <p>同一逻辑锁 key 必须始终使用同一种锁类型，不得在普通锁与公平锁入口之间混用；
 * 混用会绕过公平锁的排队机制并破坏其等待、唤醒语义。</p>
 */
public interface DistributedLockExecutor {

    /**
     * 获取普通锁执行动作，并在动作结束时立即释放。
     *
     * @param lockKey 业务逻辑锁 key，不得再传给公平锁入口
     * @param action 锁内动作
     * @return 动作结果
     * @param <T> 结果类型
     */
    <T> T execute(String lockKey, Supplier<T> action);

    /**
     * 获取公平锁执行动作；存在事务同步时延迟到事务完成后释放。
     *
     * @param lockKey 业务逻辑锁 key，不得再传给普通锁入口
     * @param action 锁内动作
     * @return 动作结果
     * @param <T> 结果类型
     */
    <T> T executeFairUntilTransactionCompletion(String lockKey, Supplier<T> action);
}
