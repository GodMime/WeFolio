package com.jxc.wefolio.db;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 访客资料迁移测试 — 锁定全局访客表和访问汇总关联字段。
 */
class VisitorProfileMigrationTest {

    @Test
    void migrationShouldCreateGlobalVisitorTableAndLinkVisitRecords() throws IOException {
        String sql = Files.readString(Path.of("src/main/resources/db/migration/V23__visitor_profile.sql"));

        assertThat(sql).contains("CREATE TABLE `wf_visitor`");
        assertThat(sql).contains("`openid` VARCHAR(128) NOT NULL COMMENT '微信 openid 明文'");
        assertThat(sql).contains("UNIQUE KEY `uk_visitor_openid` (`openid`, `deleted`)");
        assertThat(sql).contains("UNIQUE KEY `uk_visitor_key` (`visitor_key`, `deleted`)");
        assertThat(sql).contains("ALTER TABLE `wf_visit_record`");
        assertThat(sql).contains("ADD COLUMN `visitor_id` BIGINT UNSIGNED NULL COMMENT '全局访客 ID'");
        assertThat(sql).contains("DROP INDEX `uk_visit_visitor_portfolio`");
        assertThat(sql).contains("ADD UNIQUE KEY `uk_visit_visitor_portfolio` (`visitor_id`, `portfolio_id`, `deleted`)");
        assertThat(sql).contains("ADD KEY `idx_visit_visitor` (`visitor_id`, `last_visited_at`)");
    }
}
