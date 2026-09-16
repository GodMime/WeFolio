package com.jxc.wefolio.service.payment;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.provider.CsvSource;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 微信虚拟支付错误分类测试。
 */
class WechatVirtualPaymentErrorClassifierTest {

    /** HTTP 成功不能替代缺失的业务成功码，HTTP 失败也不能被 errcode=0 掩盖。 */
    @Test
    void shouldRejectMissingErrorCodeAndFailedHttpStatus() {
        WechatVirtualPaymentErrorClassifier classifier = new WechatVirtualPaymentErrorClassifier();
        assertThat(classifier.classify(null, 200)).isEqualTo(WechatVirtualPaymentErrorType.UNKNOWN);
        assertThat(classifier.classify(0, 403)).isEqualTo(WechatVirtualPaymentErrorType.UNKNOWN);
        assertThat(classifier.classify(0, 503)).isEqualTo(WechatVirtualPaymentErrorType.TRANSIENT);
    }

    /** HTTP 4xx 保留业务错误含义，429 和 5xx 继续优先按传输状态处理。 */
    @ParameterizedTest
    @CsvSource({
            "268490006,400,INSUFFICIENT_BALANCE",
            "268490009,403,SESSION_INVALID",
            "268490004,409,DUPLICATE_SUCCESS",
            "40014,401,ACCESS_TOKEN_REJECTED",
            "268490006,429,RATE_LIMITED",
            "268490009,503,TRANSIENT",
            "123456,400,UNKNOWN",
            "0,302,UNKNOWN"
    })
    void shouldPreserveKnownBusinessErrorsOnClientHttpFailure(
            int errorCode, int httpStatus, WechatVirtualPaymentErrorType expected
    ) {
        assertThat(new WechatVirtualPaymentErrorClassifier().classify(errorCode, httpStatus)).isEqualTo(expected);
    }

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
