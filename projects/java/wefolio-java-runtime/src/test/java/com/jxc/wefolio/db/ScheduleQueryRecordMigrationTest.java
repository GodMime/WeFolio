package com.jxc.wefolio.db;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 查询档期记录迁移测试 — 锁定新表字段、索引和被排除字段。
 */
class ScheduleQueryRecordMigrationTest {

    @Test
    void migrationShouldCreateScheduleQueryRecordTableWithApprovedFields() throws IOException {
        Path migrationPath = Path.of("src/main/resources/db/migration/V26__schedule_query_record.sql");

        assertThat(migrationPath).exists();

        String sql = Files.readString(migrationPath);

        assertThat(sql).contains("CREATE TABLE `wf_schedule_query_record`");
        assertThat(sql).contains("`portfolio_id` BIGINT UNSIGNED NOT NULL");
        assertThat(sql).contains("`portfolio_type` VARCHAR(16) CHARACTER SET ascii COLLATE ascii_bin");
        assertThat(sql).contains("`portfolio_title_snapshot` VARCHAR(100) NOT NULL");
        assertThat(sql).contains("`visit_record_id` BIGINT UNSIGNED NOT NULL");
        assertThat(sql).contains("`visitor_id` BIGINT UNSIGNED NULL");
        assertThat(sql).contains("`visitor_key` CHAR(64) NOT NULL");
        assertThat(sql).contains("`owner_type` VARCHAR(16) CHARACTER SET ascii COLLATE ascii_bin");
        assertThat(sql).contains("`owner_id` BIGINT UNSIGNED NOT NULL");
        assertThat(sql).contains("`source_type` VARCHAR(32) CHARACTER SET ascii COLLATE ascii_bin");
        assertThat(sql).contains("`display_mode` VARCHAR(32) CHARACTER SET ascii COLLATE ascii_bin");
        assertThat(sql).contains("`queried_date` DATE NOT NULL");
        assertThat(sql).contains("`slot_definition_id` BIGINT UNSIGNED NOT NULL");
        assertThat(sql).contains("`slot_name_snapshot` VARCHAR(50) NOT NULL");
        assertThat(sql).contains("`start_time_snapshot` TIME NULL");
        assertThat(sql).contains("`end_time_snapshot` TIME NULL");
        assertThat(sql).contains("`color_snapshot` VARCHAR(16) NULL");
        assertThat(sql).contains("`result_status` VARCHAR(32) CHARACTER SET ascii COLLATE ascii_bin");
        assertThat(sql).contains("`result_status_text` VARCHAR(32) NOT NULL");
        assertThat(sql).contains("`available` TINYINT UNSIGNED NOT NULL");
        assertThat(sql).contains("`result_message` VARCHAR(100) NOT NULL");
        assertThat(sql).contains("`queried_at` DATETIME(3) NOT NULL");
        assertThat(sql).contains("KEY `idx_schedule_query_owner_portfolio_time`");
        assertThat(sql).contains("(`owner_type`, `owner_id`, `portfolio_type`, `queried_at`, `id`)");
        assertThat(sql)
                .doesNotContain("portfolio_share_code_snapshot")
                .doesNotContain("portfolio_revision")
                .doesNotContain("trigger_type")
                .doesNotContain("idempotency_key")
                .doesNotContain("component_key");
    }

    /**
     * 查档记录允许零时长档位快照，避免 start == end 的边界插入失败。
     */
    @Test
    void migrationShouldAllowEqualScheduleQueryStartAndEndTimeSnapshots() throws IOException {
        Path migrationPath = Path.of("src/main/resources/db/migration/V26__schedule_query_record.sql");

        assertThat(migrationPath).exists();

        String sql = Files.readString(migrationPath);

        assertThat(sql).contains(
                "CONSTRAINT `chk_schedule_query_time` CHECK (`start_time_snapshot` <= `end_time_snapshot`)"
        );
    }
}
