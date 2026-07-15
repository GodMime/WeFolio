package com.jxc.wefolio.job.service;

import com.jxc.wefolio.job.model.WorkStorageBillingModels.BillingRule;
import com.jxc.wefolio.job.model.WorkStorageBillingModels.SettlementResult;
import com.jxc.wefolio.job.model.WorkStorageBillingModels.UserStorageAggregate;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.ReentrantLock;

/**
 * 按用户和账期串行化结算的 JVM 锁服务。
 *
 * <p>同步锁在事务代理调用前取得，代理返回后才释放，等待锁时不占用数据库事务。</p>
 */
@Service
@RequiredArgsConstructor
public class WorkStorageBillingUserLockService {

    /** 单用户事务服务 */
    private final WorkStorageBillingTransactionService transactionService;

    /** 可回收的账期锁表 */
    private final ConcurrentHashMap<BillingKey, LockHolder> locks = new ConcurrentHashMap<>();

    /**
     * 持锁执行单用户事务。
     *
     * @param rule 积分规则
     * @param billingMonth 账期
     * @param aggregate 用户聚合
     * @return 结算结果
     */
    public SettlementResult settleWithLock(
            BillingRule rule,
            LocalDate billingMonth,
            UserStorageAggregate aggregate
    ) {
        BillingKey key = new BillingKey(aggregate.userId(), billingMonth);
        LockHolder holder = locks.compute(key, (ignored, current) -> {
            LockHolder selected = current == null ? new LockHolder() : current;
            selected.references.incrementAndGet();
            return selected;
        });
        holder.lock.lock();
        try {
            return transactionService.settleUser(rule, billingMonth, aggregate);
        } finally {
            holder.lock.unlock();
            locks.computeIfPresent(key, (ignored, current) -> {
                if (current != holder) {
                    return current;
                }
                return holder.references.decrementAndGet() == 0 ? null : holder;
            });
        }
    }

    /** 仅供同包单元测试确认无锁对象泄漏。 */
    int activeLockCountForTest() {
        return locks.size();
    }

    /** 用户账期锁键。 */
    private record BillingKey(long userId, LocalDate billingMonth) {
    }

    /** 带引用计数的锁对象。 */
    private static final class LockHolder {
        private final ReentrantLock lock = new ReentrantLock();
        private final AtomicInteger references = new AtomicInteger();
    }
}
