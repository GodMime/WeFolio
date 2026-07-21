package com.jxc.wefolio.service;

import com.jxc.wefolio.dict.PointRuleStatusDict;
import com.jxc.wefolio.dict.PointSceneCodeDict;
import com.jxc.wefolio.dto.WorkStorageBillingSettlementResponse;
import com.jxc.wefolio.entity.PointAccountEntity;
import com.jxc.wefolio.mapper.WorkStorageBillingMapper;
import com.jxc.wefolio.mapper.WorkStorageBillingMapper.BillingRule;
import com.jxc.wefolio.mapper.WorkStorageBillingMapper.StorageAggregate;
import com.jxc.wefolio.service.point.DebitCommand;
import com.jxc.wefolio.service.point.PointCommandService;
import com.jxc.wefolio.service.point.PointMutationResult;
import com.jxc.wefolio.service.point.SystemDebitResult;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.YearMonth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Runtime 作品存储月度结算服务测试。
 */
@ExtendWith(MockitoExtension.class)
class WorkStorageBillingSettlementServiceTest {

    /** 测试用户 ID。 */
    private static final Long USER_ID = 7L;

    /** 2026-08 账期。 */
    private static final YearMonth BILLING_MONTH = YearMonth.of(2026, 8);

    /** 作品存储月费 Mapper。 */
    @Mock
    private WorkStorageBillingMapper workStorageBillingMapper;

    /** 积分账户服务。 */
    @Mock
    private PointService pointService;

    /** 统一积分命令服务。 */
    @Mock
    private PointCommandService pointCommandService;

    /** 待测试结算服务。 */
    @InjectMocks
    private WorkStorageBillingSettlementService settlementService;

    /** 新规则只按完整 2MB 计费，未满计费单位的字节不进位。 */
    @ParameterizedTest
    @CsvSource({
            "2097151, 0",
            "2097152, 1",
            "4194303, 1",
            "4194304, 2"
    })
    void settlementShouldChargeOnlyCompleteTwoMegabyteUnits(
            long totalBytes,
            long expectedPoints
    ) {
        arrangeSettlement(totalBytes, expectedPoints);

        WorkStorageBillingSettlementResponse response = settlementService.settle(USER_ID, BILLING_MONTH);

        assertThat(response.pointsDue()).isEqualTo(expectedPoints);
        assertThat(response.pointsDeducted()).isEqualTo(expectedPoints);
    }

    /** 月费规则必须按账期末时间读取，使 2026-08 账期命中新版本。 */
    @Test
    void settlementShouldLoadRuleAtBillingMonthEnd() {
        arrangeSettlement(0L, 0L);

        settlementService.settle(USER_ID, BILLING_MONTH);

        verify(workStorageBillingMapper).loadRule(
                PointSceneCodeDict.MONTHLY_WORK_STORAGE.getCode(),
                PointRuleStatusDict.ACTIVE.getCode(),
                LocalDateTime.of(2026, 8, 31, 23, 59, 59)
        );
    }

    /**
     * 准备单用户月费结算依赖。
     *
     * @param totalBytes 作品存储总字节数
     * @param expectedPoints 预期应扣积分
     */
    private void arrangeSettlement(long totalBytes, long expectedPoints) {
        when(workStorageBillingMapper.findBill(USER_ID, LocalDate.of(2026, 8, 1)))
                .thenReturn(null);
        when(workStorageBillingMapper.loadStorage(USER_ID))
                .thenReturn(new StorageAggregate(1L, totalBytes));
        when(workStorageBillingMapper.loadRule(anyString(), anyString(), any(LocalDateTime.class)))
                .thenReturn(new BillingRule(41L, 2L, 1L));
        when(pointService.ensureAccount(USER_ID)).thenReturn(pointAccount());
        when(workStorageBillingMapper.insertBill(any())).thenReturn(1);
        if (expectedPoints > 0L) {
            when(pointCommandService.deductForSystem(any(DebitCommand.class)))
                    .thenReturn(SystemDebitResult.deducted(new PointMutationResult(
                            99L,
                            11L,
                            expectedPoints,
                            100L,
                            100L - expectedPoints,
                            false
                    )));
        }
    }

    /** 构造具有充足余额的积分账户。 */
    private PointAccountEntity pointAccount() {
        PointAccountEntity account = new PointAccountEntity();
        account.setId(11L);
        account.setUserId(USER_ID);
        account.setBalance(100L);
        return account;
    }
}
