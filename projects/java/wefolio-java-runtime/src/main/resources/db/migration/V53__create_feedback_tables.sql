-- ============================================================
-- WeFolio V53 — 创建意见反馈与附件上传任务表
-- ============================================================

CREATE TABLE `wf_feedback` (
  `id` BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '主键',
  `feedback_no` VARCHAR(40) CHARACTER SET ascii COLLATE ascii_bin
    NOT NULL COMMENT '反馈单号',
  `user_id` BIGINT UNSIGNED NOT NULL COMMENT '提交用户 ID',
  `status` VARCHAR(32) CHARACTER SET ascii COLLATE ascii_bin
    NOT NULL COMMENT 'PROCESSING 处理中 WAITING_FOLLOW_UP 待再次反馈 RESOLVED 已处理',
  `feedback_result` VARCHAR(200) NULL COMMENT '团队处理结果',
  `feedback_result_at` DATETIME(3) NULL COMMENT '团队处理结果更新时间',
  `rounds_json` JSON NOT NULL COMMENT '反馈轮次快照',
  `round_count` TINYINT UNSIGNED NOT NULL COMMENT '反馈轮次数量',
  `attachment_count` TINYINT UNSIGNED NOT NULL COMMENT '全部轮次附件数量',
  `create_idempotency_key` VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin
    NOT NULL COMMENT '创建反馈幂等键',
  `created_at` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '创建时间',
  `updated_at` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3)
    ON UPDATE CURRENT_TIMESTAMP(3) COMMENT '更新时间',
  `deleted` BIGINT UNSIGNED NOT NULL DEFAULT 0 COMMENT '逻辑删除：0 未删除，已删除时记录本行主键 ID',
  `version` INT UNSIGNED NOT NULL DEFAULT 0 COMMENT '乐观锁版本号',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_feedback_no` (`feedback_no`, `deleted`),
  UNIQUE KEY `uk_feedback_create_idempotency` (`user_id`, `create_idempotency_key`, `deleted`),
  KEY `idx_feedback_user_status_updated` (`user_id`, `status`, `updated_at`),
  KEY `idx_feedback_user_updated` (`user_id`, `updated_at`),
  CONSTRAINT `chk_feedback_status` CHECK (
    `status` IN ('PROCESSING', 'WAITING_FOLLOW_UP', 'RESOLVED')
  ),
  CONSTRAINT `chk_feedback_round_count` CHECK (`round_count` BETWEEN 1 AND 3),
  CONSTRAINT `chk_feedback_attachment_count` CHECK (`attachment_count` <= 9)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
  ROW_FORMAT=DYNAMIC COMMENT='意见反馈单';

CREATE TABLE `wf_feedback_upload_task` (
  `id` BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '主键',
  `user_id` BIGINT UNSIGNED NOT NULL COMMENT '所属用户 ID',
  `client_id` VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin
    NOT NULL COMMENT '客户端上传任务 ID',
  `object_key` VARCHAR(512) CHARACTER SET ascii COLLATE ascii_bin
    NOT NULL COMMENT '后端生成的 COS 对象键',
  `media_type` VARCHAR(32) CHARACTER SET ascii COLLATE ascii_bin
    NOT NULL COMMENT 'IMAGE 图片 VIDEO 视频',
  `mime_type` VARCHAR(128) CHARACTER SET ascii COLLATE ascii_bin
    NOT NULL COMMENT 'MIME 类型',
  `file_size` BIGINT UNSIGNED NOT NULL COMMENT '文件字节数',
  `duration_ms` BIGINT UNSIGNED NOT NULL DEFAULT 0 COMMENT '媒体时长毫秒，图片为 0',
  `status` VARCHAR(32) CHARACTER SET ascii COLLATE ascii_bin
    NOT NULL DEFAULT 'PENDING' COMMENT 'PENDING 待上传 CONFIRMED 已确认 EXPIRED 已过期',
  `expires_at` DATETIME(3) NOT NULL COMMENT '上传任务过期时间',
  `feedback_id` BIGINT UNSIGNED NULL COMMENT '确认后关联的反馈 ID',
  `round_no` TINYINT UNSIGNED NULL COMMENT '确认后关联的反馈轮次',
  `created_at` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '创建时间',
  `updated_at` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3)
    ON UPDATE CURRENT_TIMESTAMP(3) COMMENT '更新时间',
  `deleted` BIGINT UNSIGNED NOT NULL DEFAULT 0 COMMENT '逻辑删除：0 未删除，已删除时记录本行主键 ID',
  `version` INT UNSIGNED NOT NULL DEFAULT 0 COMMENT '乐观锁版本号',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_feedback_upload_task_client` (`user_id`, `client_id`, `deleted`),
  UNIQUE KEY `uk_feedback_upload_task_object` (`object_key`, `deleted`),
  KEY `idx_feedback_upload_task_status_expires` (`status`, `expires_at`),
  KEY `idx_feedback_upload_task_feedback` (`user_id`, `feedback_id`),
  CONSTRAINT `chk_feedback_upload_task_media_type` CHECK (`media_type` IN ('IMAGE', 'VIDEO')),
  CONSTRAINT `chk_feedback_upload_task_status` CHECK (
    `status` IN ('PENDING', 'CONFIRMED', 'EXPIRED')
  ),
  CONSTRAINT `chk_feedback_upload_task_round_no` CHECK (
    `round_no` IS NULL OR `round_no` BETWEEN 1 AND 3
  )
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
  ROW_FORMAT=DYNAMIC COMMENT='意见反馈附件上传任务';
