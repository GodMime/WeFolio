package com.jxc.wefolio.config;

import com.jxc.wefolio.exception.BusinessException;
import com.jxc.wefolio.service.payment.WechatPayClient;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 微信支付客户端配置测试。
 */
class WechatPayConfigurationTest {

    @Test
    void disabledPaymentShouldCreateNonRemoteClientWithoutReadingCertificateFiles() {
        WechatPayProperties properties = new WechatPayProperties();
        properties.setEnabled(false);

        WechatPayClient client = new WechatPayConfiguration().wechatPayClient(properties);

        assertThatThrownBy(() -> client.queryByMerchantOrderNo("WFR20260717153000123A3B7K9M2Q5R"))
                .isInstanceOf(BusinessException.class)
                .hasMessage("微信支付暂未配置，请稍后再试");
    }
}
