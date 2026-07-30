package com.jxc.wefolio.db;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 动图作品数据库迁移测试。
 */
class AnimationWorkMigrationTest {

    /** 动图作品迁移脚本路径。 */
    private static final Path MIGRATION_PATH =
            Path.of("src/main/resources/db/migration/V47__support_animation_works.sql");

    /**
     * 迁移应扩充三张业务表并保留明确的媒体类型约束。
     *
     * @throws IOException 读取迁移失败时抛出
     */
    @Test
    void migrationShouldAddAnimationColumnsAndConstraints() throws IOException {
        assertThat(MIGRATION_PATH).exists();

        String sql = Files.readString(MIGRATION_PATH);

        assertThat(sql)
                .contains("DROP CHECK `chk_work_media_type`")
                .contains("DROP CHECK `chk_work_upload_task_media_type`")
                .contains("DROP CHECK `chk_work_audit_task_media_type`")
                .contains("`media_type` IN ('IMAGE', 'VIDEO', 'ANIMATION')")
                .contains("ADD COLUMN `frame_count`")
                .contains("ADD COLUMN `cover_frame_number`")
                .contains("ADD COLUMN `cover_sha256`")
                .contains("ADD COLUMN `sampled_frame_numbers`")
                .contains("CONSTRAINT `chk_work_animation_frames`")
                .contains("AND `frame_count` IS NOT NULL")
                .contains("AND `cover_frame_number` IS NOT NULL")
                .contains("CONSTRAINT `chk_work_upload_task_animation_frames`")
                .contains("CONSTRAINT `chk_work_audit_task_animation_frames`")
                .contains("AND `sampled_frame_numbers` IS NOT NULL")
                .doesNotContain("AS UNSIGNED");
        assertThat(sql).containsPattern("AS SIGNED\\)\\s+BETWEEN 1 AND 300");
    }

    /**
     * 迁移应增加独立动图上传积分规则，并沿用既有配置结构。
     *
     * @throws IOException 读取迁移失败时抛出
     */
    @Test
    void migrationShouldSeedAnimationUploadPointRule() throws IOException {
        assertThat(MIGRATION_PATH).exists();

        String sql = Files.readString(MIGRATION_PATH);

        assertThat(sql)
                .contains("'UPLOAD_ANIMATION', 1, '上传动图作品'")
                .contains("'CONSUMPTION', 'UPLOAD_ANIMATION', 'MAINTENANCE'")
                .contains("'FIXED_PER_ACTION', 1, 5")
                .contains("JSON_OBJECT('source', 'ANIMATION_WORK_SUPPORT_2026_07_30')")
                .doesNotContain("'dedupeWindowHours'")
                .doesNotContain("'dedupeScope'");
    }
}
