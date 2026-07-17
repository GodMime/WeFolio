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
}
