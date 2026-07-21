package com.jxc.wefolio.db;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 充值套餐迁移测试 — 固定四个首版套餐及不可覆盖式初始化约束。
 */
class RechargePackageMigrationTest {

    /** V37 充值套餐 migration 路径。 */
    private static final Path MIGRATION = Path.of(
            "src/main/resources/db/migration/V37__seed_recharge_packages.sql");

    /** V40 代币兑换比例对齐 migration 路径。 */
    private static final Path TOKEN_RATIO_MIGRATION = Path.of(
            "src/main/resources/db/migration/V40__align_recharge_packages_with_token_ratio.sql");

    @Test
    void migrationShouldSeedFourVersionOnePackages() throws IOException {
        assertThat(MIGRATION).exists();

        String sql = Files.readString(MIGRATION);
        assertThat(sql)
                .contains("'RECHARGE_1_YUAN', 1, '1 元档'")
                .containsSubsequence("100", "10", "0", "10", "10")
                .contains("'RECHARGE_10_YUAN', 1, '10 元档'")
                .containsSubsequence("1000", "100", "0", "100", "20")
                .contains("'RECHARGE_50_YUAN', 1, '50 元档'")
                .containsSubsequence("5000", "500", "20", "520", "30")
                .contains("'RECHARGE_100_YUAN', 1, '100 元档'")
                .containsSubsequence("10000", "1000", "100", "1100", "40");
    }

    @Test
    void migrationShouldExposeDataConflictsInsteadOfOverwritingPackages() throws IOException {
        assertThat(MIGRATION).exists();

        assertThat(Files.readString(MIGRATION))
                .doesNotContainIgnoringCase("INSERT IGNORE")
                .doesNotContainIgnoringCase("REPLACE INTO")
                .doesNotContainIgnoringCase("ON DUPLICATE KEY UPDATE");
    }

    @Test
    void tokenRatioMigrationShouldUpdateExistingPackagesInPlace() throws IOException {
        assertThat(TOKEN_RATIO_MIGRATION).exists();

        String sql = Files.readString(TOKEN_RATIO_MIGRATION);
        assertThat(sql)
                .contains("UPDATE `wf_recharge_package`")
                .containsSubsequence(
                        "`base_points` = CASE `package_code`",
                        "WHEN 'RECHARGE_1_YUAN' THEN 100",
                        "WHEN 'RECHARGE_10_YUAN' THEN 1000",
                        "WHEN 'RECHARGE_50_YUAN' THEN 5000",
                        "WHEN 'RECHARGE_100_YUAN' THEN 10000",
                        "`bonus_points` = CASE `package_code`",
                        "WHEN 'RECHARGE_1_YUAN' THEN 0",
                        "WHEN 'RECHARGE_10_YUAN' THEN 0",
                        "WHEN 'RECHARGE_50_YUAN' THEN 200",
                        "WHEN 'RECHARGE_100_YUAN' THEN 1000",
                        "`total_points` = CASE `package_code`",
                        "WHEN 'RECHARGE_1_YUAN' THEN 100",
                        "WHEN 'RECHARGE_10_YUAN' THEN 1000",
                        "WHEN 'RECHARGE_50_YUAN' THEN 5200",
                        "WHEN 'RECHARGE_100_YUAN' THEN 11000")
                .contains("`package_version` = 1")
                .contains("`status` = 'ACTIVE'")
                .contains("`deleted` = 0")
                .doesNotContain("`amount_fen` =")
                .doesNotContain("wf_point_rule");
    }
}
