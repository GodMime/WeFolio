package com.jxc.wefolio.service.payment;

import com.jxc.wefolio.dict.RechargeOrderStatusDict;
import com.jxc.wefolio.entity.RechargeOrderEntity;
import com.jxc.wefolio.mapper.RechargeOrderEntityMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** 微信虚拟支付通知分发测试。 */
@ExtendWith(MockitoExtension.class)
class WechatVirtualPaymentNotificationServiceTest {

    @Mock
    private RechargeOrderEntityMapper rechargeOrderEntityMapper;

    @Mock
    private RechargeService rechargeService;

    @Mock
    private RechargeRefundTransactionService refundTransactionService;

    @Mock
    private WechatAuthoritativeBalanceSyncService balanceSyncService;

    @Test
    void paymentNotificationShouldUseAuthoritativeOrderQuery() {
        RechargeOrderEntity order = order(RechargeOrderStatusDict.PENDING_PAYMENT);
        when(rechargeOrderEntityMapper.selectOne(any())).thenReturn(order);

        String response = service().handle(new WechatVirtualPaymentNotification(
                "xpay_coin_pay_notify", "WFR202607190001"));

        verify(rechargeService).syncOrderFromNotification("WFR202607190001");
        assertThat(response).contains("SUCCESS");
    }

    @Test
    void refundNotificationShouldMarkRefundedAndSynchronizeBalanceWhenPossible() {
        RechargeOrderEntity order = order(RechargeOrderStatusDict.PAID);
        when(rechargeOrderEntityMapper.selectOne(any())).thenReturn(order);
        when(refundTransactionService.markRefunded(order.getMerchantOrderNo())).thenReturn(order);

        service().handle(new WechatVirtualPaymentNotification(
                "xpay_refund_notify", order.getMerchantOrderNo()));

        verify(refundTransactionService).markRefunded(order.getMerchantOrderNo());
        verify(balanceSyncService).synchronizeIfSessionAvailable(
                order.getUserId(), order.getAccountId(), order.getMerchantOrderNo());
    }

    @Test
    void iosRefundQueryShouldRejectWhenPaidOrderWasDelivered() {
        when(rechargeOrderEntityMapper.selectOne(any()))
                .thenReturn(order(RechargeOrderStatusDict.PAID));

        String response = service().handle(new WechatVirtualPaymentNotification(
                "xpay_subscribe_ios_refund_query_notify", "WFR202607190001"));

        assertThat(response).contains("REJECT").contains("LOCAL_ORDER_DELIVERED");
    }

    private WechatVirtualPaymentNotificationService service() {
        return new WechatVirtualPaymentNotificationService(
                rechargeOrderEntityMapper,
                rechargeService,
                refundTransactionService,
                balanceSyncService
        );
    }

    private RechargeOrderEntity order(RechargeOrderStatusDict status) {
        RechargeOrderEntity order = new RechargeOrderEntity();
        order.setMerchantOrderNo("WFR202607190001");
        order.setUserId(7L);
        order.setAccountId(10L);
        order.setStatus(status.getCode());
        return order;
    }
}
