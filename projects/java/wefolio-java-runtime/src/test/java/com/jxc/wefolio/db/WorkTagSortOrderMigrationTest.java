package com.jxc.wefolio.db;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 作品标签排序迁移测试 — 固定标签内独立排序字段和查询索引。
 */
class WorkTagSortOrderMigrationTest {

    /** 作品标签排序迁移脚本 */
    private static final String MIGRATION =
            "src/main/resources/db/migration/V18__add_work_tag_sort_order.sql";

    @Test
    void migrationAddsTagScopedSortOrderAndIndex() throws IOException {
        String sql = Files.readString(Path.of(MIGRATION));

        assertThat(sql).contains("ADD COLUMN `sort_order` INT NOT NULL DEFAULT 0 COMMENT '标签内排序值'");
        assertThat(sql).contains("ADD KEY `idx_work_tag_user_tag_sort`");
        assertThat(sql).contains("`user_id`, `tag_id`, `deleted`, `sort_order`, `work_id`");
        assertThat(sql).contains("UPDATE `wf_work_tag` wt");
        assertThat(sql).contains("JOIN `wf_work` w ON w.`id` = wt.`work_id`");
        assertThat(sql).contains("SET wt.`sort_order` = w.`sort_order`");
    }
}
