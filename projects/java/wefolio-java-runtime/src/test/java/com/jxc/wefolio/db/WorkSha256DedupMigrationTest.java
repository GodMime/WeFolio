package com.jxc.wefolio.db;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 作品 SHA-256 去重迁移测试 — 锁定必填字段和唯一索引结构。
 */
class WorkSha256DedupMigrationTest {

    @Test
    void migrationShouldAddRequiredWorkSha256ColumnsAndUniqueIndex() throws IOException {
        String sql = Files.readString(Path.of("src/main/resources/db/migration/V17__add_work_sha256_dedup.sql"));

        assertThat(sql).contains("ADD COLUMN `media_sha256` CHAR(64) CHARACTER SET ascii COLLATE ascii_bin");
        assertThat(sql).contains("NOT NULL COMMENT '原文件 SHA-256'");
        assertThat(sql).contains("MODIFY COLUMN `cover_object_key` VARCHAR(512) NOT NULL");
        assertThat(sql).contains("ADD COLUMN `cover_sha256` CHAR(64) CHARACTER SET ascii COLLATE ascii_bin");
        assertThat(sql).contains("UNIQUE KEY `uk_work_user_media_sha256` (`user_id`, `media_sha256`, `deleted`)");
    }

    @Test
    void migrationShouldAddUploadTaskFileSha256() throws IOException {
        String sql = Files.readString(Path.of("src/main/resources/db/migration/V17__add_work_sha256_dedup.sql"));

        assertThat(sql).contains("ALTER TABLE `wf_work_upload_task`");
        assertThat(sql).contains("ADD COLUMN `file_sha256` CHAR(64) CHARACTER SET ascii COLLATE ascii_bin");
        assertThat(sql).contains("NOT NULL COMMENT '当前上传对象的 SHA-256'");
    }
}
