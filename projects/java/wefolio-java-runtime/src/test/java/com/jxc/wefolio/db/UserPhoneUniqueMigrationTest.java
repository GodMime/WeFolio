package com.jxc.wefolio.db;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 用户手机号索引迁移测试 — 记录历史约束，并确认最终身份按原始微信 openid 唯一。
 */
class UserPhoneUniqueMigrationTest {

    /** V10 的历史唯一手机号约束不得通过修改已提交迁移移除。 */
    @Test
    void userPhoneMigrationReplacesPlainIndexWithDeletedAwareUniqueIndex() throws IOException {
        String sql = Files.readString(Path.of("src/main/resources/db/migration/V10__add_user_phone_unique_index.sql"));

        assertThat(sql).contains("DROP INDEX `idx_user_phone_number`");
        assertThat(sql).contains("ADD UNIQUE KEY `uk_user_phone_number` (`phone_number`, `deleted`)");
    }

    /** V60 允许同手机号多个独立微信账户，同时由精确 openid 唯一键兜底身份并发。 */
    @Test
    void exactWechatIdentityMigrationKeepsPhoneAsProfileAndOpenidUnique() throws IOException {
        String sql = Files.readString(Path.of("src/main/resources/db/migration/V60__use_exact_wechat_identity.sql"));
        String schema = Files.readString(Path.of("full-schema.sql"));

        assertThat(sql).contains("DROP INDEX `uk_user_phone_number`")
                .contains("ADD INDEX `idx_user_phone_number` (`phone_number`, `deleted`)")
                .contains("ADD UNIQUE KEY `uk_auth_type_open_id` (`auth_type`, `open_id`, `deleted`)")
                .contains("COLLATE utf8mb4_0900_bin");
        assertThat(schema).contains("KEY `idx_user_phone_number` (`phone_number`,`deleted`)")
                .doesNotContain("UNIQUE KEY `uk_user_phone_number`")
                .contains("UNIQUE KEY `uk_auth_type_open_id` (`auth_type`,`open_id`,`deleted`)");
    }
}
