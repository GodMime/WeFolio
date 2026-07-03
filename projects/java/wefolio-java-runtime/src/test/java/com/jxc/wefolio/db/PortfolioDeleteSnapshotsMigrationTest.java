package com.jxc.wefolio.db;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 作品集删除快照迁移测试 — 锁定访问记录和线索中的作品集标题快照字段。
 */
class PortfolioDeleteSnapshotsMigrationTest {

    @Test
    void migrationShouldAddPortfolioSnapshotsToVisitRecordsAndContactLeads() throws IOException {
        String sql = Files.readString(Path.of("src/main/resources/db/migration/V22__portfolio_delete_snapshots.sql"));

        assertThat(sql).contains("ALTER TABLE `wf_visit_record`");
        assertThat(sql).contains("`portfolio_title_snapshot` VARCHAR(100) NULL COMMENT '被访问作品集标题快照'");
        assertThat(sql).contains("`portfolio_share_code_snapshot` VARCHAR(32) NULL COMMENT '被访问作品集分享编码快照'");
        assertThat(sql).contains("ALTER TABLE `wf_contact_lead`");
        assertThat(sql).contains("`portfolio_title_snapshot` VARCHAR(100) NULL COMMENT '来源作品集标题快照'");
        assertThat(sql).contains("`portfolio_share_code_snapshot` VARCHAR(32) NULL COMMENT '来源作品集分享编码快照'");
    }
}
