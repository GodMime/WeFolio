package com.jxc.wefolio.db;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 访客积分滚动扣费窗口迁移测试。
 */
class PointBillingWindowMigrationTest {

    /** 窗口表必须完整约束全局访客与业务作用域。 */
    @Test
    void billingWindowMigrationDefinesIdentityAndDictionaryChecks() throws IOException {
        Path migration = Path.of("src/main/resources/db/migration/V35__create_point_billing_window.sql");

        assertThat(migration).exists();
        assertThat(Files.readString(migration))
                .contains("CREATE TABLE `wf_point_billing_window`")
                .contains("`visitor_id` BIGINT UNSIGNED NOT NULL")
                .contains("`scope_type` VARCHAR(32) CHARACTER SET ascii COLLATE ascii_bin")
                .contains("`last_charged_at` DATETIME(3) NULL")
                .contains("UNIQUE KEY `uk_point_billing_window_identity`")
                .contains("`user_id`, `scene_code`, `visitor_id`, `scope_type`, `scope_id`, `deleted`")
                .contains("'VISIT_PERSONAL_PORTFOLIO'")
                .contains("'VIEW_PORTFOLIO_IMAGES'")
                .contains("'VIEW_PORTFOLIO_VIDEO'")
                .contains("`scope_type` IN ('PORTFOLIO', 'WORK')");
    }

    /** 六项规则必须在同一切换时间启用版本二。 */
    @Test
    void pointRuleMigrationCreatesSixVersionTwoRulesWithRollingWindowConfig() throws IOException {
        Path migration = Path.of("src/main/resources/db/migration/V36__revise_point_consumption_rules.sql");

        assertThat(migration).exists();
        String sql = Files.readString(migration);
        String normalizedSql = sql.replaceAll("\\s+", " ");
        assertThat(normalizedSql)
                .contains("SET @point_rule_cutover = CURRENT_TIMESTAMP(3)")
                .contains("`effective_to` = @point_rule_cutover")
                .contains("'MAINTAIN_STANDARD_PORTFOLIO', 2, '发布标准作品集'")
                .contains("'UPLOAD_IMAGE', 2, '上传图片作品'")
                .contains("'UPLOAD_VIDEO', 2, '上传视频作品'")
                .contains("'VISIT_PERSONAL_PORTFOLIO', 2, '访问个人作品集'")
                .contains("'VIEW_PORTFOLIO_IMAGES', 2, '查看作品集图片'")
                .contains("'VIEW_PORTFOLIO_VIDEO', 2, '查看作品集视频'")
                .contains("'FIXED_PER_ACTION', 1, 5")
                .contains("'FIXED_PER_ACTION', 1, 10")
                .contains("'dedupeWindowHours', 2, 'dedupeScope', 'PORTFOLIO'")
                .contains("'dedupeWindowHours', 2, 'dedupeScope', 'WORK'")
                .doesNotContain("'ACCUMULATED_THRESHOLD', 10, 1");
    }
}
