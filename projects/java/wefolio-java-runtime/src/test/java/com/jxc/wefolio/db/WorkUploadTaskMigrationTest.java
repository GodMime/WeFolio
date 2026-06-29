package com.jxc.wefolio.db;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 作品上传任务迁移测试 — 固定直传 COS 上传任务表的核心字段和约束。
 */
class WorkUploadTaskMigrationTest {

    @Test
    void migrationCreatesWorkUploadTaskTableWithStatusAndIdempotencyConstraints() throws IOException {
        String sql = Files.readString(Path.of("src/main/resources/db/migration/V15__add_work_upload_task.sql"));

        assertThat(sql).contains("CREATE TABLE `wf_work_upload_task`");
        assertThat(sql).contains("`batch_id` VARCHAR(64) NOT NULL");
        assertThat(sql).contains("`object_key` VARCHAR(512) NOT NULL");
        assertThat(sql).contains("`status` VARCHAR(32) CHARACTER SET ascii COLLATE ascii_bin");
        assertThat(sql).contains("'CREATED'", "'UPLOADED'", "'CONFIRMED'", "'FAILED'", "'EXPIRED'");
        assertThat(sql).contains("UNIQUE KEY `uk_work_upload_task_idempotency` (`user_id`, `idempotency_key`, `deleted`)");
        assertThat(sql).contains("KEY `idx_work_upload_task_user_status` (`user_id`, `status`, `expires_at`)");
        assertThat(sql).contains("CONSTRAINT `chk_work_upload_task_media_type` CHECK");
        assertThat(sql).contains("CONSTRAINT `chk_work_upload_task_status` CHECK");
    }
}
