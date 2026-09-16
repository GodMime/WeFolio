package com.jxc.wefolio.service.payment;

import com.jxc.wefolio.config.WechatVirtualPaymentProperties;
import com.jxc.wefolio.dict.PaymentChannelDict;
import com.jxc.wefolio.dict.RechargeOrderStatusDict;
import com.jxc.wefolio.dto.RechargeOrderSyncResponse;
import com.jxc.wefolio.entity.RechargeOrderEntity;
import com.jxc.wefolio.mapper.RechargeOrderEntityMapper;
import com.jxc.wefolio.service.AdminPointSecretValidator;
import com.jxc.wefolio.service.point.UserPointMutex;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** 后台充值核对的到期判断、退避和旧客户端恢复测试。 */
@ExtendWith(MockitoExtension.class)
class RechargeOrderReconciliationServiceTest {
    /** 内部鉴权边界。 */
    @Mock private AdminPointSecretValidator secrets;
    /** 订单持久化边界。 */
    @Mock private RechargeOrderEntityMapper orders;
    /** 远端核对应用入口。 */
    @Mock private RechargeCommandService recharge;
    /** 独立调度事务。 */
    @Mock private RechargeOrderTransactionService transactions;
    /** 可用会话查询。 */
    @Mock private MaintainerWechatSessionService sessions;
    /** 同用户串行边界。 */
    @Mock private UserPointMutex mutex;
    /** 功能开关。 */
    private final WechatVirtualPaymentProperties properties = new WechatVirtualPaymentProperties();
    /** 被核对的真实订单数据。 */
    private RechargeOrderEntity order;
    /** 被测后台编排。 */
    private RechargeOrderReconciliationService service;

    /** 每次模拟一笔支付完成但尚未在本地入账的旧客户端订单。 */
    @BeforeEach
    void setUp() {
        properties.setEnabled(true);
        service = new RechargeOrderReconciliationService(secrets, properties, orders, recharge, transactions, sessions, mutex);
        order = new RechargeOrderEntity();
        order.setId(11L);
        order.setUserId(7L);
        order.setMerchantOrderNo("ORDER");
        order.setStatus(RechargeOrderStatusDict.CLOSED.getCode());
        order.setPayChannel(PaymentChannelDict.WECHAT_VIRTUAL_PAYMENT.getCode());
        order.setQueryRetryCount(0);
        lenient().when(orders.selectById(11L)).thenReturn(order);
        lenient().when(mutex.execute(eq(7L), any())).thenAnswer(i -> i.<Supplier<?>>getArgument(1).get());
        lenient().when(sessions.findAvailableSession(7L))
                .thenReturn(new MaintainerWechatSession(7L, 1L, "session", 1L, "127.0.0.1"));
    }

    /** 后台恢复已关闭订单后结束核对，不再为已入账订单安排退款扫描。 */
    @Test
    void closedOrderShouldRecoverAndClearQuerySchedule() {
        when(recharge.reconcileOrderWithinUserLock(7L, "ORDER")).thenReturn(response(RechargeOrderStatusDict.PAID));
        assertThat(service.reconcile("secret", 11L).outcome()).isEqualTo("SUCCEEDED");
        verify(transactions).recordQuerySchedule(11L, null, 0, null);
        verify(secrets).validate("secret");
    }

    /** 历史已入账订单即使保留到期核对时间，也不能继续查询微信或刷新调度计划。 */
    @Test
    void paidOrderShouldSkipEvenWithAnExistingDueSchedule() {
        order.setStatus(RechargeOrderStatusDict.PAID.getCode());
        order.setPaidFee(5000L);
        order.setNextQueryAt(LocalDateTime.now().minusDays(1));
        lenient().when(recharge.reconcileOrderWithinUserLock(7L, "ORDER"))
                .thenReturn(response(RechargeOrderStatusDict.PAID));

        assertThat(service.reconcile("secret", 11L).outcome()).isEqualTo("SKIPPED_TERMINAL");

        verifyNoInteractions(recharge, transactions, sessions, mutex);
    }

    /** 扫描后被通知结算的订单在锁内重读时退出，避免执行已经过时的候选。 */
    @Test
    void candidatePaidBeforeLockAcquisitionShouldSkip() {
        lenient().when(recharge.reconcileOrderWithinUserLock(7L, "ORDER"))
                .thenReturn(response(RechargeOrderStatusDict.PAID));
        when(mutex.execute(eq(7L), any())).thenAnswer(invocation -> {
            order.setStatus(RechargeOrderStatusDict.PAID.getCode());
            return invocation.<Supplier<?>>getArgument(1).get();
        });

        assertThat(service.reconcile("secret", 11L).outcome()).isEqualTo("SKIPPED_TERMINAL");

        verifyNoInteractions(recharge, transactions, sessions);
    }

    /** 无会话时保留原状态等待新会话，不能把订单改成支付失败。 */
    @Test
    void missingSessionShouldWaitWithoutQueryingWechat() {
        when(sessions.findAvailableSession(7L)).thenReturn(null);
        assertThat(service.reconcile("secret", 11L).outcome()).isEqualTo("WAITING_SESSION");
        assertThat(order.getStatus()).isEqualTo(RechargeOrderStatusDict.CLOSED.getCode());
        verifyNoInteractions(recharge);
        verify(transactions).recordQuerySchedule(eq(11L), any(), eq(0), eq("WAITING_SESSION"));
    }

    /** 不确定远端结果不能终止恢复，指数退避在一小时封顶。 */
    @Test
    void repeatedQueryFailureShouldRemainRetryableAndCapBackoff() {
        order.setQueryRetryCount(20);
        when(recharge.reconcileOrderWithinUserLock(7L, "ORDER")).thenThrow(new IllegalStateException("暂时失败"));
        LocalDateTime before = LocalDateTime.now();
        assertThat(service.reconcile("secret", 11L).outcome()).isEqualTo("RETRY_WAIT");
        verify(transactions).recordQuerySchedule(eq(11L), argThat(time ->
                time.isAfter(before.plusMinutes(59)) && time.isBefore(before.plusMinutes(61))), eq(21), eq("QUERY_FAILED"));
        assertThat(order.getStatus()).isEqualTo(RechargeOrderStatusDict.CLOSED.getCode());
    }

    /** 多个调度器取到同一候选时，在用户锁内再查到期时间，避免重复远端请求。 */
    @Test
    void notDueOrderShouldSkipRemoteQueries() {
        order.setNextQueryAt(LocalDateTime.now().plusHours(1));
        assertThat(service.reconcile("secret", 11L).outcome()).isEqualTo("SKIPPED_NOT_DUE");
        verifyNoInteractions(recharge, transactions, sessions);
    }

    /** 开关关闭时只鉴权，不读写订单或调用微信。 */
    @Test
    void disabledShouldSkipWithoutTouchingOrders() {
        properties.setEnabled(false);
        assertThat(service.reconcile("secret", 11L).outcome()).isEqualTo("SKIPPED_DISABLED");
        verifyNoInteractions(orders, recharge, transactions, sessions, mutex);
    }

    /** 会话存储或解密异常也要退避，不能让一笔坏数据反复立即占用核对请求。 */
    @Test
    void sessionReadFailureShouldBackoffWithoutChangingOrderState() {
        when(sessions.findAvailableSession(7L)).thenThrow(new IllegalStateException("会话读取失败"));
        assertThat(service.reconcile("secret", 11L).outcome()).isEqualTo("RETRY_WAIT");
        verify(transactions).recordQuerySchedule(eq(11L), any(), eq(1), eq("QUERY_FAILED"));
        verifyNoInteractions(recharge);
    }

    /** 普通明确失败订单没有收款事实时，不扩大为新的自动恢复候选。 */
    @Test
    void failedOrderWithoutConfirmedPaymentShouldSkip() {
        order.setStatus(RechargeOrderStatusDict.PAYMENT_FAILED.getCode());
        assertThat(service.reconcile("secret", 11L).outcome()).isEqualTo("SKIPPED_TERMINAL");
        verifyNoInteractions(recharge, transactions, sessions);
    }

    /** 构造远端核对已确认的应用结果。 */
    private RechargeOrderSyncResponse response(RechargeOrderStatusDict status) {
        RechargeOrderSyncResponse response = new RechargeOrderSyncResponse();
        response.setStatus(status.getCode());
        response.setConfirmed(true);
        return response;
    }
}
