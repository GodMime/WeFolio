package com.jxc.wefolio.service.payment;

import com.jxc.wefolio.dict.RechargeOrderStatusDict;
import com.jxc.wefolio.message.RechargeMessage;

/** 充值订单状态展示文案 — 供记录查询和支付同步响应共用。 */
final class RechargeOrderStatusText {

    /** 仅提供状态文案转换，无需实例化。 */
    private RechargeOrderStatusText() {
    }

    /**
     * 获取充值状态展示文案。
     */
    static String format(String status) {
        if (RechargeOrderStatusDict.REFUNDED.getCode().equals(status)) {
            return RechargeMessage.STATUS_REFUNDED_TEXT;
        }
        if (RechargeOrderStatusDict.PAID.getCode().equals(status)) {
            return RechargeMessage.STATUS_PAID_TEXT;
        }
        if (RechargeOrderStatusDict.PENDING_PAYMENT.getCode().equals(status)) {
            return RechargeMessage.STATUS_PENDING_TEXT;
        }
        if (RechargeOrderStatusDict.PAYMENT_FAILED.getCode().equals(status)) {
            return RechargeMessage.STATUS_PAYMENT_FAILED_TEXT;
        }
        return RechargeMessage.STATUS_CLOSED_TEXT;
    }
}
