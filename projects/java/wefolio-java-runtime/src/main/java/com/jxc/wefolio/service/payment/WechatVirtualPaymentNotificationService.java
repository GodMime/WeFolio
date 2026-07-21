package com.jxc.wefolio.service.payment;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.jxc.wefolio.dict.RechargeOrderStatusDict;
import com.jxc.wefolio.entity.RechargeOrderEntity;
import com.jxc.wefolio.mapper.RechargeOrderEntityMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/** 已验签微信虚拟支付通知业务处理服务。 */
@Slf4j
@Service
@RequiredArgsConstructor
public class WechatVirtualPaymentNotificationService {

    private static final String PAYMENT_NOTIFY = "xpay_coin_pay_notify";
    private static final String REFUND_NOTIFY = "xpay_refund_notify";
    private static final String IOS_REFUND_QUERY_NOTIFY = "xpay_subscribe_ios_refund_query_notify";

    private final RechargeOrderEntityMapper rechargeOrderEntityMapper;
    private final RechargeService rechargeService;
    private final RechargeRefundTransactionService refundTransactionService;
    private final WechatAuthoritativeBalanceSyncService balanceSyncService;

    /** 处理通知并返回不含敏感数据的 XML 应答。 */
    public String handle(WechatVirtualPaymentNotification notification) {
        log.info("微信虚拟支付业务开始 operation=分发虚拟支付通知 referenceNo={} userId=null "
                        + "notificationType={}",
                notification.orderNo(), notification.type());
        if (PAYMENT_NOTIFY.equals(notification.type())) {
            RechargeOrderEntity order = findOrder(notification.orderNo());
            if (order == null) {
                throw new IllegalArgumentException("微信虚拟支付通知对应订单不存在");
            }
            rechargeService.syncOrderFromNotification(order.getMerchantOrderNo());
            log.info("微信虚拟支付业务完成 operation=分发虚拟支付通知 referenceNo={} userId={} "
                            + "localStatus=PAYMENT_SYNCHRONIZED",
                    order.getMerchantOrderNo(), order.getUserId());
            return successResponse();
        }
        if (REFUND_NOTIFY.equals(notification.type())) {
            RechargeOrderEntity order = findOrder(notification.orderNo());
            if (order == null) {
                throw new IllegalArgumentException("微信虚拟支付退款通知对应订单不存在");
            }
            RechargeOrderEntity refunded = refundTransactionService.markRefunded(order.getMerchantOrderNo());
            balanceSyncService.synchronizeIfSessionAvailable(
                    refunded.getUserId(), refunded.getAccountId(), refunded.getMerchantOrderNo());
            log.info("微信虚拟支付退款通知已处理 merchantOrderNo={} userId={}",
                    order.getMerchantOrderNo(), order.getUserId());
            log.info("微信虚拟支付业务完成 operation=分发虚拟支付通知 referenceNo={} userId={} "
                            + "localStatus=REFUND_SYNCHRONIZED",
                    order.getMerchantOrderNo(), order.getUserId());
            return successResponse();
        }
        if (IOS_REFUND_QUERY_NOTIFY.equals(notification.type())) {
            RechargeOrderEntity order = findOrder(notification.orderNo());
            log.info("微信虚拟支付业务完成 operation=分发虚拟支付通知 referenceNo={} userId={} "
                            + "localStatus=REFUND_ADVICE_CREATED",
                    notification.orderNo(), order == null ? null : order.getUserId());
            return iosRefundAdvice(order);
        }
        throw new IllegalArgumentException("不支持的微信虚拟支付通知类型");
    }

    /** 同时兼容商户订单号和微信侧订单号。 */
    private RechargeOrderEntity findOrder(String orderNo) {
        if (orderNo == null || orderNo.isBlank()) {
            return null;
        }
        return rechargeOrderEntityMapper.selectOne(
                Wrappers.lambdaQuery(RechargeOrderEntity.class)
                        .and(wrapper -> wrapper
                                .eq(RechargeOrderEntity::getMerchantOrderNo, orderNo)
                                .or().eq(RechargeOrderEntity::getWechatOrderId, orderNo)
                                .or().eq(RechargeOrderEntity::getChannelOrderId, orderNo))
                        .last("LIMIT 1")
        );
    }

    /** iOS 退款问询只返回交付结论和脱敏依据。 */
    private String iosRefundAdvice(RechargeOrderEntity order) {
        boolean delivered = order != null
                && (RechargeOrderStatusDict.PAID.getCode().equals(order.getStatus())
                || RechargeOrderStatusDict.REFUNDED.getCode().equals(order.getStatus()));
        String advice = delivered ? "REJECT" : "ALLOW";
        String evidence = delivered ? "LOCAL_ORDER_DELIVERED" : "LOCAL_ORDER_NOT_DELIVERED";
        return "<xml><return_code>SUCCESS</return_code><return_msg>OK</return_msg>"
                + "<refund_advice>" + advice + "</refund_advice>"
                + "<evidence>" + evidence + "</evidence></xml>";
    }

    /** 通用成功应答。 */
    private String successResponse() {
        return "<xml><return_code>SUCCESS</return_code><return_msg>OK</return_msg></xml>";
    }
}
