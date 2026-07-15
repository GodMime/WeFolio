package com.jxc.wefolio.job.service;

import com.jxc.wefolio.job.model.WorkStorageBillingModels.BillingRule;
import com.jxc.wefolio.job.model.WorkStorageBillingModels.BillingStatus;
import com.jxc.wefolio.job.model.WorkStorageBillingModels.BillCompletion;
import com.jxc.wefolio.job.model.WorkStorageBillingModels.PointAccount;
import com.jxc.wefolio.job.model.WorkStorageBillingModels.PointTransactionWrite;
import com.jxc.wefolio.job.model.WorkStorageBillingModels.SystemMessageWrite;
import com.jxc.wefolio.job.model.WorkStorageBillingModels.UserStorageAggregate;
import com.jxc.wefolio.job.repo.WorkStorageBillingRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.lang.reflect.Method;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.TimeZone;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 单用户作品存储结算事务测试。
 */
@ExtendWith(MockitoExtension.class)
class WorkStorageBillingTransactionServiceTest {

    private static final Clock FIXED_CLOCK = Clock.fixed(
            Instant.parse("2026-07-31T18:00:00Z"), ZoneId.of("Asia/Shanghai"));

    @Mock
    private WorkStorageBillingRepository repository;

    @Test
    void settleUserShouldUseRequiresNewTransaction() throws NoSuchMethodException {
        Method method = WorkStorageBillingTransactionService.class.getMethod(
                "settleUser", BillingRule.class, LocalDate.class, UserStorageAggregate.class);
        Transactional transactional = method.getAnnotation(Transactional.class);

        assertThat(transactional).isNotNull();
        assertThat(transactional.propagation()).isEqualTo(Propagation.REQUIRES_NEW);
        assertThat(transactional.rollbackFor()).contains(Exception.class);
    }

    @Test
    void duplicateBillShouldReturnSkippedWithoutTouchingAccount() {
        when(repository.tryInsertBill(anyLong(), any(), any(), any(), any())).thenReturn(null);

        var result = service().settleUser(rule(), month(), aggregate(20L * mb()));

        assertThat(result.skipped()).isTrue();
        verify(repository, never()).ensurePointAccount(anyLong(), any());
        verify(repository, never()).lockPointAccount(anyLong());
    }

    @Test
    void zeroDueShouldCreateNoChargeBillWithoutTransaction() {
        when(repository.tryInsertBill(anyLong(), any(), any(), any(), any())).thenReturn(101L);

        var result = service().settleUser(rule(), month(), aggregate(9L * mb()));

        assertThat(result.status()).isEqualTo(BillingStatus.NO_CHARGE);
        verify(repository, never()).ensurePointAccount(anyLong(), any());
        verify(repository, never()).insertPointTransaction(any());
        ArgumentCaptor<BillCompletion> completion = ArgumentCaptor.forClass(BillCompletion.class);
        verify(repository).completeBill(completion.capture());
        assertThat(completion.getValue().remark())
                .isEqualTo("2026-07 作品总大小 9.00 MB，无需扣分");
    }

    @Test
    void zeroMbShouldUseCompactNoChargeRemark() {
        when(repository.tryInsertBill(anyLong(), any(), any(), any(), any())).thenReturn(101L);

        service().settleUser(rule(), month(), aggregate(0));

        ArgumentCaptor<BillCompletion> completion = ArgumentCaptor.forClass(BillCompletion.class);
        verify(repository).completeBill(completion.capture());
        assertThat(completion.getValue().remark())
                .isEqualTo("2026-07 作品总大小 0 MB，无需扣分");
    }

    @Test
    void insufficientBalanceShouldDeductAvailablePointsAndWriteSnapshotAndLowBalanceMessage() {
        when(repository.tryInsertBill(anyLong(), any(), any(), any(), any())).thenReturn(101L);
        when(repository.lockPointAccount(7L)).thenReturn(new PointAccount(10L, 8L));
        when(repository.updatePointAccount(eq(10L), eq(8L), eq(8L), any())).thenReturn(true);
        when(repository.insertPointTransaction(any())).thenReturn(301L);

        var result = service().settleUser(rule(), month(), aggregate(205L * mb()));

        assertThat(result.status()).isEqualTo(BillingStatus.PARTIAL);
        assertThat(result.pointsDue()).isEqualTo(20L);
        assertThat(result.pointsDeducted()).isEqualTo(8L);
        ArgumentCaptor<PointTransactionWrite> transaction = ArgumentCaptor.forClass(PointTransactionWrite.class);
        verify(repository).insertPointTransaction(transaction.capture());
        assertThat(transaction.getValue().idempotencyKey()).isEqualTo("WORK_STORAGE:202607:7");
        assertThat(transaction.getValue().pointsChange()).isEqualTo(-8L);
        assertThat(transaction.getValue().remark())
                .isEqualTo("2026-07 作品总大小 205.00 MB，应扣 20 积分，积分不足，实际扣除 8 积分");
        assertThat(transaction.getValue().calculationSnapshot())
                .contains("\"billingMonth\":\"2026-07\"")
                .contains("\"calcMode\":\"MONTHLY_STORAGE_SIZE\"")
                .contains("\"points\":20")
                .contains("\"billedUnits\":20")
                .contains("\"unitBytes\":10485760")
                .contains("\"workCount\":15")
                .contains("\"displayMb\":\"205.00\"")
                .contains("\"pointsDeducted\":8")
                .contains("\"pointsShortfall\":12");
        ArgumentCaptor<SystemMessageWrite> message = ArgumentCaptor.forClass(SystemMessageWrite.class);
        verify(repository).insertLowBalanceMessage(message.capture());
        assertThat(message.getValue().idempotencyKey()).isEqualTo("POINT_LOW_BALANCE:301");
        assertThat(message.getValue().content())
                .isEqualTo("当前积分余额已低于 50，请及时充值，避免影响作品维护和客户访问。");
        ArgumentCaptor<BillCompletion> completion = ArgumentCaptor.forClass(BillCompletion.class);
        verify(repository).completeBill(completion.capture());
        assertThat(completion.getValue().pointsShortfall()).isEqualTo(12L);
    }

    @Test
    void fullChargeAtThresholdShouldNotCreateLowBalanceMessage() {
        when(repository.tryInsertBill(anyLong(), any(), any(), any(), any())).thenReturn(101L);
        when(repository.lockPointAccount(7L)).thenReturn(new PointAccount(10L, 60L));
        when(repository.updatePointAccount(eq(10L), eq(10L), eq(60L), any())).thenReturn(true);
        when(repository.insertPointTransaction(any())).thenReturn(301L);

        var result = service().settleUser(rule(), month(), aggregate(100L * mb()));

        assertThat(result.status()).isEqualTo(BillingStatus.CHARGED);
        verify(repository, never()).insertLowBalanceMessage(any());
    }

    @Test
    void zeroBalanceShouldCreatePartialBillWithoutTransactionOrMessage() {
        when(repository.tryInsertBill(anyLong(), any(), any(), any(), any())).thenReturn(101L);
        when(repository.lockPointAccount(7L)).thenReturn(new PointAccount(10L, 0L));

        var result = service().settleUser(rule(), month(), aggregate(20L * mb()));

        assertThat(result.status()).isEqualTo(BillingStatus.PARTIAL);
        verify(repository, never()).updatePointAccount(anyLong(), anyLong(), anyLong(), any());
        verify(repository, never()).insertPointTransaction(any());
        verify(repository, never()).insertLowBalanceMessage(any());
    }

    @Test
    void decemberBillingShouldBuildCrossYearSafeIdempotencyKey() {
        when(repository.tryInsertBill(anyLong(), any(), any(), any(), any())).thenReturn(101L);
        when(repository.lockPointAccount(7L)).thenReturn(new PointAccount(10L, 100L));
        when(repository.updatePointAccount(eq(10L), eq(1L), eq(100L), any())).thenReturn(true);
        when(repository.insertPointTransaction(any())).thenReturn(301L);

        service().settleUser(rule(), LocalDate.of(2026, 12, 1), aggregate(10L * mb()));

        ArgumentCaptor<PointTransactionWrite> transaction = ArgumentCaptor.forClass(PointTransactionWrite.class);
        verify(repository).insertPointTransaction(transaction.capture());
        assertThat(transaction.getValue().idempotencyKey()).isEqualTo("WORK_STORAGE:202612:7");
        assertThat(transaction.getValue().calculationSnapshot()).contains("\"billingMonth\":\"2026-12\"");
    }

    @Test
    void settleUserShouldUseConfiguredClockWhenJvmDefaultZoneDiffers() {
        TimeZone original = TimeZone.getDefault();
        TimeZone.setDefault(TimeZone.getTimeZone("UTC"));
        try {
            when(repository.tryInsertBill(anyLong(), any(), any(), any(), any())).thenReturn(101L);

            service().settleUser(rule(), month(), aggregate(0));

            ArgumentCaptor<BillCompletion> completion = ArgumentCaptor.forClass(BillCompletion.class);
            verify(repository).completeBill(completion.capture());
            assertThat(completion.getValue().processedAt())
                    .isEqualTo(LocalDateTime.of(2026, 8, 1, 2, 0));
        } finally {
            TimeZone.setDefault(original);
        }
    }

    private WorkStorageBillingTransactionService service() {
        return new WorkStorageBillingTransactionService(
                repository, new WorkStorageBillingCalculator(), FIXED_CLOCK);
    }

    private BillingRule rule() {
        return new BillingRule(32L, "MONTHLY_WORK_STORAGE", 1, 10, 1L);
    }

    private LocalDate month() {
        return LocalDate.of(2026, 7, 1);
    }

    private UserStorageAggregate aggregate(long bytes) {
        return new UserStorageAggregate(7L, 15L, bytes);
    }

    private long mb() {
        return 1024L * 1024L;
    }
}
