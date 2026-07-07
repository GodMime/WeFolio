package com.jxc.wefolio.db;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 作品审核迁移测试 — 锁定审核状态字段和审核任务表结构。
 */
class WorkAuditMigrationTest {

    /** 作品审核迁移脚本路径 */
    private static final Path MIGRATION_PATH =
            Path.of("src/main/resources/db/migration/V27__add_work_audit.sql");

    /**
     * 迁移应为作品表补充审核状态字段和扫描索引。
     *
     * @throws IOException 读取脚本失败时抛出
     */
    @Test
    void migrationShouldAddAuditStatusToWorkTable() throws IOException {
        assertThat(MIGRATION_PATH).exists();

        String sql = Files.readString(MIGRATION_PATH);

        assertThat(sql).contains("ADD COLUMN `audit_status` VARCHAR(32) CHARACTER SET ascii COLLATE ascii_bin");
        assertThat(sql).contains("NOT NULL DEFAULT 'PENDING'");
        assertThat(sql).contains("ADD KEY `idx_work_audit_scan` (`audit_status`, `media_type`, `deleted`, `id`)");
        assertThat(sql).contains("CONSTRAINT `chk_work_audit_status` CHECK");
        assertThat(sql).contains("'PENDING'", "'AUDITING'", "'PASSED'", "'REJECTED'", "'REVIEW_REQUIRED'", "'FAILED'");
    }

    /**
     * 迁移应创建 job 工程使用的作品审核任务表。
     *
     * @throws IOException 读取脚本失败时抛出
     */
    @Test
    void migrationShouldCreateWorkAuditTaskTable() throws IOException {
        assertThat(MIGRATION_PATH).exists();

        String sql = Files.readString(MIGRATION_PATH);

        assertThat(sql).contains("CREATE TABLE `wf_work_audit_task`");
        assertThat(sql).contains("`work_id` BIGINT UNSIGNED NOT NULL");
        assertThat(sql).contains("`media_object_key` VARCHAR(512) NOT NULL");
        assertThat(sql).contains("`media_sha256` CHAR(64) CHARACTER SET ascii COLLATE ascii_bin\n    NULL");
        assertThat(sql).contains("`task_status` VARCHAR(32) CHARACTER SET ascii COLLATE ascii_bin");
        assertThat(sql).contains("`audit_result` VARCHAR(32) CHARACTER SET ascii COLLATE ascii_bin");
        assertThat(sql).contains("`ci_job_id` VARCHAR(128) NULL");
        assertThat(sql).contains("`snapshot_interval_seconds` INT UNSIGNED NULL");
        assertThat(sql).contains("`snapshot_count` INT UNSIGNED NULL");
        assertThat(sql).contains("`query_count` INT UNSIGNED NOT NULL DEFAULT 0");
        assertThat(sql).contains("`last_error_message` VARCHAR(1000) NULL");
        assertThat(sql).contains("`request_payload` JSON NULL");
        assertThat(sql).contains("`response_payload` JSON NULL");
        assertThat(sql).contains("KEY `idx_work_audit_task_video_query` (`media_type`, `task_status`, `deleted`, `id`)");
        assertThat(sql).contains("UNIQUE KEY `uk_work_audit_task_media` (`work_id`, `media_sha256`, `deleted`)");
    }

    /**
     * 迁移应约束审核任务状态和结果枚举。
     *
     * @throws IOException 读取脚本失败时抛出
     */
    @Test
    void migrationShouldDeclareTaskStatusAndResultChecks() throws IOException {
        assertThat(MIGRATION_PATH).exists();

        String sql = Files.readString(MIGRATION_PATH);

        assertThat(sql).contains("CONSTRAINT `chk_work_audit_task_status` CHECK");
        assertThat(sql).contains("'SUBMITTING'", "'SUBMITTED'", "'RUNNING'", "'QUERYING'", "'SUCCESS'", "'FAILED'");
        assertThat(sql).contains("CONSTRAINT `chk_work_audit_task_result` CHECK");
        assertThat(sql).contains("'PASS'", "'BLOCK'", "'REVIEW'", "'UNKNOWN'");
    }
}
