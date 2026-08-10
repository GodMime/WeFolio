package com.jxc.wefolio.service.teamportfolio;

import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.Objects;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Supplier;

/**
 * 测试环境的单 JVM 作品集引用互斥实现。
 */
@Component
@Profile("test")
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
