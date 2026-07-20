package com.jxc.wefolio.db;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 作品存储月费七月账期生效迁移测试。
 */
class MonthlyStorageRuleJulyStartMigrationTest {

    /** V42 migration 路径。 */
    private static final Path MIGRATION = Path.of(
            "src/main/resources/db/migration/V42__move_monthly_storage_rule_to_july.sql");

    /** 版本一结束时间和版本二开始时间必须同时移动到七月账期边界。 */
    @Test
    void migrationShouldMoveMonthlyStorageRuleCutoverToJuly() throws IOException {
        assertThat(MIGRATION).exists();
        String sql = Files.readString(MIGRATION).replaceAll("\\s+", " ");

        assertThat(sql)
                .contains("UPDATE `wf_point_rule`")
                .contains("WHEN `rule_version` = 2 THEN '2026-07-01 00:00:00.000'")
                .contains("WHEN `rule_version` = 1 THEN '2026-07-01 00:00:00.000'")
                .contains("WHERE `rule_code` = 'MONTHLY_WORK_STORAGE'")
                .contains("`rule_version` IN (1, 2)")
                .contains("`deleted` = 0")
                .doesNotContain("INSERT INTO `wf_point_rule`");
    }
}
