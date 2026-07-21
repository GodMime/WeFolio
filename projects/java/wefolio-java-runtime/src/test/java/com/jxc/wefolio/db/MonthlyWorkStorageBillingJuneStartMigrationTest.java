package com.jxc.wefolio.db;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 作品存储月费首个账期调整迁移测试。
 */
class MonthlyWorkStorageBillingJuneStartMigrationTest {

    /** V33 migration 路径 */
    private static final Path MIGRATION = Path.of(
            "src/main/resources/db/migration/V33__move_monthly_work_storage_billing_start_to_june.sql");

    @Test
    void migrationShouldMoveOnlyVersionOneMonthlyStorageRuleToJune() throws IOException {
        assertThat(MIGRATION).exists();
        String sql = Files.readString(MIGRATION);

        assertThat(sql)
                .contains("UPDATE `wf_point_rule`")
                .contains("`effective_from` = '2026-06-01 00:00:00.000'")
                .contains("`updated_at` = CURRENT_TIMESTAMP(3)")
                .contains("`rule_code` = 'MONTHLY_WORK_STORAGE'")
                .contains("`rule_version` = 1")
                .contains("`deleted` = 0")
                .doesNotContain("wf_point_transaction")
                .doesNotContain("uk_point_tx_idempotency");
    }
}
