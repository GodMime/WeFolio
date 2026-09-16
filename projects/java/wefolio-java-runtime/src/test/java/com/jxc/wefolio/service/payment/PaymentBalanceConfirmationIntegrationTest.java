package com.jxc.wefolio.service.payment;

import com.jxc.wefolio.config.WechatVirtualPaymentProperties;
import com.jxc.wefolio.dict.PointDebitTaskStatusDict;
import com.jxc.wefolio.dict.PointGiftOrderStatusDict;
import com.jxc.wefolio.entity.PointDebitTaskEntity;
import com.jxc.wefolio.entity.PointGiftOrderEntity;
import com.jxc.wefolio.entity.UserAuthEntity;
import com.jxc.wefolio.mapper.PointDebitTaskEntityMapper;
import com.jxc.wefolio.mapper.PointGiftOrderEntityMapper;
import com.jxc.wefolio.mapper.UserAuthEntityMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/** 已确认微信成功后的余额补查使用真实任务、账户与事务，不受普通失败预算影响。 */
@ActiveProfiles("test")
@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:payment_balance_confirmation;MODE=MySQL;DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=FALSE"
})
class PaymentBalanceConfirmationIntegrationTest {

    /** 专属 H2 数据库。 */
    @Autowired private JdbcTemplate jdbc;
    /** 真实扣币处理器。 */
    @Autowired private PointDebitTaskProcessor debitProcessor;
    /** 真实赠送处理器。 */
    @Autowired private PointGiftOrderProcessor giftProcessor;
    /** 独立扣币短事务。 */
    @Autowired private PointDebitTaskTransactionService debitTransactions;
    /** 独立赠送短事务。 */
    @Autowired private PointGiftOrderTransactionService giftTransactions;
    /** 真实扣币持久状态。 */
    @Autowired private PointDebitTaskEntityMapper debitTasks;
    /** 真实赠送持久状态。 */
    @Autowired private PointGiftOrderEntityMapper gifts;
    /** 本测试将普通失败预算限制为一次。 */
    @Autowired private WechatVirtualPaymentProperties properties;
    /** 微信调用使用可控响应。 */
    @MockBean private WechatVirtualPaymentClient client;
    /** 会话可用性使用可控响应。 */
    @MockBean private MaintainerWechatSessionService sessions;
    /** 微信身份不是本次事务回归范围。 */
    @MockBean private UserAuthEntityMapper auths;
    /** 实际会话刷新唤醒入口。 */
    @Autowired private PointDebitTaskService activeTasks;
    /** 通过真实提交后事务事件触发会话唤醒。 */
    @Autowired private ApplicationEventPublisher events;
    /** 控制事件发布事务实际提交。 */
    @Autowired private PlatformTransactionManager transactionManager;
    /** 本测试只核对任务恢复，不访问退款记录。 */
    @MockBean private WechatAuthoritativeBalanceSyncService balanceSync;

    /** 创建原任务已到普通重试上限的测试环境。 */
    @BeforeEach
    void setUp() {
        properties.getSettlement().setMaxRetries(1);
        createTables();
        UserAuthEntity auth = new UserAuthEntity();
        auth.setId(51L);
        auth.setUserId(7L);
        auth.setOpenId("openid-test");
        when(auths.selectById(51L)).thenReturn(auth);
        when(auths.selectOne(any())).thenReturn(auth);
        when(sessions.findAvailableSession(7L)).thenReturn(session());
    }

    /** 清理专属内存数据库。 */
    @AfterEach
    void tearDown() {
        properties.getSettlement().setMaxRetries(3);
        jdbc.execute("DROP ALL OBJECTS");
    }

    /** 普通/重复扣币成功后的补查指数退避到一小时，不占普通预算，恢复后仅核销一次。 */
    @ParameterizedTest
    @EnumSource(value = WechatVirtualPaymentErrorType.class, names = {"SUCCESS", "DUPLICATE_SUCCESS"})
    void debitSuccessShouldSurviveExceededBudgetAndNeverPayAgain(WechatVirtualPaymentErrorType type) {
        insertDebit();
        jdbc.update("UPDATE wf_point_debit_task SET last_failed_at = ? WHERE id = 23",
                LocalDateTime.now().minusDays(1));
        long usedPresent = type == WechatVirtualPaymentErrorType.SUCCESS ? 3L : 0L;
        when(client.currencyPay(any())).thenReturn(new WechatVirtualPaymentResult(
                type == WechatVirtualPaymentErrorType.SUCCESS ? 0 : 268490004,
                null, type, 90L, 0L, usedPresent, null, 0L, 0L, 200));
        AtomicInteger queries = new AtomicInteger();
        when(client.queryUserBalance(any())).thenAnswer(invocation -> {
            if (queries.getAndIncrement() == 0) {
                return balance(100L, 30L);
            }
            throw new IllegalStateException("补查临时失败");
        });

        long[] expectedDelayMinutes = {1, 2, 4, 8, 16, 32, 60, 60};
        for (long expectedDelay : expectedDelayMinutes) {
            debitProcessor.process(23L);
            PointDebitTaskEntity pending = debitTasks.selectById(23L);
            assertThat(pending.getStatus()).isEqualTo(PointDebitTaskStatusDict.RETRY_WAIT.getCode());
            assertThat(pending.getLastErrorCode()).isEqualTo(type.name());
            assertThat(pending.getRetryCount()).isEqualTo(1);
            assertThat(pending.getRequestAmount()).isEqualTo(10L);
            assertThat(pending.getTaskNo()).isEqualTo("DEBIT-23");
            assertThat(pending.getActiveFlag()).isEqualTo(1);
            assertThat(pending.getNextExecuteAt()).isAfter(LocalDateTime.now().plusSeconds(50));
            assertThat(Duration.between(pending.getLastFailedAt(), pending.getNextExecuteAt()))
                    .isEqualTo(Duration.ofMinutes(expectedDelay));
            if (type == WechatVirtualPaymentErrorType.SUCCESS) {
                assertThat(pending.getWechatBalanceAfter()).isEqualTo(90L);
            }
            dueDebit();
        }
        verify(client).currencyPay(any());
        doReturn(balance(90L, 27L)).when(client).queryUserBalance(any());

        debitProcessor.process(23L);
        assertThat(debitProcessor.process(23L)).isEqualTo(TaskExecutionOutcome.SKIPPED_NOT_CLAIMABLE);

        PointDebitTaskEntity completed = debitTasks.selectById(23L);
        assertThat(completed.getStatus()).isEqualTo(PointDebitTaskStatusDict.SUCCEEDED.getCode());
        assertThat(completed.getSettledAmount()).isEqualTo(10L);
        assertThat(completed.getUsedPresentAmount()).isEqualTo(usedPresent);
        assertThat(completed.getLastErrorCode()).isEqualTo(type.name());
        assertThat(completed.getActiveFlag()).isNull();
        assertThat(value("SELECT wechat_balance FROM wf_point_account")).isEqualTo(90L);
        assertThat(value("SELECT wechat_present_balance FROM wf_point_account")).isEqualTo(27L);
        assertThat(value("SELECT balance FROM wf_point_account")).isEqualTo(90L);
        assertThat(value("SELECT pending_debit FROM wf_point_account")).isZero();
        assertThat(value("SELECT remaining_amount FROM wf_point_pending_debit")).isZero();
        verify(client).currencyPay(any());
    }

    /** 缺会话赠送补查退避到一小时，新会话事件提前唤醒后只赠送和记账一次。 */
    @Test
    void duplicateGiftShouldWaitWithoutSessionThenSettleWithoutPresentingAgain() {
        insertGift();
        jdbc.update("UPDATE wf_point_gift_order SET last_failed_at = ? WHERE id = 17",
                LocalDateTime.now().minusDays(1));
        when(client.presentCurrency(any())).thenReturn(new WechatVirtualPaymentResult(
                268490004, null, WechatVirtualPaymentErrorType.DUPLICATE_SUCCESS,
                0L, 0L, 0L, null, 0L, 0L, 200));
        when(sessions.findAvailableSession(7L)).thenReturn(null);
        long[] expectedDelayMinutes = {1, 2, 4, 8, 16, 32, 60, 60};
        for (int attempt = 0; attempt < expectedDelayMinutes.length; attempt++) {
            giftProcessor.process(17L);
            assertGiftAwaitingConfirmation();
            PointGiftOrderEntity waiting = gifts.selectById(17L);
            assertThat(Duration.between(waiting.getLastFailedAt(), waiting.getNextExecuteAt()))
                    .isEqualTo(Duration.ofMinutes(expectedDelayMinutes[attempt]));
            if (attempt < expectedDelayMinutes.length - 1) {
                dueGift();
            }
        }
        verify(client, never()).queryUserBalance(any());
        when(sessions.findAvailableSession(7L)).thenReturn(session());
        refreshSessionAfterCommit();
        PointGiftOrderEntity awakened = gifts.selectById(17L);
        assertThat(awakened.getLastFailedAt()).isNull();
        assertThat(awakened.getNextExecuteAt()).isBeforeOrEqualTo(LocalDateTime.now());
        assertThat(awakened.getLastErrorCode()).isEqualTo(WechatVirtualPaymentErrorType.DUPLICATE_SUCCESS.name());
        when(client.queryUserBalance(any())).thenThrow(new IllegalStateException("补查临时失败"));
        giftProcessor.process(17L);
        assertGiftAwaitingConfirmation();
        dueGift();
        doReturn(balance(120L, 20L)).when(client).queryUserBalance(any());

        giftProcessor.process(17L);
        assertThat(giftProcessor.process(17L)).isEqualTo(TaskExecutionOutcome.SKIPPED_NOT_CLAIMABLE);

        assertThat(gifts.selectById(17L).getStatus()).isEqualTo(PointGiftOrderStatusDict.SUCCEEDED.getCode());
        assertThat(value("SELECT total_gifted FROM wf_point_account")).isEqualTo(20L);
        assertThat(value("SELECT COUNT(*) FROM wf_point_transaction")).isEqualTo(1L);
        verify(client).presentCurrency(any());
    }

    /** 成功事实在执行器中断、租约接管及等待会话时保持，旧令牌不能改写新执行。 */
    @Test
    void leaseTakeoverShouldKeepConfirmedSuccessAndRejectOldExecution() {
        insertDebit();
        PointDebitTaskEntity first = debitTransactions.tryClaim(23L, "first-token");
        assertThat(first).isNotNull();
        jdbc.update("UPDATE wf_point_debit_task SET request_amount = 10 WHERE id = 23");
        debitTransactions.recordRemoteSuccess(23L, "first-token", new WechatVirtualPaymentResult(
                0, null, WechatVirtualPaymentErrorType.SUCCESS, 90L, 0L, 3L, null, 0L, 0L, 200));
        jdbc.update("UPDATE wf_point_debit_task SET lease_until = ? WHERE id = 23", LocalDateTime.now().minusMinutes(1));
        PointDebitTaskEntity reclaimed = debitTransactions.tryClaim(23L, "replacement-token");
        assertThat(reclaimed.getLastErrorCode()).isEqualTo(WechatVirtualPaymentErrorType.SUCCESS.name());
        assertThat(reclaimed.getUsedPresentAmount()).isEqualTo(3L);
        assertThatThrownBy(() -> debitTransactions.recordRemoteSuccess(23L, "first-token", balance(0L, 0L)))
                .isInstanceOf(ExecutionLeaseLostException.class);
        debitTransactions.markWaitingSession(23L, "replacement-token", "等待会话");
        PointDebitTaskEntity waiting = debitTasks.selectById(23L);
        assertThat(waiting.getStatus()).isEqualTo(PointDebitTaskStatusDict.WAITING_SESSION.getCode());
        assertThat(waiting.getLastErrorCode()).isEqualTo(WechatVirtualPaymentErrorType.SUCCESS.name());
        assertThat(waiting.getRetryCount()).isEqualTo(1);
        assertThat(debitProcessor.process(23L)).isEqualTo(TaskExecutionOutcome.SKIPPED_NOT_CLAIMABLE);
        new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
            events.publishEvent(new MaintainerWechatSessionAvailableEvent(7L));
            assertThat(debitTasks.selectById(23L).getStatus())
                    .isEqualTo(PointDebitTaskStatusDict.WAITING_SESSION.getCode());
        });
        assertThat(debitTasks.selectById(23L).getLastFailedAt()).isNull();
        assertThat(debitTasks.selectById(23L).getStatus()).isEqualTo(PointDebitTaskStatusDict.WAITING.getCode());
        dueDebit();
        doReturn(balance(90L, 27L)).when(client).queryUserBalance(any());
        debitProcessor.process(23L);
        verify(client, never()).currencyPay(any());
        assertThat(debitTasks.selectById(23L).getStatus()).isEqualTo(PointDebitTaskStatusDict.SUCCEEDED.getCode());
    }

    /** 赠送成功事实同样跨租约接管保留，旧执行不能重复累计赠送。 */
    @Test
    void giftLeaseTakeoverShouldOnlyConfirmBalance() {
        insertGift();
        assertThat(giftTransactions.tryClaim(17L, "first-token")).isNotNull();
        giftTransactions.recordDuplicateSuccess(17L, "first-token");
        jdbc.update("UPDATE wf_point_gift_order SET lease_until = ? WHERE id = 17", LocalDateTime.now().minusMinutes(1));
        assertThat(giftTransactions.tryClaim(17L, "replacement-token").getLastErrorCode())
                .isEqualTo(WechatVirtualPaymentErrorType.DUPLICATE_SUCCESS.name());
        assertThatThrownBy(() -> giftTransactions.recordDuplicateSuccess(17L, "first-token"))
                .isInstanceOf(ExecutionLeaseLostException.class);
        giftTransactions.markFailure(17L, "replacement-token", new WechatVirtualPaymentResult(
                null, "临时失败", WechatVirtualPaymentErrorType.TRANSIENT, 0L, 0L, 0L, null, 0L, 0L, 200));
        assertGiftAwaitingConfirmation();
        dueGift();
        doReturn(balance(120L, 20L)).when(client).queryUserBalance(any());
        giftProcessor.process(17L);
        verify(client, never()).presentCurrency(any());
        assertThat(value("SELECT total_gifted FROM wf_point_account")).isEqualTo(20L);
    }

    /** 成功后的永久错误停止扣币补查，人工恢复只查余额且采用包括期间充值的新快照。 */
    @ParameterizedTest
    @EnumSource(value = WechatVirtualPaymentErrorType.class, names = {"PERMANENT", "CONFIGURATION"})
    void debitPermanentConfirmationFailureShouldRequireManualRecovery(WechatVirtualPaymentErrorType errorType) {
        insertDebit();
        when(client.currencyPay(any())).thenReturn(new WechatVirtualPaymentResult(
                0, null, WechatVirtualPaymentErrorType.SUCCESS, 90L, 0L, 3L, null, 0L, 0L, 200));
        when(client.queryUserBalance(any())).thenReturn(balance(100L, 30L), failure(errorType));

        debitProcessor.process(23L);

        PointDebitTaskEntity failed = debitTasks.selectById(23L);
        assertThat(failed.getStatus()).isEqualTo(PointDebitTaskStatusDict.FAILED.getCode());
        assertThat(failed.getLastErrorCode()).isEqualTo(WechatVirtualPaymentErrorType.SUCCESS.name());
        assertThat(failed.getRetryCount()).isEqualTo(1);
        assertThat(failed.getRequestAmount()).isEqualTo(10L);
        assertThat(failed.getWechatBalanceAfter()).isEqualTo(90L);
        assertThat(failed.getActiveFlag()).isEqualTo(1);
        assertThat(value("SELECT pending_debit FROM wf_point_account")).isEqualTo(10L);
        assertThat(debitProcessor.process(23L)).isEqualTo(TaskExecutionOutcome.SKIPPED_NOT_CLAIMABLE);
        assertThat(debitTransactions.resetForManualRetry(23L)).isTrue();
        assertThat(debitTasks.selectById(23L).getLastFailedAt()).isNull();
        assertThat(debitTasks.selectById(23L).getRetryCount()).isEqualTo(1);
        when(client.queryUserBalance(any())).thenReturn(balance(120L, 27L));

        debitProcessor.process(23L);

        assertThat(debitTasks.selectById(23L).getStatus()).isEqualTo(PointDebitTaskStatusDict.SUCCEEDED.getCode());
        assertThat(debitTasks.selectById(23L).getWechatBalanceAfter()).isEqualTo(120L);
        assertThat(value("SELECT balance FROM wf_point_account")).isEqualTo(120L);
        assertThat(value("SELECT pending_debit FROM wf_point_account")).isZero();
        verify(client).currencyPay(any());
    }

    /** 赠送永久错误保持成功事实并停止补查，人工恢复不再次赠送或重复记账。 */
    @ParameterizedTest
    @EnumSource(value = WechatVirtualPaymentErrorType.class, names = {"PERMANENT", "CONFIGURATION"})
    void giftPermanentConfirmationFailureShouldRequireManualRecovery(WechatVirtualPaymentErrorType errorType) {
        insertGift();
        when(client.presentCurrency(any())).thenReturn(new WechatVirtualPaymentResult(
                268490004, null, WechatVirtualPaymentErrorType.DUPLICATE_SUCCESS,
                0L, 0L, 0L, null, 0L, 0L, 200));
        when(client.queryUserBalance(any())).thenReturn(failure(errorType));

        giftProcessor.process(17L);

        PointGiftOrderEntity failed = gifts.selectById(17L);
        assertThat(failed.getStatus()).isEqualTo(PointGiftOrderStatusDict.FAILED.getCode());
        assertThat(failed.getLastErrorCode()).isEqualTo(WechatVirtualPaymentErrorType.DUPLICATE_SUCCESS.name());
        assertThat(failed.getRetryCount()).isEqualTo(1);
        assertThat(failed.getAmount()).isEqualTo(20L);
        assertThat(value("SELECT total_gifted FROM wf_point_account")).isZero();
        assertThat(giftProcessor.process(17L)).isEqualTo(TaskExecutionOutcome.SKIPPED_NOT_CLAIMABLE);
        assertThat(giftTransactions.resetForManualRetry(17L)).isTrue();
        assertThat(gifts.selectById(17L).getLastFailedAt()).isNull();
        assertThat(gifts.selectById(17L).getRetryCount()).isEqualTo(1);
        when(client.queryUserBalance(any())).thenReturn(balance(120L, 20L));

        giftProcessor.process(17L);

        assertThat(gifts.selectById(17L).getStatus()).isEqualTo(PointGiftOrderStatusDict.SUCCEEDED.getCode());
        assertThat(value("SELECT total_gifted FROM wf_point_account")).isEqualTo(20L);
        assertThat(value("SELECT COUNT(*) FROM wf_point_transaction")).isEqualTo(1L);
        verify(client).presentCurrency(any());
    }

    /** 扣币成功后身份缺失不无限轮询，恢复身份并人工重试时仍只查询余额。 */
    @Test
    void debitMissingIdentityShouldStopUntilManualRecovery() {
        insertDebit();
        assertThat(debitTransactions.tryClaim(23L, "first-token")).isNotNull();
        jdbc.update("UPDATE wf_point_debit_task SET request_amount = 10 WHERE id = 23");
        debitTransactions.recordRemoteSuccess(23L, "first-token", new WechatVirtualPaymentResult(
                0, null, WechatVirtualPaymentErrorType.SUCCESS, 90L, 0L, 3L, null, 0L, 0L, 200));
        debitTransactions.markFailure(23L, "first-token", failure(WechatVirtualPaymentErrorType.TRANSIENT));
        dueDebit();
        UserAuthEntity originalAuth = auths.selectById(51L);
        when(auths.selectById(51L)).thenReturn(null);

        debitProcessor.process(23L);

        assertThat(debitTasks.selectById(23L).getStatus()).isEqualTo(PointDebitTaskStatusDict.FAILED.getCode());
        assertThat(debitTasks.selectById(23L).getLastErrorCode()).isEqualTo(WechatVirtualPaymentErrorType.SUCCESS.name());
        assertThat(debitTasks.selectById(23L).getActiveFlag()).isEqualTo(1);
        verify(client, never()).queryUserBalance(any());
        when(auths.selectById(51L)).thenReturn(originalAuth);
        when(client.queryUserBalance(any())).thenReturn(balance(90L, 27L));
        assertThat(debitTransactions.resetForManualRetry(23L)).isTrue();
        debitProcessor.process(23L);

        assertThat(debitTasks.selectById(23L).getStatus()).isEqualTo(PointDebitTaskStatusDict.SUCCEEDED.getCode());
        verify(client, never()).currencyPay(any());
    }

    /** 已成功赠送后身份缺失同样停止轮询，人工恢复后只查余额并记账一次。 */
    @Test
    void giftMissingIdentityShouldStopUntilManualRecovery() {
        insertGift();
        assertThat(giftTransactions.tryClaim(17L, "first-token")).isNotNull();
        giftTransactions.recordDuplicateSuccess(17L, "first-token");
        giftTransactions.markFailure(17L, "first-token", failure(WechatVirtualPaymentErrorType.TRANSIENT));
        dueGift();
        UserAuthEntity originalAuth = auths.selectById(51L);
        when(auths.selectOne(any())).thenReturn(null);

        giftProcessor.process(17L);

        assertThat(gifts.selectById(17L).getStatus()).isEqualTo(PointGiftOrderStatusDict.FAILED.getCode());
        assertThat(gifts.selectById(17L).getLastErrorCode()).isEqualTo(WechatVirtualPaymentErrorType.DUPLICATE_SUCCESS.name());
        verify(client, never()).queryUserBalance(any());
        when(auths.selectOne(any())).thenReturn(originalAuth);
        when(client.queryUserBalance(any())).thenReturn(balance(120L, 20L));
        assertThat(giftTransactions.resetForManualRetry(17L)).isTrue();
        giftProcessor.process(17L);

        assertThat(gifts.selectById(17L).getStatus()).isEqualTo(PointGiftOrderStatusDict.SUCCEEDED.getCode());
        assertThat(value("SELECT COUNT(*) FROM wf_point_transaction")).isEqualTo(1L);
        verify(client, never()).presentCurrency(any());
    }

    /** 新会话不抢占赠送有效租约，租约释放后可提前唤醒，退款同步失败也不阻断唤醒。 */
    @Test
    void sessionRefreshShouldOnlyWakeUnleasedConfirmedGift() {
        insertGift();
        assertThat(giftTransactions.tryClaim(17L, "first-token")).isNotNull();
        giftTransactions.recordDuplicateSuccess(17L, "first-token");
        giftTransactions.markFailure(17L, "first-token", failure(WechatVirtualPaymentErrorType.SESSION_INVALID));
        PointGiftOrderEntity waiting = gifts.selectById(17L);
        jdbc.update("UPDATE wf_point_gift_order SET execution_lease_token = ?, lease_until = ? WHERE id = 17",
                "active-token", LocalDateTime.now().plusMinutes(2));
        doThrow(new IllegalStateException("退款同步异常")).when(balanceSync).synchronizeStaleBalanceForUser(7L);

        refreshSessionAfterCommit();

        assertThat(gifts.selectById(17L).getNextExecuteAt()).isEqualTo(waiting.getNextExecuteAt());
        assertThat(gifts.selectById(17L).getExecutionLeaseToken()).isEqualTo("active-token");
        jdbc.update("UPDATE wf_point_gift_order SET lease_until = ? WHERE id = 17", LocalDateTime.now().minusMinutes(1));
        refreshSessionAfterCommit();
        assertThat(gifts.selectById(17L).getNextExecuteAt()).isBeforeOrEqualTo(LocalDateTime.now());
        assertThat(gifts.selectById(17L).getExecutionLeaseToken()).isNull();
        assertThat(gifts.selectById(17L).getLastFailedAt()).isNull();
        assertThat(gifts.selectById(17L).getRetryCount()).isEqualTo(1);
        assertThat(gifts.selectById(17L).getLastErrorCode()).isEqualTo(WechatVirtualPaymentErrorType.DUPLICATE_SUCCESS.name());
    }

    /** 可控的补查失败分类。 */
    private WechatVirtualPaymentResult failure(WechatVirtualPaymentErrorType errorType) {
        return new WechatVirtualPaymentResult(null, "余额补查失败", errorType,
                0L, 0L, 0L, null, 0L, 0L, 200);
    }

    /** 模拟会话事务提交后的真实事件，由监听器通过独立事务提交任务唤醒。 */
    private void refreshSessionAfterCommit() {
        new TransactionTemplate(transactionManager).executeWithoutResult(status ->
                events.publishEvent(new MaintainerWechatSessionAvailableEvent(7L)));
    }

    /** 核对已到预算上限的原赠送仍在等待确认。 */
    private void assertGiftAwaitingConfirmation() {
        PointGiftOrderEntity order = gifts.selectById(17L);
        assertThat(order.getStatus()).isEqualTo(PointGiftOrderStatusDict.RETRY_WAIT.getCode());
        assertThat(order.getLastErrorCode()).isEqualTo(WechatVirtualPaymentErrorType.DUPLICATE_SUCCESS.name());
        assertThat(order.getRetryCount()).isEqualTo(1);
        assertThat(order.getOrderNo()).isEqualTo("GIFT-17");
        assertThat(order.getNextExecuteAt()).isAfter(LocalDateTime.now().plusSeconds(50));
    }

    /** 插入待扣 10 的账户和原任务。 */
    private void insertDebit() {
        insertAccount(90L, 10L);
        jdbc.update("""
                INSERT INTO wf_point_debit_task
                    (id, task_no, account_id, user_id, status, active_flag, retry_count, next_execute_at)
                VALUES (23, 'DEBIT-23', 10, 7, 'WAITING', 1, 1, ?)
                """, LocalDateTime.now().minusMinutes(1));
        jdbc.update("""
                INSERT INTO wf_point_pending_debit
                    (id, account_id, user_id, point_transaction_id, original_amount, remaining_amount)
                VALUES (1, 10, 7, 1, 10, 10)
                """);
    }

    /** 插入原赠送订单，不改变微信订单号。 */
    private void insertGift() {
        insertAccount(100L, 0L);
        jdbc.update("""
                INSERT INTO wf_point_gift_order
                    (id, order_no, account_id, user_id, scene_code, amount, business_type, business_id,
                     business_snapshot, idempotency_key, status, retry_count, next_execute_at)
                VALUES (17, 'GIFT-17', 10, 7, 'MANUAL_ADMIN_GRANT', 20, 'TEST', '1', '{}', 'gift-key', 'READY', 1, ?)
                """, LocalDateTime.now().minusMinutes(1));
    }

    /** 插入共同账户。 */
    private void insertAccount(long balance, long pending) {
        jdbc.update("""
                INSERT INTO wf_point_account
                    (id, user_id, balance, wechat_balance, wechat_present_balance, pending_debit,
                     total_recharged, total_gifted, total_consumed)
                VALUES (10, 7, ?, 100, 30, ?, 100, 0, 0)
                """, balance, pending);
    }

    /** 平移两时间字段使下一轮到期，保留实际持久化的退避间隔。 */
    private void dueDebit() {
        PointDebitTaskEntity task = debitTasks.selectById(23L);
        LocalDateTime dueAt = LocalDateTime.now().minusMinutes(1);
        LocalDateTime failedAt = task.getLastFailedAt() == null ? null
                : dueAt.minus(Duration.between(task.getLastFailedAt(), task.getNextExecuteAt()));
        jdbc.update("UPDATE wf_point_debit_task SET next_execute_at = ?, last_failed_at = ? WHERE id = 23",
                dueAt, failedAt);
    }

    /** 平移赠送两时间字段使下一轮到期，保留实际持久化的退避间隔。 */
    private void dueGift() {
        PointGiftOrderEntity order = gifts.selectById(17L);
        LocalDateTime dueAt = LocalDateTime.now().minusMinutes(1);
        LocalDateTime failedAt = order.getLastFailedAt() == null ? null
                : dueAt.minus(Duration.between(order.getLastFailedAt(), order.getNextExecuteAt()));
        jdbc.update("UPDATE wf_point_gift_order SET next_execute_at = ?, last_failed_at = ? WHERE id = 17",
                dueAt, failedAt);
    }

    /** 有效测试会话。 */
    private MaintainerWechatSession session() {
        return new MaintainerWechatSession(7L, 51L, "session-test", 1L, "127.0.0.1");
    }

    /** 完整余额查询成功应答。 */
    private WechatVirtualPaymentResult balance(long total, long present) {
        return new WechatVirtualPaymentResult(0, null, WechatVirtualPaymentErrorType.SUCCESS,
                total, present, 0L, null, 0L, 0L, 200);
    }

    /** 核对实际持久化聚合值。 */
    private Long value(String sql) {
        return jdbc.queryForObject(sql, Long.class);
    }

    /** 创建实际 Mapper 使用的表结构，保持既有非空赠送消耗列。 */
    private void createTables() {
        jdbc.execute("""
                CREATE TABLE wf_point_account (
                  id BIGINT AUTO_INCREMENT PRIMARY KEY, user_id BIGINT, balance BIGINT, wechat_balance BIGINT,
                  wechat_present_balance BIGINT, pending_debit BIGINT, wechat_balance_synced_at TIMESTAMP,
                  total_recharged BIGINT, total_gifted BIGINT, total_consumed BIGINT,
                  created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP, updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
                  deleted BIGINT DEFAULT 0, version INT DEFAULT 0)
                """);
        jdbc.execute("""
                CREATE TABLE wf_point_debit_task (
                  id BIGINT AUTO_INCREMENT PRIMARY KEY, task_no VARCHAR(64), account_id BIGINT, user_id BIGINT,
                  status VARCHAR(32), active_flag INT, request_amount BIGINT, settled_amount BIGINT DEFAULT 0,
                  used_present_amount BIGINT NOT NULL DEFAULT 0, pending_before BIGINT, pending_after BIGINT,
                  retry_count INT, next_execute_at TIMESTAMP, lease_owner VARCHAR(64), execution_lease_token VARCHAR(64),
                  lease_until TIMESTAMP, session_version BIGINT, wechat_balance_before BIGINT, wechat_balance_after BIGINT,
                  last_error_code VARCHAR(64), last_error_message VARCHAR(255), last_failed_at TIMESTAMP,
                  started_at TIMESTAMP, completed_at TIMESTAMP,
                  created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP, updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
                  deleted BIGINT DEFAULT 0, version INT DEFAULT 0)
                """);
        jdbc.execute("""
                CREATE TABLE wf_point_gift_order (
                  id BIGINT AUTO_INCREMENT PRIMARY KEY, order_no VARCHAR(64), account_id BIGINT, user_id BIGINT,
                  scene_code VARCHAR(64), amount BIGINT, business_type VARCHAR(32), business_id VARCHAR(64),
                  business_snapshot VARCHAR(1024), idempotency_key VARCHAR(128), status VARCHAR(32), retry_count INT,
                  next_execute_at TIMESTAMP, lease_owner VARCHAR(64), execution_lease_token VARCHAR(64), lease_until TIMESTAMP,
                  point_transaction_id BIGINT, wechat_balance_after BIGINT, wechat_present_balance_after BIGINT,
                  last_error_code VARCHAR(64), last_error_message VARCHAR(255), last_failed_at TIMESTAMP, completed_at TIMESTAMP,
                  created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP, updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
                  deleted BIGINT DEFAULT 0, version INT DEFAULT 0)
                """);
        jdbc.execute("""
                CREATE TABLE wf_point_transaction (
                  id BIGINT AUTO_INCREMENT PRIMARY KEY, account_id BIGINT, user_id BIGINT, rule_id BIGINT,
                  transaction_type VARCHAR(32), scene_code VARCHAR(64), points_change BIGINT,
                  balance_before BIGINT, balance_after BIGINT, business_type VARCHAR(32), business_id VARCHAR(64),
                  calculation_snapshot VARCHAR(1024), idempotency_key VARCHAR(128) UNIQUE, remark VARCHAR(255), occurred_at TIMESTAMP,
                  created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP, updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
                  deleted BIGINT DEFAULT 0, version INT DEFAULT 0)
                """);
        jdbc.execute("""
                CREATE TABLE wf_point_pending_debit (
                  id BIGINT AUTO_INCREMENT PRIMARY KEY, account_id BIGINT, user_id BIGINT, point_transaction_id BIGINT,
                  original_amount BIGINT, remaining_amount BIGINT, last_settled_at TIMESTAMP,
                  created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP, updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
                  deleted BIGINT DEFAULT 0, version INT DEFAULT 0)
                """);
    }
}
