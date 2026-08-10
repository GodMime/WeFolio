package com.jxc.wefolio.service.payment;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 赠送订单处理链路结构测试。
 */
class PointGiftOrderProcessorStructureTest {

    /** 处理器必须先领租约、复用稳定订单号，并按错误分类落状态。 */
    @Test
    void processorShouldUseLeaseStableOrderAndIndependentTransactions() throws Exception {
        String processor = Files.readString(Path.of(
                "src/main/java/com/jxc/wefolio/service/payment/PointGiftOrderProcessor.java"));
        String transactionService = Files.readString(Path.of(
                "src/main/java/com/jxc/wefolio/service/payment/PointGiftOrderTransactionService.java"));

        assertThat(processor)
                .contains("public TaskExecutionOutcome process(Long orderId)")
                .contains("tokenGenerator.generate()")
                .contains("tryClaim(orderId, executionLeaseToken)")
                .contains("renewLease(order.getId(), executionLeaseToken)")
                .contains("order.getOrderNo()")
                .contains("findWechatOpenid(order.getUserId())")
                .contains("AuthTypeDict.WECHAT_MINI_APP.getCode()")
                .contains("UserAuthEntity::getOpenId")
                .contains("wechatVirtualPaymentClient.presentCurrency")
                .contains("new WechatPresentCurrencyRequest")
                .contains("WechatVirtualPaymentErrorType.DUPLICATE_SUCCESS")
                .contains("markFailure")
                .doesNotContain("public void process(Long orderId, String");
        assertThat(transactionService)
                .contains("Propagation.REQUIRES_NEW")
                .contains("pointAccountEntityMapper.applyGiftWechatBalance")
                .contains("PointTransactionTypeDict.GIFT")
                .contains("pointDebitTaskService.ensureActiveTask");
    }
}
