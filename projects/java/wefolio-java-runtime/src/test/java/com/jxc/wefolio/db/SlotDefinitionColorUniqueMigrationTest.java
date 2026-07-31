package com.jxc.wefolio.db;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 档位定义颜色唯一索引 migration 测试。
 */
class SlotDefinitionColorUniqueMigrationTest {

    /** V46 migration 路径 */
    private static final Path MIGRATION = Path.of(
            "src/main/resources/db/migration/V46__add_slot_definition_color_unique_index.sql");

    /**
     * migration 必须为同一用户未删除档位增加颜色唯一索引。
     *
     * @throws Exception 文件读取失败
     */
    @Test
    void migrationShouldAddUserColorAndDeletedUniqueIndex() throws Exception {
        assertThat(MIGRATION).exists();

        String sql = Files.readString(MIGRATION).replaceAll("\\s+", " ").trim();

        assertThat(sql).containsIgnoringCase("ALTER TABLE `wf_slot_definition`");
        assertThat(sql).containsIgnoringCase(
                "ADD UNIQUE KEY `uk_slot_user_color_deleted` (`user_id`, `color`, `deleted`)");
    }
}
