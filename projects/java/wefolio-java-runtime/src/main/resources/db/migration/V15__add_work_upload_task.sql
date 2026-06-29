-- ============================================================
-- WeFolio V15 — 作品直传 COS 上传任务表
-- ============================================================

CREATE TABLE `wf_work_upload_task` (
  `id` BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '主键',
  `batch_id` VARCHAR(64) NOT NULL COMMENT '批量上传批次 ID',
  `user_id` BIGINT UNSIGNED NOT NULL COMMENT '所属用户 ID',
  `media_type` VARCHAR(16) CHARACTER SET ascii COLLATE ascii_bin
    NOT NULL COMMENT 'IMAGE 图片 VIDEO 视频',
  `object_key` VARCHAR(512) NOT NULL COMMENT '后端生成的 COS 原文件对象键',
  `cover_object_key` VARCHAR(512) NULL COMMENT '封面对象键，图片作品默认等于原文件',
  `original_file_name` VARCHAR(255) NULL COMMENT '原始文件名',
  `mime_type` VARCHAR(100) NULL COMMENT 'MIME 类型',
  `file_size` BIGINT UNSIGNED NULL COMMENT '文件字节数',
  `duration_ms` INT UNSIGNED NULL COMMENT '视频时长毫秒',
  `width` INT UNSIGNED NULL COMMENT '像素宽度',
  `height` INT UNSIGNED NULL COMMENT '像素高度',
  `status` VARCHAR(32) CHARACTER SET ascii COLLATE ascii_bin
    NOT NULL DEFAULT 'CREATED' COMMENT 'CREATED UPLOADED CONFIRMED FAILED EXPIRED',
  `error_message` VARCHAR(500) NULL COMMENT '失败原因摘要',
  `expires_at` DATETIME(3) NOT NULL COMMENT '上传票据过期时间',
  `confirmed_work_id` BIGINT UNSIGNED NULL COMMENT '确认后创建的作品 ID',
  `idempotency_key` VARCHAR(128) NOT NULL COMMENT '上传任务创建幂等键',
  `created_at` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '创建时间',
  `updated_at` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3)
    ON UPDATE CURRENT_TIMESTAMP(3) COMMENT '更新时间',
  `deleted` TINYINT UNSIGNED NOT NULL DEFAULT 0 COMMENT '逻辑删除：0未删除 1已删除',
  `version` INT UNSIGNED NOT NULL DEFAULT 0 COMMENT '乐观锁版本号',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_work_upload_task_idempotency` (`user_id`, `idempotency_key`, `deleted`),
  KEY `idx_work_upload_task_user_status` (`user_id`, `status`, `expires_at`),
  KEY `idx_work_upload_task_batch` (`user_id`, `batch_id`, `id`),
  KEY `idx_work_upload_task_object` (`object_key`),
  CONSTRAINT `chk_work_upload_task_media_type` CHECK (`media_type` IN ('IMAGE', 'VIDEO')),
  CONSTRAINT `chk_work_upload_task_status` CHECK (
    `status` IN ('CREATED', 'UPLOADED', 'CONFIRMED', 'FAILED', 'EXPIRED')
  )
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
  ROW_FORMAT=DYNAMIC COMMENT='作品直传 COS 上传任务';
