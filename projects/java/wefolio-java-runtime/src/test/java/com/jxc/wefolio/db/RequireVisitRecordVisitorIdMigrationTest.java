package com.jxc.wefolio.db;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 访问汇总访客 ID 必填迁移测试 — 锁定历史数据回填和字段非空约束。
 */
class RequireVisitRecordVisitorIdMigrationTest {

    @Test
    void migrationShouldBackfillVisitorIdAndRequireIt() throws IOException {
        Path migrationPath = Path.of("src/main/resources/db/migration/V25__require_visit_record_visitor_id.sql");

        assertThat(migrationPath).exists();

        String sql = Files.readString(migrationPath);

        assertThat(sql).contains("DROP INDEX `uk_visit_visitor_portfolio`");
        assertThat(sql).contains("ADD KEY `idx_visit_visitor_portfolio` (`visitor_id`, `portfolio_id`, `deleted`)");
        assertThat(sql).contains("UPDATE `wf_visit_record` AS `visit_record`");
        assertThat(sql).contains("SELECT `id` FROM `wf_visitor` WHERE `deleted` = 0 ORDER BY `id` ASC LIMIT 1");
        assertThat(sql).contains("WHERE `visit_record`.`visitor_id` IS NULL");
        assertThat(sql).contains("ALTER TABLE `wf_visit_record`");
        assertThat(sql).contains("MODIFY COLUMN `visitor_id` BIGINT UNSIGNED NOT NULL COMMENT '全局访客 ID'");
    }
}
