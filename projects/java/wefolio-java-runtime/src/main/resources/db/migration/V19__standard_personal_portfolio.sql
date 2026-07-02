-- ============================================================
-- WeFolio V19 — 标准个人作品集草稿/正式配置
-- ============================================================

SET NAMES utf8mb4;

ALTER TABLE `wf_portfolio`
  DROP COLUMN `title`,
  DROP COLUMN `intro`,
  DROP COLUMN `share_cover_url`,
  DROP COLUMN `share_avatar_url`,
  DROP COLUMN `schema_json`,
  ADD COLUMN `draft_config_json` JSON NULL COMMENT '草稿完整配置' AFTER `schema_version`,
  ADD COLUMN `draft_revision` INT UNSIGNED NOT NULL DEFAULT 0 COMMENT '草稿版本号' AFTER `draft_config_json`,
  ADD COLUMN `draft_content_hash` CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NULL COMMENT '草稿配置SHA256' AFTER `draft_revision`,
  ADD COLUMN `draft_saved_by` BIGINT UNSIGNED NULL COMMENT '草稿最近保存人' AFTER `draft_content_hash`,
  ADD COLUMN `draft_saved_at` DATETIME(3) NULL COMMENT '草稿最近保存时间' AFTER `draft_saved_by`,
  ADD COLUMN `published_config_json` JSON NULL COMMENT '正式发布完整配置' AFTER `draft_saved_at`,
  ADD COLUMN `published_revision` INT UNSIGNED NOT NULL DEFAULT 0 COMMENT '正式发布版本号' AFTER `published_config_json`,
  ADD COLUMN `published_content_hash` CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NULL COMMENT '正式配置SHA256' AFTER `published_revision`,
  ADD COLUMN `published_by` BIGINT UNSIGNED NULL COMMENT '最近发布人' AFTER `published_content_hash`,
  ADD COLUMN `published_at` DATETIME(3) NULL COMMENT '最近发布时间' AFTER `published_by`,
  ADD COLUMN `publication_status` VARCHAR(32) CHARACTER SET ascii COLLATE ascii_bin
    NOT NULL DEFAULT 'DRAFT_ONLY' COMMENT 'DRAFT_ONLY PUBLISHED OFFLINE' AFTER `published_at`;

ALTER TABLE `wf_portfolio_reference`
  ADD COLUMN `config_scope` VARCHAR(16) CHARACTER SET ascii COLLATE ascii_bin
    NOT NULL DEFAULT 'PUBLISHED' COMMENT 'DRAFT 草稿 / PUBLISHED 正式' AFTER `portfolio_id`,
  DROP INDEX `uk_portfolio_reference`,
  ADD UNIQUE KEY `uk_portfolio_reference_scope`
    (`portfolio_id`, `config_scope`, `component_path`, `reference_type`, `reference_id`),
  ADD KEY `idx_reference_target_scope`
    (`reference_type`, `reference_id`, `config_scope`, `is_valid`, `portfolio_id`),
  ADD CONSTRAINT `chk_reference_config_scope` CHECK (`config_scope` IN ('DRAFT', 'PUBLISHED'));
