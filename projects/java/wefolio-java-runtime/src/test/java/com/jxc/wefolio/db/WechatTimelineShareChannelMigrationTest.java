package com.jxc.wefolio.db;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 微信朋友圈分享渠道迁移测试。
 */
class WechatTimelineShareChannelMigrationTest {

    /** V43 migration 路径。 */
    private static final Path MIGRATION_PATH = Path.of(
            "src/main/resources/db/migration/V43__add_wechat_timeline_share_channel.sql");

    @Test
    void migrationShouldExtendShareChannelCheckWithoutAddingColumns() throws IOException {
        String sql = Files.readString(MIGRATION_PATH);

        assertThat(sql).contains("ALTER TABLE `wf_portfolio_share_record`");
        assertThat(sql).contains("DROP CHECK `chk_share_channel`");
        assertThat(sql).contains("CONSTRAINT `chk_share_channel` CHECK");
        assertThat(sql).contains("'WECHAT_CARD'", "'WECHAT_TIMELINE'", "'QR_CODE'", "'COPIED_PATH'");
        assertThat(sql).doesNotContain("ADD COLUMN", "DROP COLUMN", "MODIFY COLUMN", "CHANGE COLUMN");
    }
}
