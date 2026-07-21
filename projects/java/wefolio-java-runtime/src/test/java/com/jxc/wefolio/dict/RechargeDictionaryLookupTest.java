package com.jxc.wefolio.dict;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 充值相关字典编码查询契约测试。
 */
class RechargeDictionaryLookupTest {

    @Test
    void paymentChannelShouldResolveKnownCodeAndRejectUnknownCode() {
        assertThat(PaymentChannelDict.fromCode("WECHAT_PAY"))
                .isEqualTo(PaymentChannelDict.WECHAT_PAY);
        assertThat(PaymentChannelDict.fromCode("UNKNOWN")).isNull();
        assertThat(PaymentChannelDict.fromCode(null)).isNull();
    }

    @Test
    void rechargePackageStatusShouldResolveKnownCodeAndRejectUnknownCode() {
        assertThat(RechargePackageStatusDict.fromCode("ACTIVE"))
                .isEqualTo(RechargePackageStatusDict.ACTIVE);
        assertThat(RechargePackageStatusDict.fromCode("DISABLED"))
                .isEqualTo(RechargePackageStatusDict.DISABLED);
        assertThat(RechargePackageStatusDict.fromCode("UNKNOWN")).isNull();
        assertThat(RechargePackageStatusDict.fromCode(null)).isNull();
    }
}
