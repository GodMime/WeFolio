package com.jxc.wefolio.service.payment;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 微信虚拟支付签名测试。
 */
class WechatVirtualPaymentSignerTest {

    /** 固定向量应生成小写十六进制 HMAC-SHA256。 */
    @Test
    void shouldMatchFixedHmacSha256Vectors() {
        WechatVirtualPaymentSigner signer = new WechatVirtualPaymentSigner();
        String body = "{\"env\":0,\"amount\":10}";

        assertThat(signer.paySignature("test-app-key", "/xpay/currency_pay", body))
                .isEqualTo("a39658f6e3ae0e9e0c8f517a594b386e196b839dd4410e09888e0a26bb1ac05e");
        assertThat(signer.userSignature("test-session-key", body))
                .isEqualTo("f7c42856580df051bb0a332bdfb838c12e3ad82f644c7ef4a1ef0564387470a6");
    }
}
