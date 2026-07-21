package com.jxc.wefolio.db;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 积分规则迁移测试 — 确认规则分组及作品集发布文案刷数脚本存在。
 */
class PointRuleMigrationTest {

    @Test
    void pointRuleGroupMigrationAddsColumnAndBackfillsSeedRules() throws IOException {
        String sql = Files.readString(Path.of("src/main/resources/db/migration/V9__add_point_rule_group.sql"));

        assertThat(sql).contains("ADD COLUMN `group_code`");
        assertThat(sql).contains("UPDATE `wf_point_rule`");
        assertThat(sql).contains("'MAINTENANCE'");
        assertThat(sql).contains("'VISITOR'");
        assertThat(sql).contains("UPLOAD_IMAGE");
        assertThat(sql).contains("VISIT_PERSONAL_PORTFOLIO");
    }

    /**
     * 作品集积分规则名称迁移应把维护文案更新为发布文案。
     */
    @Test
    void portfolioRuleNameMigrationUsesPublishCopy() throws IOException {
        Path migration = Path.of(
                "src/main/resources/db/migration/V34__rename_portfolio_publish_point_rules.sql");

        assertThat(migration).exists();
        assertThat(Files.readString(migration))
                .contains("MAINTAIN_STANDARD_PORTFOLIO")
                .contains("发布标准作品集")
                .contains("MAINTAIN_ADVANCED_PORTFOLIO")
                .contains("发布高级作品集");
    }
}
