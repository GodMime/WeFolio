package com.jxc.wefolio.service.payment;

import com.jxc.wefolio.config.WechatVirtualPaymentProperties;
import com.jxc.wefolio.entity.PointAccountEntity;
import com.jxc.wefolio.entity.PointDebitTaskEntity;
import com.jxc.wefolio.entity.PointGiftOrderEntity;
import com.jxc.wefolio.mapper.PointAccountEntityMapper;
import com.jxc.wefolio.mapper.PointDebitTaskEntityMapper;
import com.jxc.wefolio.mapper.PointGiftOrderEntityMapper;
import com.jxc.wefolio.mapper.PointPendingDebitEntityMapper;
import com.jxc.wefolio.mapper.PointTransactionEntityMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 微信虚拟支付执行租约事务测试。
 */
@ExtendWith(MockitoExtension.class)
class VirtualPaymentExecutionLeaseTransactionTest {

    /** 测试租约令牌。 */
    private static final String EXECUTION_LEASE_TOKEN = "0123456789ABCDEF0123456789ABCDEF";

    @Mock
    private PointGiftOrderEntityMapper giftOrderMapper;

    @Mock
    private PointDebitTaskEntityMapper debitTaskMapper;

    @Mock
    private PointAccountEntityMapper accountMapper;

    @Mock
    private PointTransactionEntityMapper transactionMapper;

    @Mock
    private PointPendingDebitEntityMapper pendingDebitMapper;

    @Mock
    private PointDebitTaskService debitTaskService;

    /** Release B 的领取、续租和人工释放只能维护新租约字段。 */
    @Test
    void mappersShouldUseExecutionLeaseTokenOnlyAfterReleaseBSwitch() throws Exception {
        String giftMapper = Files.readString(Path.of(
                "src/main/java/com/jxc/wefolio/mapper/PointGiftOrderEntityMapper.java"));
        String debitMapper = Files.readString(Path.of(
                "src/main/java/com/jxc/wefolio/mapper/PointDebitTaskEntityMapper.java"));

        assertThat(giftMapper)
                .contains("execution_lease_token = #{executionLeaseToken}")
                .contains("execution_lease_token = NULL")
                .doesNotContain("lease_owner");
        assertThat(debitMapper)
                .contains("execution_lease_token = #{executionLeaseToken}")
                .contains("execution_lease_token = NULL")
                .doesNotContain("lease_owner");
    }

    /** 旧令牌续租失败后必须以专用异常终止执行。 */
    @Test
    void giftLeaseRenewalShouldRejectReplacedToken() {
        LocalDateTime databaseNow = LocalDateTime.of(2026, 8, 8, 17, 30);
        when(giftOrderMapper.selectCurrentTimestamp()).thenReturn(databaseNow);
        when(giftOrderMapper.renewLease(
                17L, EXECUTION_LEASE_TOKEN, databaseNow.plusSeconds(60))).thenReturn(0);

        assertThatThrownBy(() -> giftService().renewLease(17L, EXECUTION_LEASE_TOKEN))
                .isInstanceOf(ExecutionLeaseLostException.class)
                .hasMessageContaining("赠送订单");
    }

    /** 有效令牌续租时只能延长同一令牌对应的截止时间。 */
    @Test
    void debitLeaseRenewalShouldUseDatabaseTime() {
        LocalDateTime databaseNow = LocalDateTime.of(2026, 8, 8, 17, 31);
        when(debitTaskMapper.selectCurrentTimestamp()).thenReturn(databaseNow);
        when(debitTaskMapper.renewLease(
                23L, EXECUTION_LEASE_TOKEN, databaseNow.plusSeconds(60))).thenReturn(1);

        debitService().renewLease(23L, EXECUTION_LEASE_TOKEN);

        verify(debitTaskMapper).renewLease(
                23L, EXECUTION_LEASE_TOKEN, databaseNow.plusSeconds(60));
    }

    /** 被替换令牌不得同步任务对应积分账户。 */
    @Test
    void syncBalanceShouldRejectReplacedTokenBeforeChangingAccount() {
        LocalDateTime databaseNow = LocalDateTime.of(2026, 8, 8, 17, 32);
        when(debitTaskMapper.selectCurrentTimestamp()).thenReturn(databaseNow);
        when(debitTaskMapper.renewLease(
                23L, EXECUTION_LEASE_TOKEN, databaseNow.plusSeconds(60))).thenReturn(0);

        assertThatThrownBy(() -> debitService().syncBalance(
                23L, EXECUTION_LEASE_TOKEN, 900L, 300L))
                .isInstanceOf(ExecutionLeaseLostException.class);
        verify(accountMapper, never()).syncWechatBalance(23L, 900L, 300L);
    }

    /** 有效令牌只能通过任务中保存的 accountId 同步账户。 */
    @Test
    void syncBalanceShouldResolveAccountFromClaimedTask() {
        LocalDateTime databaseNow = LocalDateTime.of(2026, 8, 8, 17, 33);
        PointDebitTaskEntity task = new PointDebitTaskEntity();
        task.setId(23L);
        task.setAccountId(41L);
        task.setExecutionLeaseToken(EXECUTION_LEASE_TOKEN);
        PointAccountEntity account = new PointAccountEntity();
        account.setId(41L);
        when(debitTaskMapper.selectCurrentTimestamp()).thenReturn(databaseNow);
        when(debitTaskMapper.renewLease(
                23L, EXECUTION_LEASE_TOKEN, databaseNow.plusSeconds(60))).thenReturn(1);
        when(debitTaskMapper.selectById(23L)).thenReturn(task);
        when(accountMapper.syncWechatBalance(41L, 900L, 300L)).thenReturn(1);
        when(accountMapper.selectById(41L)).thenReturn(account);

        PointAccountEntity result = debitService().syncBalance(
                23L, EXECUTION_LEASE_TOKEN, 900L, 300L);

        assertThat(result).isSameAs(account);
        verify(accountMapper).syncWechatBalance(41L, 900L, 300L);
    }

    /** 被新执行接管后，旧赠送执行不得修改账户。 */
    @Test
    void completeGiftShouldRejectOldTokenBeforeChangingAccount() {
        LocalDateTime databaseNow = LocalDateTime.of(2026, 8, 8, 17, 34);
        PointGiftOrderEntity order = new PointGiftOrderEntity();
        order.setId(17L);
        order.setExecutionLeaseToken("BBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBB");
        when(giftOrderMapper.selectById(17L)).thenReturn(order);
        when(giftOrderMapper.selectCurrentTimestamp()).thenReturn(databaseNow);
        when(giftOrderMapper.renewLease(
                17L, EXECUTION_LEASE_TOKEN, databaseNow.plusSeconds(60))).thenReturn(0);

        assertThatThrownBy(() -> giftService().completeSuccess(
                17L, EXECUTION_LEASE_TOKEN, successResult()))
                .isInstanceOf(ExecutionLeaseLostException.class);
        verify(accountMapper, never()).applyGiftWechatBalance(17L, 1L, 900L, 300L);
    }

    /** 构造赠送事务服务。 */
    private PointGiftOrderTransactionService giftService() {
        return new PointGiftOrderTransactionService(
                giftOrderMapper, accountMapper, transactionMapper, debitTaskService, properties());
    }

    /** 构造扣币事务服务。 */
    private PointDebitTaskTransactionService debitService() {
        return new PointDebitTaskTransactionService(
                debitTaskMapper, accountMapper, pendingDebitMapper, properties());
    }

    /** 构造默认租约配置。 */
    private WechatVirtualPaymentProperties properties() {
        WechatVirtualPaymentProperties properties = new WechatVirtualPaymentProperties();
        properties.getSettlement().setLeaseDuration(Duration.ofSeconds(60));
        return properties;
    }

    /** 构造微信成功结果。 */
    private WechatVirtualPaymentResult successResult() {
        return new WechatVirtualPaymentResult(
                0, null, WechatVirtualPaymentErrorType.SUCCESS,
                900L, 300L, 0L, null, 0L, 0L, 0);
    }
}
