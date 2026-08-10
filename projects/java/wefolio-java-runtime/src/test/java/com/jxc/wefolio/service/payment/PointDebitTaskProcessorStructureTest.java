package com.jxc.wefolio.service.payment;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 微信待扣任务处理结构测试。
 */
class PointDebitTaskProcessorStructureTest {

    /** 执行器必须查询权威余额、持久化请求金额并支持部分核销和会话失效。 */
    @Test
    void processorShouldImplementBalanceFirstPartialSettlementAndSessionInvalidation() throws Exception {
        String processor = Files.readString(Path.of(
                "src/main/java/com/jxc/wefolio/service/payment/PointDebitTaskProcessor.java"));
        String transactionService = Files.readString(Path.of(
                "src/main/java/com/jxc/wefolio/service/payment/PointDebitTaskTransactionService.java"));

        assertThat(processor)
                .contains("public TaskExecutionOutcome process(Long taskId)")
                .contains("tokenGenerator.generate()")
                .contains("tryClaim(taskId, executionLeaseToken)")
                .contains("renewLease(task.getId(), executionLeaseToken)")
                .contains("queryUserBalance")
                .contains("Math.min")
                .contains("prepareRequest")
                .contains("currencyPay")
                .contains("processDueGiftFirst")
                .contains("invalidateVersion")
                .contains("WAITING_SESSION")
                .contains("pointGiftOrderProcessor.process(gift.getId())")
                .doesNotContain("public void process(Long taskId, String");
        assertThat(transactionService)
                .contains("Propagation.REQUIRES_NEW")
                .contains("settleWechatDebit")
                .contains("remainingAmount")
                .contains("PointDebitTaskStatusDict.PARTIAL")
                .contains("getActiveFlag, activeFlag");
    }
}
