package com.jxc.wefolio.service.payment;

import com.jxc.wefolio.message.RechargeMessage;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/**
 * 微信支付充值通知服务 — 先由官方 SDK 验签解密，再复用统一结算事务。
 *
 * @deprecated 普通微信支付通知入口已下线，待兼容观察期结束后删除
 */
@Service
@RequiredArgsConstructor
@Deprecated(forRemoval = true)
public class WechatRechargeNotificationService {

    /** 微信支付客户端。 */
    private final WechatPayClient wechatPayClient;

    /** 充值订单事务服务。 */
    private final RechargeOrderTransactionService transactionService;

    /**
     * 处理微信支付原始通知。
     *
     * @param request 包含全部签名头和原始请求体的通知
     * @return 充值结算结果
     * @deprecated 普通支付通知不再参与充值入账
     */
    @Deprecated(forRemoval = true)
    public RechargeSettlementResult handle(WechatPayClient.NotificationRequest request) {
        WechatPayClient.Transaction transaction = wechatPayClient.parseNotification(request);
        if (transaction.tradeState() != WechatPayClient.TradeState.SUCCESS) {
            throw new WechatPayNotificationException(
                    RechargeMessage.NOTIFICATION_NOT_SUCCESS_MESSAGE, null);
        }
        return transactionService.settle(transaction);
    }
}
