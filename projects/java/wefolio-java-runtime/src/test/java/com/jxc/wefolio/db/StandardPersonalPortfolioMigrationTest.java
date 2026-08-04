package com.jxc.wefolio.db;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 标准个人作品集迁移测试 — 锁定草稿/正式配置字段和引用作用域。
 */
class StandardPersonalPortfolioMigrationTest {

    @Test
    void migrationShouldReplaceFixedPortfolioFieldsWithDraftAndPublishedConfig() throws IOException {
        String sql = Files.readString(Path.of("src/main/resources/db/migration/V19__standard_personal_portfolio.sql"));

        assertThat(sql).contains("ALTER TABLE `wf_portfolio`");
        assertThat(sql).contains("DROP COLUMN `title`");
        assertThat(sql).contains("DROP COLUMN `intro`");
        assertThat(sql).contains("DROP COLUMN `share_cover_url`");
        assertThat(sql).contains("DROP COLUMN `share_avatar_url`");
        assertThat(sql).contains("DROP COLUMN `schema_json`");
        assertThat(sql).contains("ADD COLUMN `draft_config_json` JSON NULL COMMENT '草稿完整配置'");
        assertThat(sql).contains("ADD COLUMN `draft_revision` INT UNSIGNED NOT NULL DEFAULT 0 COMMENT '草稿版本号'");
        assertThat(sql).contains("ADD COLUMN `published_config_json` JSON NULL COMMENT '正式发布完整配置'");
        assertThat(sql).contains("ADD COLUMN `published_revision` INT UNSIGNED NOT NULL DEFAULT 0 COMMENT '正式发布版本号'");
        assertThat(sql).contains("ADD COLUMN `publication_status` VARCHAR(32) CHARACTER SET ascii COLLATE ascii_bin");
    }

    @Test
    void migrationShouldScopePortfolioReferencesByDraftAndPublishedConfig() throws IOException {
        String sql = Files.readString(Path.of("src/main/resources/db/migration/V19__standard_personal_portfolio.sql"));

        assertThat(sql).contains("ALTER TABLE `wf_portfolio_reference`");
        assertThat(sql).contains("ADD COLUMN `config_scope` VARCHAR(16) CHARACTER SET ascii COLLATE ascii_bin");
        assertThat(sql).contains("DROP INDEX `uk_portfolio_reference`");
        assertThat(sql).contains("ADD UNIQUE KEY `uk_portfolio_reference_scope`");
        assertThat(sql).contains("`portfolio_id`, `config_scope`, `component_path`, `reference_type`, `reference_id`");
        assertThat(sql).contains("ADD KEY `idx_reference_target_scope`");
        assertThat(sql).contains("`reference_type`, `reference_id`, `config_scope`, `is_valid`, `portfolio_id`");
    }

    @Test
    void migrationShouldAllowReferenceRebuildAfterLogicDelete() throws IOException {
        String sql = Files.readString(Path.of("src/main/resources/db/migration/V20__portfolio_reference_unique_deleted.sql"));

        assertThat(sql).contains("ALTER TABLE `wf_portfolio_reference`");
        assertThat(sql).contains("DROP INDEX `uk_portfolio_reference_scope`");
        assertThat(sql).contains("ADD UNIQUE KEY `uk_portfolio_reference_scope`");
        assertThat(sql).contains("`portfolio_id`, `config_scope`, `component_path`, `reference_type`, `reference_id`, `deleted`");
    }

    @Test
    void hyperlinkMigrationShouldAddLinkedPortfolioWithoutRemovingExistingReferenceTypes() throws IOException {
        String sql = Files.readString(Path.of(
                "src/main/resources/db/migration/V48__support_personal_portfolio_hyperlink.sql"));

        assertThat(sql).contains("DROP CHECK `chk_reference_type`");
        assertThat(sql).contains("'LINKED_PORTFOLIO'");
        assertThat(sql).contains(
                "'WORK'", "'MEMBER_PORTFOLIO'", "'USER_PROFILE'",
                "'TEAM_PROFILE'", "'SCHEDULE_COMPONENT'", "'QR_CODE_ASSET'"
        );
    }
}
