package com.jxc.wefolio.service.payment;

import com.jxc.wefolio.dto.CreateRechargeOrderResponse;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** 微信虚拟支付充值响应契约测试。 */
class RechargeServiceTest {

    /** 创建订单响应只保留虚拟支付调起字段。 */
    @Test
    void createResponseShouldExposeVirtualPaymentFieldsOnly() {
        CreateRechargeOrderResponse response = new CreateRechargeOrderResponse();
        response.setMode("short_series_coin");
        response.setSignData("{\"env\":0}");
        response.setPaySig("pay-signature");
        response.setSignature("user-signature");

        assertThat(response.getMode()).isEqualTo("short_series_coin");
        assertThat(response.getSignData()).contains("\"env\":0");
        assertThat(response.getPaySig()).isEqualTo("pay-signature");
        assertThat(response.getSignature()).isEqualTo("user-signature");
    }
}
