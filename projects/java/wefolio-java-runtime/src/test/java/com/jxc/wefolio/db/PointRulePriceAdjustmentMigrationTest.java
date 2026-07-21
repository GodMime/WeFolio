package com.jxc.wefolio.db;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 积分规则价格调整迁移测试。
 */
class PointRulePriceAdjustmentMigrationTest {

    /** V41 migration 路径。 */
    private static final Path MIGRATION = Path.of(
            "src/main/resources/db/migration/V41__revise_point_rule_prices.sql");

    /** 五项即时规则必须关闭旧版本并写入指定的新版本与积分值。 */
    @Test
    void migrationShouldVersionImmediatePointRules() throws IOException {
        String sql = normalizedSql();

        assertThat(sql)
                .contains("SET @point_rule_cutover = CURRENT_TIMESTAMP(3)")
                .contains("'CREATE_TEAM', 2, '新建团队', "
                        + "'CONSUMPTION', 'CREATE_TEAM', 'MAINTENANCE', "
                        + "'FIXED_PER_ACTION', 1, 2000")
                .contains("'MAINTAIN_ADVANCED_PORTFOLIO', 2, '发布高级作品集', "
                        + "'CONSUMPTION', 'MAINTAIN_ADVANCED_PORTFOLIO', 'MAINTENANCE', "
                        + "'FIXED_PER_ACTION', 1, 20")
                .contains("'MAINTAIN_STANDARD_PORTFOLIO', 3, '发布标准作品集', "
                        + "'CONSUMPTION', 'MAINTAIN_STANDARD_PORTFOLIO', 'MAINTENANCE', "
                        + "'FIXED_PER_ACTION', 1, 10")
                .contains("'VIEW_PORTFOLIO_VIDEO', 3, '查看作品集视频', "
                        + "'CONSUMPTION', 'VIEW_PORTFOLIO_VIDEO', 'VISITOR', "
                        + "'FIXED_PER_ACTION', 1, 10")
                .contains("'VISIT_PERSONAL_PORTFOLIO', 3, '访问个人作品集', "
                        + "'CONSUMPTION', 'VISIT_PERSONAL_PORTFOLIO', 'VISITOR', "
                        + "'FIXED_PER_ACTION', 1, 10");
    }

    /** 访客规则必须保留两小时窗口与原作用域。 */
    @Test
    void migrationShouldKeepVisitorRollingWindowConfig() throws IOException {
        assertThat(normalizedSql())
                .contains("'dedupeWindowHours', 2, 'dedupeScope', 'PORTFOLIO'")
                .contains("'dedupeWindowHours', 2, 'dedupeScope', 'WORK'");
    }

    /** 月费规则必须从 2026-08 账期切换为每完整 2MB 一分。 */
    @Test
    void migrationShouldVersionMonthlyStorageRuleFromAugust() throws IOException {
        assertThat(normalizedSql())
                .contains("SET @monthly_storage_cutover = '2026-08-01 00:00:00.000'")
                .contains("'MONTHLY_WORK_STORAGE', 2, '作品存储月费', "
                        + "'CONSUMPTION', 'MONTHLY_WORK_STORAGE', 'MAINTENANCE', "
                        + "'MONTHLY_STORAGE_SIZE', 2, 1")
                .doesNotContain("'UPLOAD_VIDEO', 3");
    }

    /** 读取并压平 migration，避免断言依赖排版。 */
    private String normalizedSql() throws IOException {
        assertThat(MIGRATION).exists();
        return Files.readString(MIGRATION).replaceAll("\\s+", " ");
    }
}
