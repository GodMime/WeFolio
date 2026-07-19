package com.jxc.wefolio.job.service;

import com.jxc.wefolio.job.config.WorkStorageBillingProperties;
import com.jxc.wefolio.job.model.WorkStorageBillingModels.UserStorageAggregate;
import com.jxc.wefolio.job.repo.WorkStorageBillingRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** 作品存储月度远程结算协调测试。 */
@ExtendWith(MockitoExtension.class)
class WorkStorageBillingServiceTest {

    @Mock
    private WorkStorageBillingRepository repository;

    @Mock
    private RuntimeWorkStorageBillingClient runtimeClient;

    @Test
    void runShouldPageByCursorContinueAfterFailureAndSummarizeRuntimeResults() {
        LocalDate month = LocalDate.of(2026, 7, 1);
        UserStorageAggregate first = new UserStorageAggregate(7L, 2L, 20L);
        UserStorageAggregate second = new UserStorageAggregate(8L, 1L, 10L);
        UserStorageAggregate third = new UserStorageAggregate(9L, 0L, 0L);
        when(repository.findUnbilledUserAggregates(any(), anyLong(), anyInt()))
                .thenReturn(List.of(first, second), List.of(third), List.of());
        when(runtimeClient.settle(7L, month)).thenThrow(new IllegalStateException("单用户失败"));
        when(runtimeClient.settle(8L, month)).thenReturn(result("CHARGED", 1L, 1L));
        when(runtimeClient.settle(9L, month)).thenReturn(result("NO_CHARGE", 0L, 0L));

        var summary = service().run(month, "execution-1");

        assertThat(summary.scanned()).isEqualTo(3);
        assertThat(summary.failed()).isEqualTo(1);
        assertThat(summary.charged()).isEqualTo(1);
        assertThat(summary.noCharge()).isEqualTo(1);
        assertThat(summary.pointsDue()).isEqualTo(1);
        assertThat(summary.pointsDeducted()).isEqualTo(1);
        assertThat(summary.totalFileSizeBytes()).isEqualTo(30L);
        ArgumentCaptor<Long> cursors = ArgumentCaptor.forClass(Long.class);
        verify(repository, org.mockito.Mockito.times(3))
                .findUnbilledUserAggregates(any(), cursors.capture(), anyInt());
        assertThat(cursors.getAllValues()).containsExactly(0L, 8L, 9L);
    }

    private WorkStorageBillingService service() {
        WorkStorageBillingProperties properties = new WorkStorageBillingProperties();
        properties.setBatchSize(2);
        return new WorkStorageBillingService(repository, runtimeClient, properties);
    }

    private RuntimeWorkStorageBillingClient.SettlementResult result(
            String status,
            long pointsDue,
            long pointsDeducted
    ) {
        RuntimeWorkStorageBillingClient.SettlementResult result =
                new RuntimeWorkStorageBillingClient.SettlementResult();
        result.setStatus(status);
        result.setPointsDue(pointsDue);
        result.setPointsDeducted(pointsDeducted);
        return result;
    }
}
