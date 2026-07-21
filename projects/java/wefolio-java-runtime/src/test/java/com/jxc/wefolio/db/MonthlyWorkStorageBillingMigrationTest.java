package com.jxc.wefolio.db;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 月度作品存储计费迁移测试 — 固定账单表、积分规则及既有流水幂等索引契约。
 */
class MonthlyWorkStorageBillingMigrationTest {

    /** V32 migration 路径 */
    private static final Path MIGRATION = Path.of(
            "src/main/resources/db/migration/V32__add_monthly_work_storage_billing.sql");

    @Test
    void migrationShouldCreateMonthlyWorkStorageBillTable() throws IOException {
        String sql = migrationSql();

        assertThat(sql)
                .contains("CREATE TABLE `wf_work_storage_monthly_bill`")
                .contains("`user_id` BIGINT UNSIGNED NOT NULL")
                .contains("`billing_month` DATE NOT NULL")
                .contains("`work_count` BIGINT UNSIGNED NOT NULL")
                .contains("`total_file_size_bytes` BIGINT UNSIGNED NOT NULL")
                .contains("`points_due` BIGINT UNSIGNED NOT NULL")
                .contains("`points_deducted` BIGINT UNSIGNED NOT NULL")
                .contains("`points_shortfall` BIGINT UNSIGNED NOT NULL")
                .contains("`created_at` DATETIME(3)")
                .contains("`updated_at` DATETIME(3)")
                .contains("`deleted` BIGINT UNSIGNED NOT NULL")
                .contains("`version` INT UNSIGNED NOT NULL");
    }

    @Test
    void migrationShouldDeclareUserMonthDeletedUniqueIndexAndChecks() throws IOException {
        assertThat(migrationSql())
                .contains("UNIQUE KEY `uk_work_storage_bill_user_month`\n    (`user_id`, `billing_month`, `deleted`)")
                .contains("'NO_CHARGE', 'CHARGED', 'PARTIAL'")
                .contains("`points_deducted` <= `points_due`")
                .contains("`points_shortfall` = `points_due` - `points_deducted`");
    }

    @Test
    void migrationShouldReplaceCalcModeCheckAndSeedRule() throws IOException {
        String sql = migrationSql();

        assertThat(sql)
                .contains("DROP CHECK `chk_point_rule_calc_mode`")
                .contains("ADD CONSTRAINT `chk_point_rule_calc_mode` CHECK")
                .contains("'FIXED_PER_ACTION'")
                .contains("'ACCUMULATED_THRESHOLD'")
                .contains("'RECHARGE_PACKAGE'")
                .contains("'MANUAL_ADJUSTMENT'")
                .contains("'MONTHLY_STORAGE_SIZE'")
                .contains("'MONTHLY_WORK_STORAGE'")
                .contains("'MAINTENANCE'")
                .contains("'2026-07-01 00:00:00.000'")
                .containsSubsequence("'MONTHLY_STORAGE_SIZE'", "10", "1");
    }

    @Test
    void pointTransactionIdempotencyIndexShouldRemainUnchanged() throws IOException {
        assertThat(migrationSql())
                .doesNotContain("ALTER TABLE `wf_point_transaction`")
                .doesNotContain("uk_point_tx_idempotency");
    }

    @Test
    void pointRuleSeedShouldUseVersionColumnAfterV5Rename() throws IOException {
        assertThat(migrationSql())
                .contains("`version`")
                .doesNotContain("lock_version");
    }

    private String migrationSql() throws IOException {
        return Files.readString(MIGRATION);
    }
}
