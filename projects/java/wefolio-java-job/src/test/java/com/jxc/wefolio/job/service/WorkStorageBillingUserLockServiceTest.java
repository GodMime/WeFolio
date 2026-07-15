package com.jxc.wefolio.job.service;

import com.jxc.wefolio.job.model.WorkStorageBillingModels.BillingRule;
import com.jxc.wefolio.job.model.WorkStorageBillingModels.BillingStatus;
import com.jxc.wefolio.job.model.WorkStorageBillingModels.SettlementResult;
import com.jxc.wefolio.job.model.WorkStorageBillingModels.UserStorageAggregate;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 用户账期 JVM 同步锁测试。
 */
class WorkStorageBillingUserLockServiceTest {

    @Test
    void sameUserAndMonthShouldExecuteSeriallyAndCleanLock() throws Exception {
        WorkStorageBillingTransactionService transactionService = mock(WorkStorageBillingTransactionService.class);
        AtomicInteger active = new AtomicInteger();
        AtomicInteger maxActive = new AtomicInteger();
        doAnswer(invocation -> {
            int current = active.incrementAndGet();
            maxActive.accumulateAndGet(current, Math::max);
            Thread.sleep(80);
            active.decrementAndGet();
            return result();
        }).when(transactionService).settleUser(any(), any(), any());
        WorkStorageBillingUserLockService lockService = new WorkStorageBillingUserLockService(transactionService);

        try (var executor = Executors.newFixedThreadPool(2)) {
            var first = executor.submit(() -> lockService.settleWithLock(rule(), month(), aggregate(7L)));
            var second = executor.submit(() -> lockService.settleWithLock(rule(), month(), aggregate(7L)));
            first.get(2, TimeUnit.SECONDS);
            second.get(2, TimeUnit.SECONDS);
        }

        assertThat(maxActive).hasValue(1);
        assertThat(lockService.activeLockCountForTest()).isZero();
    }

    @Test
    void differentBillingKeysShouldExecuteInParallel() throws Exception {
        WorkStorageBillingTransactionService transactionService = mock(WorkStorageBillingTransactionService.class);
        CountDownLatch entered = new CountDownLatch(2);
        CountDownLatch release = new CountDownLatch(1);
        doAnswer(invocation -> {
            entered.countDown();
            release.await(1, TimeUnit.SECONDS);
            return result();
        }).when(transactionService).settleUser(any(), any(), any());
        WorkStorageBillingUserLockService lockService = new WorkStorageBillingUserLockService(transactionService);

        try (var executor = Executors.newFixedThreadPool(2)) {
            var first = executor.submit(() -> lockService.settleWithLock(rule(), month(), aggregate(7L)));
            var second = executor.submit(() -> lockService.settleWithLock(rule(), month(), aggregate(8L)));
            assertThat(entered.await(1, TimeUnit.SECONDS)).isTrue();
            release.countDown();
            first.get(2, TimeUnit.SECONDS);
            second.get(2, TimeUnit.SECONDS);
        }
    }

    @Test
    void exceptionShouldReleaseLockAndAllowRetry() {
        WorkStorageBillingTransactionService transactionService = mock(WorkStorageBillingTransactionService.class);
        when(transactionService.settleUser(any(), any(), any())).thenReturn(result());
        doThrow(new IllegalStateException("结算失败"))
                .doReturn(result())
                .when(transactionService).settleUser(any(), any(), any());
        WorkStorageBillingUserLockService lockService = new WorkStorageBillingUserLockService(transactionService);

        assertThatThrownBy(() -> lockService.settleWithLock(rule(), month(), aggregate(7L)))
                .isInstanceOf(IllegalStateException.class);
        assertThat(lockService.settleWithLock(rule(), month(), aggregate(7L)).status())
                .isEqualTo(BillingStatus.CHARGED);
        assertThat(lockService.activeLockCountForTest()).isZero();
    }

    private BillingRule rule() {
        return new BillingRule(32L, "MONTHLY_WORK_STORAGE", 1, 10, 1L);
    }

    private LocalDate month() {
        return LocalDate.of(2026, 7, 1);
    }

    private UserStorageAggregate aggregate(long userId) {
        return new UserStorageAggregate(userId, 1, 10L * 1024 * 1024);
    }

    private SettlementResult result() {
        return new SettlementResult(BillingStatus.CHARGED, false, 1, 1, 10L * 1024 * 1024);
    }
}
