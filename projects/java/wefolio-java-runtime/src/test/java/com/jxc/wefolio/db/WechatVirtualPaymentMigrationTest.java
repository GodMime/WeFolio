package com.jxc.wefolio.db;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 微信虚拟支付迁移测试 — 固定账户公式、持久任务、充值订单和历史测试数据重置契约。
 */
class WechatVirtualPaymentMigrationTest {

    /** 虚拟支付模型迁移路径。 */
    private static final Path MODEL_MIGRATION = Path.of(
            "src/main/resources/db/migration/V38__add_wechat_virtual_payment_models.sql");

    /** 历史测试积分重置迁移路径。 */
    private static final Path RESET_MIGRATION = Path.of(
            "src/main/resources/db/migration/V39__reset_test_point_data_for_virtual_payment.sql");

    @Test
    void modelMigrationShouldCreateWechatBalancesAndPersistentTasks() throws IOException {
        assertThat(MODEL_MIGRATION).exists();

        String sql = Files.readString(MODEL_MIGRATION);
        assertThat(sql)
                .contains("MODIFY COLUMN `balance` BIGINT NOT NULL")
                .contains("`wechat_balance` BIGINT NOT NULL DEFAULT 0")
                .contains("`wechat_present_balance` BIGINT NOT NULL DEFAULT 0")
                .contains("`pending_debit` BIGINT NOT NULL DEFAULT 0")
                .contains("`wechat_balance_synced_at` DATETIME(3) NULL")
                .contains("CHECK (`balance` = `wechat_balance` - `pending_debit`)")
                .contains("CREATE TABLE `wf_point_pending_debit`")
                .contains("CREATE TABLE `wf_point_debit_task`")
                .contains("CREATE TABLE `wf_point_gift_order`")
                .contains("CREATE TABLE `wf_maintainer_wechat_session`")
                .contains("UNIQUE KEY `uk_point_debit_task_active` (`user_id`, `active_flag`, `deleted`)")
                .contains("UNIQUE KEY `uk_point_gift_idempotency` (`idempotency_key`, `deleted`)")
                .contains("'WAITING_SESSION'")
                .contains("'WECHAT_VIRTUAL_PAYMENT'")
                .contains("'REFUNDED'")
                .contains("'SKIPPED_NON_POSITIVE_BALANCE'")
                .doesNotContain("pending_gift");
    }

    /** 历史非零余额必须先回填微信余额快照，再添加账户公式约束。 */
    @Test
    void modelMigrationShouldBackfillLegacyBalanceBeforeAddingFormulaConstraint() throws IOException {
        assertThat(MODEL_MIGRATION).exists();

        String sql = Files.readString(MODEL_MIGRATION);
        assertThat(sql).containsSubsequence(
                "ADD COLUMN `wechat_balance` BIGINT NOT NULL DEFAULT 0",
                "UPDATE `wf_point_account`",
                "SET `wechat_balance` = `balance`",
                "ADD CONSTRAINT `chk_point_account_balance_formula`"
        );
    }

    @Test
    void modelMigrationShouldReplaceLegacyRechargeFieldsAndAllowSignedLedgerBalances() throws IOException {
        assertThat(MODEL_MIGRATION).exists();

        String sql = Files.readString(MODEL_MIGRATION);
        assertThat(sql)
                .contains("MODIFY COLUMN `balance_before` BIGINT NOT NULL")
                .contains("MODIFY COLUMN `balance_after` BIGINT NOT NULL")
                .contains("DROP COLUMN `prepay_id`")
                .contains("DROP COLUMN `payment_transaction_id`")
                .contains("`buy_quantity` BIGINT UNSIGNED NOT NULL")
                .contains("`wechat_order_id` VARCHAR(128) NULL")
                .contains("`bonus_gift_order_id` BIGINT UNSIGNED NULL")
                .contains("'WECHAT_SYNC'");
    }

    @Test
    void resetMigrationShouldClearTestLedgerAndCreateStableHistoricalGiftOrders() throws IOException {
        assertThat(RESET_MIGRATION).exists();

        String sql = Files.readString(RESET_MIGRATION);
        assertThat(sql)
                .containsSubsequence(
                        "DELETE FROM `wf_system_message`",
                        "DELETE FROM `wf_point_billing_window`",
                        "DELETE FROM `wf_point_meter`",
                        "DELETE FROM `wf_work_storage_monthly_bill`",
                        "DELETE FROM `wf_recharge_order`",
                        "DELETE FROM `wf_point_transaction`")
                .contains("`balance` = 0")
                .contains("`wechat_balance` = 0")
                .contains("`wechat_present_balance` = 0")
                .contains("`pending_debit` = 0")
                .contains("HISTORICAL_USER_MIGRATION_GIFT:")
                .contains("SHA2(")
                .contains("500")
                .doesNotContain("wf_referral_relation");
    }
}
