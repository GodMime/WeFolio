-- ============================================================
-- WeFolio V27 — 作品内容审核状态和审核任务表
-- ============================================================

ALTER TABLE `wf_work`
  ADD COLUMN `audit_status` VARCHAR(32) CHARACTER SET ascii COLLATE ascii_bin
    NOT NULL DEFAULT 'PENDING' COMMENT '审核状态：PENDING未审核 AUDITING审核中 PASSED通过 REJECTED违规 REVIEW_REQUIRED疑似 FAILED失败'
    AFTER `status`,
  ADD KEY `idx_work_audit_scan` (`audit_status`, `media_type`, `deleted`, `id`),
  ADD CONSTRAINT `chk_work_audit_status` CHECK (
    `audit_status` IN ('PENDING', 'AUDITING', 'PASSED', 'REJECTED', 'REVIEW_REQUIRED', 'FAILED')
  );

CREATE TABLE `wf_work_audit_task` (
  `id` BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '主键',
  `work_id` BIGINT UNSIGNED NOT NULL COMMENT '作品 ID',
  `user_id` BIGINT UNSIGNED NOT NULL COMMENT '用户 ID',
  `media_type` VARCHAR(16) CHARACTER SET ascii COLLATE ascii_bin
    NOT NULL COMMENT 'IMAGE 图片 VIDEO 视频',
  `media_object_key` VARCHAR(512) NOT NULL COMMENT 'COS 媒体对象键',
  `media_sha256` CHAR(64) CHARACTER SET ascii COLLATE ascii_bin
    NULL COMMENT '媒体文件 SHA-256',
  `provider` VARCHAR(32) CHARACTER SET ascii COLLATE ascii_bin
    NOT NULL DEFAULT 'TENCENT_CI' COMMENT '审核服务提供方',
  `task_status` VARCHAR(32) CHARACTER SET ascii COLLATE ascii_bin
    NOT NULL COMMENT 'PENDING SUBMITTING SUBMITTED RUNNING QUERYING SUCCESS FAILED',
  `audit_result` VARCHAR(32) CHARACTER SET ascii COLLATE ascii_bin
    NOT NULL DEFAULT 'UNKNOWN' COMMENT 'PASS通过 BLOCK违规 REVIEW疑似 UNKNOWN未知',
  `ci_job_id` VARCHAR(128) NULL COMMENT '腾讯云数据万象任务 ID',
  `ci_state` VARCHAR(64) NULL COMMENT '腾讯云任务状态',
  `ci_result` INT NULL COMMENT '腾讯云审核结果码',
  `ci_label` VARCHAR(128) NULL COMMENT '命中的主要标签',
  `ci_score` INT NULL COMMENT '命中分数摘要',
  `snapshot_interval_seconds` INT UNSIGNED NULL COMMENT '视频截帧间隔秒数',
  `snapshot_count` INT UNSIGNED NULL COMMENT '视频截帧数量',
  `attempt_count` INT UNSIGNED NOT NULL DEFAULT 0 COMMENT '提交或图片审核调用次数',
  `query_count` INT UNSIGNED NOT NULL DEFAULT 0 COMMENT '视频结果主动查询次数',
  `last_query_at` DATETIME(3) NULL COMMENT '最近一次查询时间',
  `locked_by` VARCHAR(128) NULL COMMENT '当前处理实例',
  `locked_until` DATETIME(3) NULL COMMENT '锁过期时间',
  `started_at` DATETIME(3) NULL COMMENT '开始处理时间',
  `submitted_at` DATETIME(3) NULL COMMENT '视频提交成功时间',
  `finished_at` DATETIME(3) NULL COMMENT '终态时间',
  `last_error_message` VARCHAR(1000) NULL COMMENT '最近一次错误',
  `request_payload` JSON NULL COMMENT '请求摘要',
  `response_payload` JSON NULL COMMENT '响应摘要',
  `created_at` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '创建时间',
  `updated_at` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3)
    ON UPDATE CURRENT_TIMESTAMP(3) COMMENT '更新时间',
  `deleted` BIGINT UNSIGNED NOT NULL DEFAULT 0 COMMENT '逻辑删除：0未删除，已删除时为主键ID',
  `version` INT UNSIGNED NOT NULL DEFAULT 0 COMMENT '乐观锁版本号',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_work_audit_task_media` (`work_id`, `media_sha256`, `deleted`),
  KEY `idx_work_audit_task_video_query` (`media_type`, `task_status`, `deleted`, `id`),
  KEY `idx_work_audit_task_work` (`work_id`, `deleted`, `id`),
  KEY `idx_work_audit_task_ci_job` (`ci_job_id`),
  CONSTRAINT `chk_work_audit_task_media_type` CHECK (`media_type` IN ('IMAGE', 'VIDEO')),
  CONSTRAINT `chk_work_audit_task_provider` CHECK (`provider` = 'TENCENT_CI'),
  CONSTRAINT `chk_work_audit_task_status` CHECK (
    `task_status` IN ('PENDING', 'SUBMITTING', 'SUBMITTED', 'RUNNING', 'QUERYING', 'SUCCESS', 'FAILED')
  ),
  CONSTRAINT `chk_work_audit_task_result` CHECK (
    `audit_result` IN ('PASS', 'BLOCK', 'REVIEW', 'UNKNOWN')
  )
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
  ROW_FORMAT=DYNAMIC COMMENT='作品内容审核任务';
