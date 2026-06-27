package com.jxc.wefolio.db;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 用户手机号唯一索引迁移测试 — 确认微信注册手机号具备数据库并发兜底。
 */
class UserPhoneUniqueMigrationTest {

    @Test
    void userPhoneMigrationReplacesPlainIndexWithDeletedAwareUniqueIndex() throws IOException {
        String sql = Files.readString(Path.of("src/main/resources/db/migration/V10__add_user_phone_unique_index.sql"));

        assertThat(sql).contains("DROP INDEX `idx_user_phone_number`");
        assertThat(sql).contains("ADD UNIQUE KEY `uk_user_phone_number` (`phone_number`, `deleted`)");
    }
}
