package com.jxc.wefolio.service.teamportfolio;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.Objects;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Supplier;

/**
 * 单 JVM 作品集引用互斥实现。
 *
 * <p>事务方法内调用时，锁会延迟到事务提交或回滚完成后释放，避免方法返回到事务真正提交之间
 * 出现新的引用写入。多实例部署前通过 {@code portfolio.reference-lock.provider} 替换。</p>
 */
@Component
@ConditionalOnProperty(
        prefix = "portfolio.reference-lock",
        name = "provider",
        havingValue = "local",
        matchIfMissing = true
)
public class LocalPortfolioReferenceMutex implements PortfolioReferenceMutex {

    /** 当前 JVM 内全部个人删除与团队引用写入共用的公平锁。 */
    private final ReentrantLock lock = new ReentrantLock(true);

    /** 在全局引用互斥区间内执行，并保证事务完成后再释放锁。 */
    @Override
    public <T> T execute(Supplier<T> action) {
        Objects.requireNonNull(action, "作品集引用互斥动作不能为空");
        lock.lock();
        try {
            return action.get();
        } finally {
            if (TransactionSynchronizationManager.isSynchronizationActive()) {
                TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                    @Override
                    public void afterCompletion(int status) {
                        lock.unlock();
                    }
                });
            } else {
                lock.unlock();
            }
        }
    }
}
