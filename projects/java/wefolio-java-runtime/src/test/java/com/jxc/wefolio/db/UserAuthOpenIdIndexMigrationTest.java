package com.jxc.wefolio.db;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/** 维护者微信 openid 查询索引 migration 测试。 */
class UserAuthOpenIdIndexMigrationTest {

    /** V44 migration 路径。 */
    private static final Path MIGRATION = Path.of(
            "src/main/resources/db/migration/V44__add_user_auth_open_id_index.sql");

    @Test
    void migrationShouldAddOpenIdAndDeletedCompositeIndex() throws IOException {
        assertThat(MIGRATION).exists();

        String sql = Files.readString(MIGRATION).replaceAll("\\s+", " ").trim();

        assertThat(sql).containsIgnoringCase("ALTER TABLE `wf_user_auth`");
        assertThat(sql).containsIgnoringCase(
                "ADD INDEX `idx_user_auth_open_id_deleted` (`open_id`, `deleted`)");
    }
}
