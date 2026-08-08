package com.jxc.wefolio.service.teamportfolio;

import org.junit.jupiter.api.Test;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/** 单 JVM 作品集引用互斥测试。 */
class LocalPortfolioReferenceMutexTest {

    @Test
    void actionsShouldBeSerializedAcrossPersonalAndTeamMutations() throws Exception {
        LocalPortfolioReferenceMutex mutex = new LocalPortfolioReferenceMutex();
        ExecutorService executor = Executors.newFixedThreadPool(2);
        CountDownLatch firstEntered = new CountDownLatch(1);
        CountDownLatch releaseFirst = new CountDownLatch(1);
        AtomicInteger inside = new AtomicInteger();
        AtomicInteger maxInside = new AtomicInteger();
        try {
            Future<?> first = executor.submit(() -> mutex.execute(() -> {
                maxInside.accumulateAndGet(inside.incrementAndGet(), Math::max);
                firstEntered.countDown();
                await(releaseFirst);
                inside.decrementAndGet();
                return null;
            }));
            assertThat(firstEntered.await(1, TimeUnit.SECONDS)).isTrue();
            Future<?> second = executor.submit(() -> mutex.execute(() -> {
                maxInside.accumulateAndGet(inside.incrementAndGet(), Math::max);
                inside.decrementAndGet();
                return null;
            }));

            releaseFirst.countDown();
            first.get(1, TimeUnit.SECONDS);
            second.get(1, TimeUnit.SECONDS);
        } finally {
            releaseFirst.countDown();
            executor.shutdownNow();
        }

        assertThat(maxInside).hasValue(1);
    }

    @Test
    void transactionShouldKeepMutexUntilCompletionCallback() throws Exception {
        LocalPortfolioReferenceMutex mutex = new LocalPortfolioReferenceMutex();
        ExecutorService executor = Executors.newSingleThreadExecutor();
        CountDownLatch secondEntered = new CountDownLatch(1);
        List<TransactionSynchronization> synchronizations = List.of();
        TransactionSynchronizationManager.initSynchronization();
        try {
            assertThat(mutex.execute(() -> "first")).isEqualTo("first");
            synchronizations = TransactionSynchronizationManager.getSynchronizations();
            Future<?> second = executor.submit(() -> mutex.execute(() -> {
                secondEntered.countDown();
                return null;
            }));

            assertThat(secondEntered.await(100, TimeUnit.MILLISECONDS)).isFalse();
            synchronizations.forEach(synchronization ->
                    synchronization.afterCompletion(TransactionSynchronization.STATUS_COMMITTED));
            TransactionSynchronizationManager.clearSynchronization();
            assertThat(secondEntered.await(1, TimeUnit.SECONDS)).isTrue();
            second.get(1, TimeUnit.SECONDS);
        } finally {
            if (TransactionSynchronizationManager.isSynchronizationActive()) {
                synchronizations.forEach(synchronization ->
                        synchronization.afterCompletion(TransactionSynchronization.STATUS_ROLLED_BACK));
                TransactionSynchronizationManager.clearSynchronization();
            }
            executor.shutdownNow();
        }
    }

    /** 等待测试信号并保留线程中断状态。 */
    private void await(CountDownLatch latch) {
        try {
            latch.await();
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("等待作品集引用互斥测试信号时被中断", exception);
        }
    }
}
