package com.jxc.wefolio.db;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 作品长宽比迁移测试 — 锁定作品表长宽比字段结构。
 */
class WorkAspectRatioMigrationTest {

    /** 作品长宽比迁移脚本路径 */
    private static final Path MIGRATION_PATH =
            Path.of("src/main/resources/db/migration/V29__add_work_aspect_ratio.sql");

    /**
     * 迁移应为作品表补充可空长宽比字段，且不回填历史数据。
     *
     * @throws IOException 读取脚本失败时抛出
     */
    @Test
    void migrationShouldAddAspectRatioToWorkTable() throws IOException {
        assertThat(MIGRATION_PATH).exists();

        String sql = Files.readString(MIGRATION_PATH);

        assertThat(sql).contains("ADD COLUMN `aspect_ratio` VARCHAR(32) NULL COMMENT '长宽比'");
        assertThat(sql).contains("AFTER `height`");
        assertThat(sql).doesNotContain("UPDATE `wf_work`");
    }
}
