package com.jxc.wefolio.service.payment;

import com.jxc.wefolio.config.WechatVirtualPaymentProperties;
import com.jxc.wefolio.dict.RechargeOrderStatusDict;
import com.jxc.wefolio.dto.RechargeOrderSyncResponse;
import com.jxc.wefolio.entity.RechargeOrderEntity;
import com.jxc.wefolio.entity.UserAuthEntity;
import com.jxc.wefolio.exception.BusinessException;
import com.jxc.wefolio.mapper.RechargeOrderEntityMapper;
import com.jxc.wefolio.mapper.UserAuthEntityMapper;
import com.jxc.wefolio.service.PointService;
import com.jxc.wefolio.service.point.UserPointMutex;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/** 充值查单、余额查询及结算的用户锁和失败恢复测试。 */
@ExtendWith(MockitoExtension.class)
class RechargeAuthoritySettlementTest {

    /** 支付开关模拟。 */
    @Mock private WechatVirtualPaymentProperties virtualPaymentProperties;
    /** 订单查询模拟。 */
    @Mock private RechargeOrderEntityMapper rechargeOrderEntityMapper;
    /** 微信身份模拟。 */
    @Mock private UserAuthEntityMapper userAuthEntityMapper;
    /** 本地积分模拟。 */
    @Mock private PointService pointService;
    /** 结算事务模拟。 */
    @Mock private RechargeOrderTransactionService transactionService;
    /** 微信请求模拟。 */
    @Mock private WechatVirtualPaymentClient wechatVirtualPaymentClient;
    /** 会话模拟。 */
    @Mock private MaintainerWechatSessionService maintainerWechatSessionService;
    /** 用户锁模拟。 */
    @Mock private UserPointMutex userPointMutex;
    /** 被测充值编排服务。 */
    @InjectMocks private RechargeService service;
    /** 检查所有远端调用与本地结算都处于同一锁区间。 */
    private final AtomicBoolean locked = new AtomicBoolean();
    /** 本次尚未结算的充值订单。 */
    private RechargeOrderEntity order;
    /** 已确认支付结果。 */
    private WechatVirtualPaymentResult paid;

    /** 设置一个已被微信支付、尚未在本地确认的订单。 */
    @BeforeEach
    void setUp() {
        when(virtualPaymentProperties.isEnabled()).thenReturn(true);
        when(userPointMutex.execute(eq(7L), any())).thenAnswer(invocation -> {
            assertThat(locked.compareAndSet(false, true)).isTrue();
            try {
                return invocation.<Supplier<?>>getArgument(1).get();
            } finally {
                locked.set(false);
            }
        });
        order = new RechargeOrderEntity();
        order.setId(1L);
        order.setUserId(7L);
        order.setMerchantOrderNo("ORDER");
        order.setStatus(RechargeOrderStatusDict.CLOSED.getCode());
        when(rechargeOrderEntityMapper.selectOne(any())).thenReturn(order);
        UserAuthEntity auth = new UserAuthEntity();
        auth.setOpenId("openid");
        lenient().when(userAuthEntityMapper.selectOne(any())).thenReturn(auth);
        lenient().when(maintainerWechatSessionService.findAvailableSession(7L))
                .thenReturn(new MaintainerWechatSession(7L, 2L, "session", 1L, "127.0.0.1"));
        paid = new WechatVirtualPaymentResult(0, null, WechatVirtualPaymentErrorType.SUCCESS,
                0L, 0L, 0L, "PAID", 500L, 5000L, 200);
        lenient().when(wechatVirtualPaymentClient.queryOrder(any())).thenAnswer(invocation -> {
            assertThat(locked.get()).isTrue();
            return paid;
        });
    }

    /** 退款后的查询重放返回已退款终态，不需要会话、不请求微信也不重新充值。 */
    @Test
    void refundedOrderShouldReturnTerminalStatusWithoutRemoteQueries() {
        order.setStatus(RechargeOrderStatusDict.REFUNDED.getCode());

        RechargeOrderSyncResponse response = service.syncOrder(7L, "ORDER");

        assertThat(response.getStatus()).isEqualTo(RechargeOrderStatusDict.REFUNDED.getCode());
        assertThat(response.getStatusText()).isEqualTo("已退款");
        assertThat(response.isConfirmed()).isTrue();
        assertThat(response.getBalance()).isNull();
        verifyNoInteractions(wechatVirtualPaymentClient, maintainerWechatSessionService, transactionService, pointService);
    }

    /** 查询与结算必须连续处于一把用户锁内，并将当前余额传给结算事务。 */
    @Test
    void shouldQueryAuthoritativeBalanceInsideSameLockBeforeSettling() {
        WechatVirtualPaymentResult balance = new WechatVirtualPaymentResult(
                0, null, WechatVirtualPaymentErrorType.SUCCESS, 600L, 50L, 0L, null, 0L, 0L, 200);
        when(wechatVirtualPaymentClient.queryUserBalance(any())).thenAnswer(invocation -> {
            assertThat(locked.get()).isTrue();
            return balance;
        });
        when(transactionService.settleVirtual("ORDER", paid, balance)).thenAnswer(invocation -> {
            assertThat(locked.get()).isTrue();
            order.setStatus(RechargeOrderStatusDict.PAID.getCode());
            return new RechargeSettlementResult(order, 580L, false);
        });

        RechargeOrderSyncResponse response = service.syncOrder(7L, "ORDER");

        assertThat(response.getBalance()).isEqualTo(580L);
        assertThat(response.getStatus()).isEqualTo(RechargeOrderStatusDict.PAID.getCode());
        var sequence = inOrder(wechatVirtualPaymentClient, transactionService);
        sequence.verify(wechatVirtualPaymentClient).queryOrder(any());
        sequence.verify(wechatVirtualPaymentClient).queryUserBalance(any());
        sequence.verify(transactionService).settleVirtual("ORDER", paid, balance);
        verify(userPointMutex).execute(eq(7L), any());
        assertThat(locked.get()).isFalse();
    }

    /** 余额查询失败时不提交充值，客户端或通知可用同一订单重试。 */
    @Test
    void balanceQueryFailureShouldLeaveOriginalOrderUnsettled() {
        when(wechatVirtualPaymentClient.queryUserBalance(any())).thenReturn(new WechatVirtualPaymentResult(
                null, "临时失败", WechatVirtualPaymentErrorType.TRANSIENT, 0L, 0L, 0L, null, 0L, 0L, 503));

        assertThatThrownBy(() -> service.syncOrder(7L, "ORDER")).isInstanceOf(BusinessException.class);

        verifyNoInteractions(transactionService, pointService);
        assertThat(order.getStatus()).isEqualTo(RechargeOrderStatusDict.CLOSED.getCode());
        assertThat(locked.get()).isFalse();
    }

    /** 查单响应明确来自沙箱时，不得用其支付结果结算正式环境订单。 */
    @Test
    void sandboxOrderResultShouldNotQueryBalanceOrSettle() {
        paid = new WechatVirtualPaymentResult(0, null, WechatVirtualPaymentErrorType.SUCCESS,
                0L, 0L, 0L, "PAID", 500L, 5000L, 200, "openid", 1, "ORDER");

        assertThatThrownBy(() -> service.syncOrder(7L, "ORDER")).isInstanceOf(BusinessException.class);

        verify(wechatVirtualPaymentClient, never()).queryUserBalance(any());
        verifyNoInteractions(transactionService, pointService);
        assertThat(order.getStatus()).isEqualTo(RechargeOrderStatusDict.CLOSED.getCode());
        assertThat(locked.get()).isFalse();
    }

    /** 微信退款终态沿用既有未入账订单的失败收口，不继续轮询或发起充值结算。 */
    @Test
    void remoteRefundShouldFinishPollingWithoutSettling() {
        paid = new WechatVirtualPaymentResult(0, null, WechatVirtualPaymentErrorType.SUCCESS,
                0L, 0L, 0L, "REFUND", 0L, 0L, 200, "openid", 0, "ORDER");
        when(transactionService.markTerminalState(7L, "ORDER", RechargeOrderStatusDict.PAYMENT_FAILED))
                .thenAnswer(invocation -> {
                    order.setStatus(RechargeOrderStatusDict.PAYMENT_FAILED.getCode());
                    return order;
                });

        RechargeOrderSyncResponse response = service.syncOrder(7L, "ORDER");

        assertThat(response.isConfirmed()).isTrue();
        assertThat(response.getStatus()).isEqualTo(RechargeOrderStatusDict.PAYMENT_FAILED.getCode());
        verify(wechatVirtualPaymentClient, never()).queryUserBalance(any());
        verify(transactionService, never()).settleVirtual(any(), any(), any());
        verifyNoInteractions(pointService);
        assertThat(locked.get()).isFalse();
    }
}
