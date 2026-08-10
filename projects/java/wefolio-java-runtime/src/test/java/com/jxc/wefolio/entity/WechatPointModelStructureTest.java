package com.jxc.wefolio.entity;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 微信虚拟支付积分模型结构测试 — 固定实体字段、状态字典和账户原子 SQL 契约。
 */
class WechatPointModelStructureTest {

    /** runtime Java 源码根目录。 */
    private static final Path JAVA_ROOT = Path.of("src/main/java/com/jxc/wefolio");

    @Test
    void accountAndRechargeEntitiesShouldExposeWechatVirtualPaymentFields() throws IOException {
        String account = read("entity/PointAccountEntity.java");
        String recharge = read("entity/RechargeOrderEntity.java");

        assertThat(account)
                .contains("private Long wechatBalance;")
                .contains("private Long wechatPresentBalance;")
                .contains("private Long pendingDebit;")
                .contains("private LocalDateTime wechatBalanceSyncedAt;");
        assertThat(recharge)
                .contains("private Long buyQuantity;")
                .contains("private String wechatOrderId;")
                .contains("private String channelOrderId;")
                .contains("private String wxpayOrderId;")
                .contains("private Long paidFee;")
                .contains("private Long pointTransactionId;")
                .contains("private Long bonusGiftOrderId;")
                .contains("private LocalDateTime refundedAt;")
                .doesNotContain("private String prepayId;")
                .doesNotContain("private String paymentTransactionId;");
    }

    @Test
    void persistentTaskEntitiesShouldDeclareAllRecoveryFields() throws IOException {
        assertThat(read("entity/PointPendingDebitEntity.java"))
                .contains("@TableName(\"wf_point_pending_debit\")")
                .contains("private Long pointTransactionId;")
                .contains("private Long originalAmount;")
                .contains("private Long remainingAmount;");
        assertThat(read("entity/PointDebitTaskEntity.java"))
                .contains("@TableName(\"wf_point_debit_task\")")
                .contains("private String taskNo;")
                .contains("private Integer activeFlag;")
                .contains("private Long requestAmount;")
                .contains("private String executionLeaseToken;")
                .contains("private LocalDateTime leaseUntil;")
                .contains("private Long sessionVersion;");
        assertThat(read("entity/PointGiftOrderEntity.java"))
                .contains("@TableName(\"wf_point_gift_order\")")
                .contains("private String orderNo;")
                .contains("private String businessSnapshot;")
                .contains("private String idempotencyKey;")
                .contains("private String executionLeaseToken;")
                .contains("private Long wechatPresentBalanceAfter;");
        assertThat(read("entity/MaintainerWechatSessionEntity.java"))
                .contains("@TableName(\"wf_maintainer_wechat_session\")")
                .contains("private String sessionKeyCiphertext;")
                .contains("private Long sessionVersion;")
                .contains("private String lastUserIp;");
    }

    @Test
    void pointAccountMapperShouldExposeThreeDebitSemanticsAndWechatSettlement() throws IOException {
        String mapper = read("mapper/PointAccountEntityMapper.java");

        assertThat(mapper)
                .contains("int deductForMaintainer(")
                .contains("int deductForVisitor(")
                .contains("int deductForSystem(")
                .contains("int syncWechatBalance(")
                .contains("int settleWechatDebit(")
                .contains("pending_debit = pending_debit + #{points}")
                .contains("AND balance >= #{points}")
                .contains("AND balance > 0")
                .contains("balance = #{wechatBalance} - pending_debit")
                .contains("pending_debit = pending_debit - #{settledPoints}");
    }

    @Test
    void dictionariesShouldCoverWechatSyncRefundAndTaskStates() throws IOException {
        assertThat(read("dict/PointTransactionTypeDict.java")).contains("WECHAT_SYNC");
        assertThat(read("dict/RechargeOrderStatusDict.java")).contains("REFUNDED");
        assertThat(read("dict/PointDebitTaskStatusDict.java"))
                .contains("WAITING_SESSION")
                .contains("PARTIAL")
                .contains("NO_BALANCE")
                .contains("FAILED");
        assertThat(read("dict/PointGiftOrderStatusDict.java"))
                .contains("READY")
                .contains("RETRY_WAIT")
                .contains("SUCCEEDED")
                .contains("FAILED");
        assertThat(read("dict/MaintainerWechatSessionStatusDict.java"))
                .contains("AVAILABLE")
                .contains("INVALID");
    }

    /** 读取指定 runtime Java 源文件。 */
    private String read(String relativePath) throws IOException {
        Path path = JAVA_ROOT.resolve(relativePath);
        assertThat(path).exists();
        return Files.readString(path);
    }
}
