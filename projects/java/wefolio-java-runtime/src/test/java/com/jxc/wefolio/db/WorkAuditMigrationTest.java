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

    /** 作品审核拒绝原因迁移脚本路径 */
    private static final Path REJECT_REASON_MIGRATION_PATH =
            Path.of("src/main/resources/db/migration/V28__add_work_audit_reject_reason.sql");

    /** 作品审核轮次和稳定原因迁移脚本路径 */
    private static final Path AUDIT_ROUND_MIGRATION_PATH =
            Path.of("src/main/resources/db/migration/V45__add_work_audit_round.sql");

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
     * 迁移应为作品表补充审核拒绝原因字段，且使用固定长度字符串。
     *
     * @throws IOException 读取脚本失败时抛出
     */
    @Test
    void migrationShouldAddAuditRejectReasonToWorkTable() throws IOException {
        assertThat(REJECT_REASON_MIGRATION_PATH).exists();

        String sql = Files.readString(REJECT_REASON_MIGRATION_PATH);

        assertThat(sql).contains("ADD COLUMN `audit_reject_reason` VARCHAR(512) NULL");
        assertThat(sql).contains("AFTER `audit_status`");
        assertThat(sql).doesNotContain("TEXT");
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

    /**
     * 新迁移只调整审核轮次、稳定原因和任务唯一键结构，风险代码不做数据库枚举约束。
     *
     * @throws IOException 读取脚本失败时抛出
     */
    @Test
    void auditRoundMigrationShouldBeSchemaOnly() throws IOException {
        assertThat(AUDIT_ROUND_MIGRATION_PATH).exists();

        String sql = Files.readString(AUDIT_ROUND_MIGRATION_PATH);
        String upperSql = sql.toUpperCase();

        assertThat(sql)
                .contains("`audit_round` INT UNSIGNED NOT NULL DEFAULT 1")
                .contains("`audit_reason_code` VARCHAR(32) CHARACTER SET ascii COLLATE ascii_bin NULL")
                .contains("`audit_reason_codes` JSON NULL")
                .contains("CONSTRAINT `chk_work_audit_round` CHECK (`audit_round` >= 1)")
                .contains("CONSTRAINT `chk_work_audit_reason_codes` CHECK")
                .contains("JSON_TYPE(`audit_reason_codes`) = 'ARRAY'")
                .contains("JSON_LENGTH(`audit_reason_codes`) <= 20")
                .contains("MODIFY COLUMN `media_sha256` CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL")
                .contains("DROP INDEX `uk_work_audit_task_media`")
                .contains("UNIQUE KEY `uk_work_audit_task_media_round` "
                        + "(`work_id`, `media_sha256`, `audit_round`, `deleted`)");
        assertThat(sql)
                .doesNotContain("CONSTRAINT `chk_work_audit_reason_code`")
                .doesNotContain("`audit_reason_code` IS NULL OR `audit_reason_code` IN");
        assertThat(upperSql)
                .doesNotContain("UPDATE WF_WORK")
                .doesNotContain("UPDATE WF_WORK_AUDIT_TASK")
                .doesNotContain("INSERT INTO WF_WORK")
                .doesNotContain("DELETE FROM WF_WORK");
    }
}
