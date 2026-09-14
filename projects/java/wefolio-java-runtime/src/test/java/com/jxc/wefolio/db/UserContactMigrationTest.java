package com.jxc.wefolio.db;

import org.junit.jupiter.api.Test;
import java.nio.file.Files;
import java.nio.file.Path;
import static org.assertj.core.api.Assertions.assertThat;

/** 联系字段增量迁移结构检查；不代替真实 MySQL 升级验证。 */
class UserContactMigrationTest {
    /** 只新增可空微信密文列，不回填或修改历史联系手机。 */
    @Test void addsNullableWechatWithoutBackfill() throws Exception {
        String sql = Files.readString(Path.of("src/main/resources/db/migration/V56__add_user_contact_wechat.sql"));
        assertThat(sql).contains("ADD COLUMN contact_wechat_ciphertext VARCHAR(512) NULL");
        assertThat(sql.toUpperCase()).doesNotContain("UPDATE WF_USER", "DELETE FROM", "DEFAULT 0", "MODIFY COLUMN");
    }
}
