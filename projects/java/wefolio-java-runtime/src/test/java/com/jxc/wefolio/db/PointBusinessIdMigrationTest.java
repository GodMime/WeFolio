package com.jxc.wefolio.db;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 积分业务 ID 扩容迁移测试 — 支持作品集、作品和访客 key 组合写入计量器和流水。
 */
class PointBusinessIdMigrationTest {

    @Test
    void migrationShouldExpandPointBusinessIdColumns() throws IOException {
        String sql = Files.readString(Path.of("src/main/resources/db/migration/V24__expand_point_business_id.sql"));

        assertThat(sql).contains("ALTER TABLE `wf_point_meter`");
        assertThat(sql).contains("MODIFY COLUMN `business_id` VARCHAR(128) NOT NULL COMMENT '计量业务ID'");
        assertThat(sql).contains("ALTER TABLE `wf_point_transaction`");
        assertThat(sql).contains("MODIFY COLUMN `business_id` VARCHAR(128) NOT NULL COMMENT '关联业务ID'");
    }
}
