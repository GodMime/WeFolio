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
import org.mockito.InOrder;
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
@ExtendWith(MockitoExtension.class)
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
        when(virtualPaymentClient.queryUserBalance(any())).thenReturn(balance);
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
        order.verify(debitTransactionService).completeSuccess(23L, EXECUTION_LEASE_TOKEN, paid);
    }

    /** 构造赠送处理器。 */
    private PointGiftOrderProcessor giftProcessor() {
        return new PointGiftOrderProcessor(
                giftTransactionService, virtualPaymentClient, userAuthEntityMapper,
                userPointMutex, tokenGenerator);
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
