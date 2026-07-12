package com.jxc.wefolio.db;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 团队查档记录迁移测试 — 锁定团队独立表的字段、索引和约束。
 */
class TeamScheduleQueryRecordMigrationTest {

    /** 验证迁移只创建团队查档表，并保留全部业务字段和默认值。 */
    @Test
    void migrationShouldCreateIndependentTeamScheduleQueryRecordTable() throws IOException {
        Path migrationPath = Path.of("src/main/resources/db/migration/V31__create_team_schedule_query_record.sql");

        assertThat(migrationPath).exists();

        String sql = Files.readString(migrationPath);

        assertThat(sql)
                .contains("CREATE TABLE `wf_team_schedule_query_record`")
                .doesNotContain("CREATE TABLE `wf_schedule_query_record`")
                .contains("`portfolio_id` BIGINT UNSIGNED NOT NULL")
                .contains("`portfolio_revision` INT UNSIGNED NOT NULL")
                .contains("`portfolio_title_snapshot` VARCHAR(100) NOT NULL")
                .contains("`team_id` BIGINT UNSIGNED NOT NULL")
                .contains("`visit_record_id` BIGINT UNSIGNED NOT NULL")
                .contains("`visitor_id` BIGINT UNSIGNED NULL")
                .contains("`visitor_key` CHAR(64) NOT NULL")
                .contains("`source_type` VARCHAR(32) CHARACTER SET ascii COLLATE ascii_bin")
                .contains("NOT NULL DEFAULT 'UNKNOWN'")
                .contains("`display_mode` VARCHAR(32) CHARACTER SET ascii COLLATE ascii_bin")
                .contains("NOT NULL DEFAULT 'MODAL_CALENDAR'")
                .contains("`queried_date` DATE NOT NULL")
                .contains("`result_status` VARCHAR(32) CHARACTER SET ascii COLLATE ascii_bin NOT NULL")
                .contains("`result_status_text` VARCHAR(32) NOT NULL")
                .contains("`available` TINYINT UNSIGNED NOT NULL")
                .contains("`result_message` VARCHAR(100) NOT NULL")
                .contains("`team_result_json` JSON NOT NULL")
                .contains("`available_member_count` INT UNSIGNED NOT NULL DEFAULT 0")
                .contains("`partial_available_member_count` INT UNSIGNED NOT NULL DEFAULT 0")
                .contains("`full_member_count` INT UNSIGNED NOT NULL DEFAULT 0")
                .contains("`queried_at` DATETIME(3) NOT NULL")
                .contains("`created_at` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3)")
                .contains("`updated_at` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3)")
                .contains("`deleted` BIGINT UNSIGNED NOT NULL DEFAULT 0")
                .contains("`version` INT UNSIGNED NOT NULL DEFAULT 0");
    }

    /** 验证迁移包含团队和访问记录查询所需索引以及结果合法性约束。 */
    @Test
    void migrationShouldDeclareTeamScheduleQueryIndexesAndConstraints() throws IOException {
        Path migrationPath = Path.of("src/main/resources/db/migration/V31__create_team_schedule_query_record.sql");

        assertThat(migrationPath).exists();

        String sql = Files.readString(migrationPath);

        assertThat(sql)
                .contains("KEY `idx_team_schedule_query_team_time` (`team_id`, `queried_at`, `id`)")
                .contains("KEY `idx_team_schedule_query_visit_time` (`visit_record_id`, `queried_at`)")
                .contains("KEY `idx_team_schedule_query_portfolio_time` (`portfolio_id`, `queried_at`)")
                .contains("CONSTRAINT `chk_team_schedule_query_status` CHECK")
                .contains("`result_status` IN ('TEAM_AVAILABLE', 'TEAM_PARTIAL_AVAILABLE', 'TEAM_FULL')")
                .contains("CONSTRAINT `chk_team_schedule_query_available` CHECK (`available` IN (0, 1))");
    }

    /** 验证迁移不夹带既有表变更，且索引和约束数量严格符合规格。 */
    @Test
    void migrationShouldContainOnlySpecifiedTableIndexesAndChecks() throws IOException {
        Path migrationPath = Path.of("src/main/resources/db/migration/V31__create_team_schedule_query_record.sql");

        assertThat(migrationPath).exists();

        String sql = Files.readString(migrationPath);

        assertThat(countMatches(sql, "(?im)^\\s*CREATE\\s+TABLE\\b")).isEqualTo(1);
        assertThat(sql).doesNotContainPattern("(?i)\\bFOREIGN\\s+KEY\\b");
        assertThat(sql).doesNotContainPattern("(?im)^\\s*(?:ALTER|UPDATE|INSERT|DELETE)\\b");

        assertThat(countMatches(sql, "(?im)^\\s*KEY\\s+`")).isEqualTo(3);
        assertThat(sql).containsOnlyOnce("KEY `idx_team_schedule_query_team_time`");
        assertThat(sql).containsOnlyOnce("KEY `idx_team_schedule_query_visit_time`");
        assertThat(sql).containsOnlyOnce("KEY `idx_team_schedule_query_portfolio_time`");

        assertThat(countMatches(sql, "(?i)\\bCHECK\\s*\\(")).isEqualTo(2);
        assertThat(sql).containsOnlyOnce("CONSTRAINT `chk_team_schedule_query_status` CHECK");
        assertThat(sql).containsOnlyOnce("CONSTRAINT `chk_team_schedule_query_available` CHECK");
    }

    /** 统计正则表达式在 SQL 中的匹配次数。 */
    private int countMatches(String content, String expression) {
        Matcher matcher = Pattern.compile(expression).matcher(content);
        int count = 0;
        while (matcher.find()) {
            count++;
        }
        return count;
    }
}
