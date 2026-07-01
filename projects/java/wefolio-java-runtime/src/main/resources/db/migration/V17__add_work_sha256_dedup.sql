-- ============================================================
-- WeFolio V17 — 作品 SHA-256 去重字段
-- ============================================================

ALTER TABLE `wf_work`
  ADD COLUMN `media_sha256` CHAR(64) CHARACTER SET ascii COLLATE ascii_bin
    NOT NULL COMMENT '原文件 SHA-256' AFTER `media_object_key`,
  MODIFY COLUMN `cover_object_key` VARCHAR(512) NOT NULL COMMENT 'COS 缩略图或封面对象键',
  ADD COLUMN `cover_sha256` CHAR(64) CHARACTER SET ascii COLLATE ascii_bin
    NOT NULL COMMENT '缩略图或封面 SHA-256' AFTER `cover_object_key`,
  ADD UNIQUE KEY `uk_work_user_media_sha256` (`user_id`, `media_sha256`, `deleted`);

ALTER TABLE `wf_work_upload_task`
  ADD COLUMN `file_sha256` CHAR(64) CHARACTER SET ascii COLLATE ascii_bin
    NOT NULL COMMENT '当前上传对象的 SHA-256' AFTER `object_key`;
