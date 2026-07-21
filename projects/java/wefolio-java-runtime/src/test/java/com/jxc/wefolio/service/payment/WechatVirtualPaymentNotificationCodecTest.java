package com.jxc.wefolio.service.payment;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** 微信虚拟支付通知 XML 解析测试。 */
class WechatVirtualPaymentNotificationCodecTest {

    private final WechatVirtualPaymentNotificationCodec codec =
            new WechatVirtualPaymentNotificationCodec();

    @Test
    void shouldParsePaymentEventAndMerchantOrderNumber() {
        String xml = "<xml><Event><![CDATA[xpay_coin_pay_notify]]></Event>"
                + "<out_trade_no><![CDATA[WFR202607190001]]></out_trade_no></xml>";

        WechatVirtualPaymentNotification notification = codec.parse(xml);

        assertThat(notification.type()).isEqualTo("xpay_coin_pay_notify");
        assertThat(notification.orderNo()).isEqualTo("WFR202607190001");
    }

    @Test
    void shouldRejectDoctypeAndUnknownEvent() {
        assertThatThrownBy(() -> codec.parse("<!DOCTYPE xml [<!ENTITY xxe SYSTEM 'file:///etc/passwd'>]><xml/>"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> codec.parse("<xml><Event>unknown</Event></xml>"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("不支持");
    }
}
