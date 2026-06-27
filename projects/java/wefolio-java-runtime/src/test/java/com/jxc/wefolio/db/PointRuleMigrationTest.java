package com.jxc.wefolio.db;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 积分规则迁移测试 — 确认规则分组字段和存量规则刷数脚本存在。
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
}
