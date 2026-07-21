package com.jxc.wefolio.service.payment;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 微信支付充值通知服务测试。
 */
@ExtendWith(MockitoExtension.class)
class WechatRechargeNotificationServiceTest {

    /** 微信支付客户端模拟。 */
    @Mock
    private WechatPayClient wechatPayClient;

    /** 充值订单事务服务模拟。 */
    @Mock
    private RechargeOrderTransactionService transactionService;

    @Test
    void handleShouldParseOriginalNotificationAndUseSharedSettlement() {
        WechatPayClient.NotificationRequest request = request();
        WechatPayClient.Transaction transaction = successTransaction();
        RechargeSettlementResult settlement = new RechargeSettlementResult(null, 806L, false);
        when(wechatPayClient.parseNotification(request)).thenReturn(transaction);
        when(transactionService.settle(transaction)).thenReturn(settlement);

        RechargeSettlementResult result = service().handle(request);

        assertThat(result).isSameAs(settlement);
        verify(wechatPayClient).parseNotification(request);
        verify(transactionService).settle(transaction);
    }

    @Test
    void handleShouldRejectNonSuccessEventWithoutSettlement() {
        WechatPayClient.Transaction transaction = new WechatPayClient.Transaction(
                "wx-test-app-id", "1900000001", "WFR20260717153000123A3B7K9M2Q5R", null,
                WechatPayClient.TradeState.NOTPAY, "CNY", 5000, null);
        when(wechatPayClient.parseNotification(request())).thenReturn(transaction);

        assertThatThrownBy(() -> service().handle(request()))
                .isInstanceOf(WechatPayNotificationException.class)
                .hasMessage("微信支付通知不是支付成功事件");
        verify(transactionService, never()).settle(transaction);
    }

    /**
     * 创建被测服务。
     *
     * @return 通知服务
     */
    private WechatRechargeNotificationService service() {
        return new WechatRechargeNotificationService(wechatPayClient, transactionService);
    }

    /**
     * 构造原始通知。
     *
     * @return 通知参数
     */
    private WechatPayClient.NotificationRequest request() {
        return new WechatPayClient.NotificationRequest(
                "PUB_KEY_ID_123", "signature", "1784273400", "nonce",
                "WECHATPAY2-SHA256-RSA2048", "{\"id\":\"notification-1\"}");
    }

    /**
     * 构造支付成功交易。
     *
     * @return 微信交易
     */
    private WechatPayClient.Transaction successTransaction() {
        return new WechatPayClient.Transaction(
                "wx-test-app-id", "1900000001", "WFR20260717153000123A3B7K9M2Q5R",
                "4200000000001", WechatPayClient.TradeState.SUCCESS, "CNY", 5000,
                LocalDateTime.of(2026, 7, 17, 15, 30, 8));
    }
}
