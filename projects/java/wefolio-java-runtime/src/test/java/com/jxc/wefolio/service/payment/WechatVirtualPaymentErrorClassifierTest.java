package com.jxc.wefolio.service.payment;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 微信虚拟支付错误分类测试。
 */
class WechatVirtualPaymentErrorClassifierTest {

    /** 设计方案列出的微信错误码必须映射到稳定分类。 */
    @ParameterizedTest
    @CsvSource({
            "0,SUCCESS",
            "268490004,DUPLICATE_SUCCESS",
            "268490009,SESSION_INVALID",
            "268490006,INSUFFICIENT_BALANCE",
            "-1,TRANSIENT",
            "268490015,RATE_LIMITED",
            "40001,ACCESS_TOKEN_REJECTED",
            "40014,ACCESS_TOKEN_REJECTED",
            "42001,ACCESS_TOKEN_REJECTED"
    })
    void shouldClassifyKnownWechatErrors(int errorCode, WechatVirtualPaymentErrorType expected) {
        WechatVirtualPaymentErrorClassifier classifier = new WechatVirtualPaymentErrorClassifier();

        assertThat(classifier.classify(errorCode, 200)).isEqualTo(expected);
    }
}
