package com.jxc.wefolio.service.payment;

import com.jxc.wefolio.entity.PointAccountEntity;
import com.jxc.wefolio.entity.PointDebitTaskEntity;
import com.jxc.wefolio.entity.PointGiftOrderEntity;
import com.jxc.wefolio.entity.UserAuthEntity;
import com.jxc.wefolio.mapper.PointGiftOrderEntityMapper;
import com.jxc.wefolio.mapper.UserAuthEntityMapper;
import com.jxc.wefolio.service.point.UserPointMutex;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.mockito.InOrder;
import org.mockito.ArgumentCaptor;
import static org.mockito.ArgumentMatchers.eq;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 单条微信虚拟支付任务处理器领取测试。
 */
@ExtendWith({MockitoExtension.class, OutputCaptureExtension.class})
class PointTaskProcessorClaimTest {

    /** 固定测试执行租约令牌。 */
    private static final String EXECUTION_LEASE_TOKEN = "0123456789ABCDEF0123456789ABCDEF";

    @Mock
    private ExecutionLeaseTokenGenerator tokenGenerator;

    @Mock
    private PointGiftOrderTransactionService giftTransactionService;

    @Mock
    private PointDebitTaskTransactionService debitTransactionService;

    @Mock
    private WechatVirtualPaymentClient virtualPaymentClient;

    @Mock
    private MaintainerWechatSessionService sessionService;

    @Mock
    private UserAuthEntityMapper userAuthEntityMapper;

    @Mock
    private UserPointMutex userPointMutex;

    @Mock
    private PointGiftOrderEntityMapper giftOrderMapper;

    @Mock
    private PointGiftOrderProcessor giftOrderProcessor;

    @BeforeEach
    void setUp() {
        when(tokenGenerator.generate()).thenReturn(EXECUTION_LEASE_TOKEN);
    }

    /** 赠送订单未领取成功时不得访问微信。 */
    @Test
    void giftProcessorShouldSkipUnclaimableOrderWithoutRemoteCall() {
        when(giftTransactionService.tryClaim(17L, EXECUTION_LEASE_TOKEN)).thenReturn(null);

        TaskExecutionOutcome outcome = giftProcessor().process(17L);

        assertThat(outcome).isEqualTo(TaskExecutionOutcome.SKIPPED_NOT_CLAIMABLE);
        verify(tokenGenerator).generate();
        verify(virtualPaymentClient, never()).presentCurrency(any());
    }

    /** 赠送远端调用前续租失败时必须停止，不得访问微信。 */
    @Test
    void giftProcessorShouldStopWhenLeaseRenewalFails() {
        executeMutexAction();
        PointGiftOrderEntity order = giftOrder();
        UserAuthEntity auth = new UserAuthEntity();
        auth.setOpenId("openid-17");
        when(giftTransactionService.tryClaim(17L, EXECUTION_LEASE_TOKEN)).thenReturn(order);
        when(userAuthEntityMapper.selectOne(any())).thenReturn(auth);
        doThrow(new ExecutionLeaseLostException("租约失效"))
                .when(giftTransactionService).renewLease(17L, EXECUTION_LEASE_TOKEN);

        TaskExecutionOutcome outcome = giftProcessor().process(17L);

        assertThat(outcome).isEqualTo(TaskExecutionOutcome.PROCESSED);
        verify(virtualPaymentClient, never()).presentCurrency(any());
    }

    /** 扣币任务未领取成功时不得查询微信余额。 */
    @Test
    void debitProcessorShouldSkipUnclaimableTaskWithoutRemoteCall() {
        when(debitTransactionService.tryClaim(23L, EXECUTION_LEASE_TOKEN)).thenReturn(null);

        TaskExecutionOutcome outcome = debitProcessor().process(23L);

        assertThat(outcome).isEqualTo(TaskExecutionOutcome.SKIPPED_NOT_CLAIMABLE);
        verify(virtualPaymentClient, never()).queryUserBalance(any());
        verify(virtualPaymentClient, never()).currencyPay(any());
    }

    /** 扣币的余额查询和支付前必须分别续租，并始终传递同一令牌。 */
    @Test
    void debitProcessorShouldRenewBeforeEachRemoteCallWithSameToken() {
        executeMutexAction();
        PointDebitTaskEntity task = debitTask();
        PointAccountEntity account = new PointAccountEntity();
        account.setId(41L);
        account.setPendingDebit(100L);
        MaintainerWechatSession session = new MaintainerWechatSession(
                7L, 51L, "session-key", 3L, "127.0.0.1");
        UserAuthEntity auth = new UserAuthEntity();
        auth.setId(51L);
        auth.setUserId(7L);
        auth.setOpenId("openid-7");
        PointDebitTaskEntity prepared = debitTask();
        prepared.setRequestAmount(100L);
        WechatVirtualPaymentResult balance = successResult(500L, 200L);
        WechatVirtualPaymentResult paid = successResult(400L, 100L);

        when(debitTransactionService.tryClaim(23L, EXECUTION_LEASE_TOKEN)).thenReturn(task);
        when(giftOrderMapper.selectOne(any())).thenReturn(null);
        when(sessionService.findAvailableSession(7L)).thenReturn(session);
        when(userAuthEntityMapper.selectById(51L)).thenReturn(auth);
        when(virtualPaymentClient.queryUserBalance(any())).thenReturn(balance, paid);
        when(debitTransactionService.syncBalance(
                23L, EXECUTION_LEASE_TOKEN, 500L, 200L)).thenReturn(account);
        when(debitTransactionService.prepareRequest(
                23L, EXECUTION_LEASE_TOKEN, 100L, 100L, 500L, 3L)).thenReturn(prepared);
        when(virtualPaymentClient.currencyPay(any())).thenReturn(paid);

        TaskExecutionOutcome outcome = debitProcessor().process(23L);

        assertThat(outcome).isEqualTo(TaskExecutionOutcome.PROCESSED);
        InOrder order = inOrder(tokenGenerator, debitTransactionService, virtualPaymentClient);
        order.verify(tokenGenerator).generate();
        order.verify(debitTransactionService).tryClaim(23L, EXECUTION_LEASE_TOKEN);
        order.verify(debitTransactionService).renewLease(23L, EXECUTION_LEASE_TOKEN);
        order.verify(virtualPaymentClient).queryUserBalance(any());
        order.verify(debitTransactionService).syncBalance(
                23L, EXECUTION_LEASE_TOKEN, 500L, 200L);
        order.verify(debitTransactionService).prepareRequest(
                23L, EXECUTION_LEASE_TOKEN, 100L, 100L, 500L, 3L);
        order.verify(debitTransactionService).renewLease(23L, EXECUTION_LEASE_TOKEN);
        order.verify(virtualPaymentClient).currencyPay(any());
        order.verify(debitTransactionService).recordRemoteSuccess(23L, EXECUTION_LEASE_TOKEN, paid);
        order.verify(debitTransactionService).renewLease(23L, EXECUTION_LEASE_TOKEN);
        order.verify(virtualPaymentClient).queryUserBalance(any());
        ArgumentCaptor<WechatVirtualPaymentResult> settled = ArgumentCaptor.forClass(WechatVirtualPaymentResult.class);
        order.verify(debitTransactionService).completeSuccess(eq(23L), eq(EXECUTION_LEASE_TOKEN), settled.capture());
        assertThat(settled.getValue().balance()).isEqualTo(400L);
        assertThat(settled.getValue().presentBalance()).isEqualTo(100L);
    }

    /** 微信重复赠送没有余额字段，必须补查后使用权威快照结算。 */
    @Test
    void giftDuplicateShouldQueryBalanceBeforeSettlement() {
        prepareDuplicateGift();
        when(sessionService.findAvailableSession(7L)).thenReturn(session());
        when(virtualPaymentClient.queryUserBalance(any())).thenReturn(successResult(120L, 20L));

        giftProcessor().process(17L);

        var captor = ArgumentCaptor.forClass(WechatVirtualPaymentResult.class);
        verify(giftTransactionService).completeSuccess(eq(17L),
                eq(EXECUTION_LEASE_TOKEN), captor.capture());
        assertThat(captor.getValue().errorType()).isEqualTo(WechatVirtualPaymentErrorType.DUPLICATE_SUCCESS);
        assertThat(captor.getValue().balance()).isEqualTo(120L);
        assertThat(captor.getValue().presentBalance()).isEqualTo(20L);
    }

    /** 重复赠送没有会话时不得将零余额结算，原订单保留用于恢复。 */
    @Test
    void giftDuplicateWithoutSessionShouldRemainRetryable() {
        prepareDuplicateGift();

        giftProcessor().process(17L);

        verify(giftTransactionService, never()).completeSuccess(any(), any(), any());
        verify(virtualPaymentClient, never()).queryUserBalance(any());
        var captor = ArgumentCaptor.forClass(WechatVirtualPaymentResult.class);
        verify(giftTransactionService).markFailure(eq(17L),
                eq(EXECUTION_LEASE_TOKEN), captor.capture());
        assertThat(captor.getValue().errorType()).isEqualTo(WechatVirtualPaymentErrorType.SESSION_INVALID);
    }

    /** 重复扣币使用补查余额，沿用第一次持久化的请求金额。 */
    @Test
    void debitDuplicateShouldSettleOriginalRequestWithQueriedBalance() {
        PointDebitTaskEntity prepared = prepareDuplicateDebit();
        when(virtualPaymentClient.queryUserBalance(any()))
                .thenReturn(successResult(400L, 100L), successResult(400L, 100L));

        debitProcessor().process(23L);

        var captor = ArgumentCaptor.forClass(WechatVirtualPaymentResult.class);
        verify(debitTransactionService).completeSuccess(eq(23L),
                eq(EXECUTION_LEASE_TOKEN), captor.capture());
        assertThat(captor.getValue().balance()).isEqualTo(400L);
        assertThat(captor.getValue().errorType()).isEqualTo(WechatVirtualPaymentErrorType.DUPLICATE_SUCCESS);
        var pay = ArgumentCaptor.forClass(WechatCurrencyPayRequest.class);
        verify(virtualPaymentClient).currencyPay(pay.capture());
        assertThat(pay.getValue().amount()).isEqualTo(prepared.getRequestAmount());
        assertThat(pay.getValue().orderId()).isEqualTo(prepared.getTaskNo());
    }

    /** 重复扣币补查失败不能清空请求或按余额不足释放任务。 */
    @Test
    void debitDuplicateQueryFailureShouldPreserveRecoverableTask() {
        prepareDuplicateDebit();
        when(virtualPaymentClient.queryUserBalance(any())).thenReturn(successResult(400L, 100L),
                new WechatVirtualPaymentResult(268490006, "余额查询失败",
                        WechatVirtualPaymentErrorType.INSUFFICIENT_BALANCE,
                        0L, 0L, 0L, null, 0L, 0L, 200));

        debitProcessor().process(23L);

        verify(debitTransactionService, never()).completeSuccess(any(), any(), any());
        verify(debitTransactionService, never()).markNoBalance(any(), any(), anyLong());
        var captor = ArgumentCaptor.forClass(WechatVirtualPaymentResult.class);
        verify(debitTransactionService).markFailure(eq(23L),
                eq(EXECUTION_LEASE_TOKEN), captor.capture());
        assertThat(captor.getValue().errorType()).isEqualTo(WechatVirtualPaymentErrorType.INSUFFICIENT_BALANCE);
    }

    /** 成功后的会话失效交给事件唤醒，不按普通余额失败继续轮询。 */
    @Test
    void confirmedDebitWithExpiredSessionShouldWaitForSessionRefresh() {
        prepareConfirmedDebit();
        when(virtualPaymentClient.queryUserBalance(any())).thenReturn(new WechatVirtualPaymentResult(
                268490009, "会话失效", WechatVirtualPaymentErrorType.SESSION_INVALID,
                0L, 0L, 0L, null, 0L, 0L, 200));

        debitProcessor().process(23L);

        verify(sessionService).invalidateVersion(7L, 3L, "会话失效");
        verify(debitTransactionService).markWaitingSession(23L, EXECUTION_LEASE_TOKEN, "会话失效");
        verify(debitTransactionService, never()).markFailure(any(), any(), any());
        verify(debitTransactionService, never()).completeSuccess(any(), any(), any());
        verify(virtualPaymentClient, never()).currencyPay(any());
    }

    /** 完整余额可能因充值或退款增减，保留同一快照并记录与扣币应答的差异。 */
    @ParameterizedTest
    @ValueSource(longs = {350L, 450L})
    void confirmedDebitShouldReportChangedBalanceWithoutMixingSnapshots(long queriedBalance, CapturedOutput output) {
        prepareConfirmedDebit();
        when(virtualPaymentClient.queryUserBalance(any())).thenReturn(successResult(queriedBalance, 100L));

        debitProcessor().process(23L);

        var captor = ArgumentCaptor.forClass(WechatVirtualPaymentResult.class);
        verify(debitTransactionService).completeSuccess(eq(23L), eq(EXECUTION_LEASE_TOKEN), captor.capture());
        assertThat(captor.getValue().balance()).isEqualTo(queriedBalance);
        assertThat(captor.getValue().presentBalance()).isEqualTo(100L);
        assertThat(output).contains("event=WECHAT_DEBIT_BALANCE_SNAPSHOT_CHANGED taskId=23 payBalance=400 queriedBalance="
                + queriedBalance);
        verify(virtualPaymentClient, never()).currencyPay(any());
    }

    /** 模拟重新领取已持久保存普通扣币成功及应答余额的任务。 */
    private void prepareConfirmedDebit() {
        executeMutexAction();
        PointDebitTaskEntity task = debitTask();
        task.setRequestAmount(100L);
        task.setLastErrorCode(WechatVirtualPaymentErrorType.SUCCESS.name());
        task.setWechatBalanceAfter(400L);
        when(debitTransactionService.tryClaim(23L, EXECUTION_LEASE_TOKEN)).thenReturn(task);
        when(sessionService.findAvailableSession(7L)).thenReturn(session());
        UserAuthEntity auth = new UserAuthEntity();
        auth.setId(51L);
        auth.setUserId(7L);
        auth.setOpenId("openid-7");
        when(userAuthEntityMapper.selectById(51L)).thenReturn(auth);
    }

    /** 准备重复赠送响应和不包含余额的原应答。 */
    private void prepareDuplicateGift() {
        executeMutexAction();
        when(giftTransactionService.tryClaim(17L, EXECUTION_LEASE_TOKEN)).thenReturn(giftOrder());
        UserAuthEntity auth = new UserAuthEntity();
        auth.setOpenId("openid-7");
        when(userAuthEntityMapper.selectOne(any())).thenReturn(auth);
        when(virtualPaymentClient.presentCurrency(any())).thenReturn(duplicateResult());
    }

    /** 准备已经冻结金额的扣币重试任务。 */
    private PointDebitTaskEntity prepareDuplicateDebit() {
        executeMutexAction();
        PointDebitTaskEntity prepared = debitTask();
        prepared.setRequestAmount(100L);
        prepared.setPendingBefore(100L);
        when(debitTransactionService.tryClaim(23L, EXECUTION_LEASE_TOKEN)).thenReturn(prepared);
        when(sessionService.findAvailableSession(7L)).thenReturn(session());
        UserAuthEntity auth = new UserAuthEntity();
        auth.setId(51L);
        auth.setUserId(7L);
        auth.setOpenId("openid-7");
        when(userAuthEntityMapper.selectById(51L)).thenReturn(auth);
        PointAccountEntity account = new PointAccountEntity();
        account.setPendingDebit(100L);
        when(debitTransactionService.syncBalance(23L, EXECUTION_LEASE_TOKEN, 400L, 100L)).thenReturn(account);
        when(debitTransactionService.prepareRequest(23L, EXECUTION_LEASE_TOKEN, 100L, 100L, 400L, 3L))
                .thenReturn(prepared);
        when(virtualPaymentClient.currencyPay(any())).thenReturn(duplicateResult());
        return prepared;
    }

    /** 构造有效维护者会话。 */
    private MaintainerWechatSession session() {
        return new MaintainerWechatSession(7L, 51L, "session-key", 3L, "127.0.0.1");
    }

    /** 微信重复成功没有任何余额字段。 */
    private WechatVirtualPaymentResult duplicateResult() {
        return new WechatVirtualPaymentResult(268490004, null, WechatVirtualPaymentErrorType.DUPLICATE_SUCCESS,
                0L, 0L, 0L, null, 0L, 0L, 200);
    }

    /** 构造赠送处理器。 */
    private PointGiftOrderProcessor giftProcessor() {
        return new PointGiftOrderProcessor(
                giftTransactionService, virtualPaymentClient, userAuthEntityMapper,
                userPointMutex, tokenGenerator, sessionService);
    }

    /** 构造扣币处理器。 */
    private PointDebitTaskProcessor debitProcessor() {
        return new PointDebitTaskProcessor(
                debitTransactionService, virtualPaymentClient, sessionService,
                userAuthEntityMapper, userPointMutex, giftOrderMapper,
                giftOrderProcessor, tokenGenerator);
    }

    /** 让模拟互斥器在当前线程执行处理动作。 */
    private void executeMutexAction() {
        doAnswer(invocation -> invocation.<Supplier<?>>getArgument(1).get())
                .when(userPointMutex).execute(anyLong(), any());
    }

    /** 构造待赠送订单。 */
    private PointGiftOrderEntity giftOrder() {
        PointGiftOrderEntity order = new PointGiftOrderEntity();
        order.setId(17L);
        order.setOrderNo("GIFT-17");
        order.setUserId(7L);
        order.setAmount(20L);
        return order;
    }

    /** 构造已领取扣币任务。 */
    private PointDebitTaskEntity debitTask() {
        PointDebitTaskEntity task = new PointDebitTaskEntity();
        task.setId(23L);
        task.setTaskNo("DEBIT-23");
        task.setUserId(7L);
        task.setAccountId(41L);
        return task;
    }

    /** 构造微信成功结果。 */
    private WechatVirtualPaymentResult successResult(long balance, long presentBalance) {
        return new WechatVirtualPaymentResult(
                0, null, WechatVirtualPaymentErrorType.SUCCESS,
                balance, presentBalance, 0L, null, 0L, 0L, 0);
    }
}
