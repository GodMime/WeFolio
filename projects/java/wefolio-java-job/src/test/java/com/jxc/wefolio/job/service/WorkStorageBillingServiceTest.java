package com.jxc.wefolio.job.service;

import com.jxc.wefolio.job.config.WorkStorageBillingProperties;
import com.jxc.wefolio.job.model.WorkStorageBillingModels.BillingRule;
import com.jxc.wefolio.job.model.WorkStorageBillingModels.BillingStatus;
import com.jxc.wefolio.job.model.WorkStorageBillingModels.SettlementResult;
import com.jxc.wefolio.job.model.WorkStorageBillingModels.UserStorageAggregate;
import com.jxc.wefolio.job.repo.WorkStorageBillingRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 作品存储月度批处理服务测试。
 */
@ExtendWith(MockitoExtension.class)
class WorkStorageBillingServiceTest {

    @Mock
    private WorkStorageBillingRepository repository;

    @Mock
    private WorkStorageBillingUserLockService lockService;

    @Test
    void runShouldLoadRuleAtDecemberBillingMonthEnd() {
        when(repository.findActiveRule(any())).thenReturn(rule());
        when(repository.findUnbilledUserAggregates(any(), anyLong(), anyInt())).thenReturn(List.of());

        service().run(LocalDate.of(2026, 12, 1), "execution-1");

        ArgumentCaptor<LocalDateTime> effectiveAt = ArgumentCaptor.forClass(LocalDateTime.class);
        verify(repository).findActiveRule(effectiveAt.capture());
        assertThat(effectiveAt.getValue())
                .isEqualTo(LocalDateTime.of(2026, 12, 31, 23, 59, 59, 999_000_000));
    }

    @Test
    void missingActiveRuleShouldAbortBeforeScanningUsers() {
        when(repository.findActiveRule(any())).thenReturn(null);

        assertThatThrownBy(() -> service().run(LocalDate.of(2026, 7, 1), "execution-1"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("作品存储积分规则不存在或未启用");
        verify(repository, never()).findUnbilledUserAggregates(any(), anyLong(), anyInt());
    }

    @Test
    void runShouldPageByCursorContinueAfterFailureAndSummarize() {
        UserStorageAggregate first = aggregate(7L, 20L * mb());
        UserStorageAggregate second = aggregate(8L, 10L * mb());
        UserStorageAggregate third = aggregate(9L, 0L);
        when(repository.findActiveRule(any())).thenReturn(rule());
        when(repository.findUnbilledUserAggregates(any(), anyLong(), anyInt()))
                .thenReturn(List.of(first, second), List.of(third), List.of());
        when(lockService.settleWithLock(any(), any(), any())).thenAnswer(invocation -> {
            UserStorageAggregate aggregate = invocation.getArgument(2);
            if (aggregate.userId() == 7L) {
                throw new IllegalStateException("单用户失败");
            }
            if (aggregate.userId() == 8L) {
                return new SettlementResult(BillingStatus.CHARGED, false, 1, 1, aggregate.totalFileSizeBytes());
            }
            return new SettlementResult(BillingStatus.NO_CHARGE, false, 0, 0, aggregate.totalFileSizeBytes());
        });

        var summary = service().run(LocalDate.of(2026, 7, 1), "execution-1");

        assertThat(summary.scanned()).isEqualTo(3);
        assertThat(summary.failed()).isEqualTo(1);
        assertThat(summary.charged()).isEqualTo(1);
        assertThat(summary.noCharge()).isEqualTo(1);
        assertThat(summary.pointsDue()).isEqualTo(1);
        assertThat(summary.pointsDeducted()).isEqualTo(1);
        assertThat(summary.totalFileSizeBytes()).isEqualTo(30L * mb());
        ArgumentCaptor<Long> cursors = ArgumentCaptor.forClass(Long.class);
        verify(repository, org.mockito.Mockito.times(3))
                .findUnbilledUserAggregates(any(), cursors.capture(), anyInt());
        assertThat(cursors.getAllValues()).containsExactly(0L, 8L, 9L);
    }

    private WorkStorageBillingService service() {
        WorkStorageBillingProperties properties = new WorkStorageBillingProperties();
        properties.setBatchSize(2);
        return new WorkStorageBillingService(
                repository, lockService, new WorkStorageBillingCalculator(), properties);
    }

    private BillingRule rule() {
        return new BillingRule(32L, "MONTHLY_WORK_STORAGE", 1, 10, 1L);
    }

    private UserStorageAggregate aggregate(long userId, long bytes) {
        return new UserStorageAggregate(userId, 1, bytes);
    }

    private long mb() {
        return 1024L * 1024L;
    }
}
