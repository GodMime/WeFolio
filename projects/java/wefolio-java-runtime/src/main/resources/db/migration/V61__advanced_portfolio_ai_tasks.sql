-- 高级作品集上下文与五态 AI 任务；旧任务表必须为空。
-- 未启用前提由数据库强制检查；包含逻辑删除行，不清理任何业务数据。
CREATE TEMPORARY TABLE `wf_advanced_migration_guard` (
  `task_count` BIGINT NOT NULL,
  `advanced_count` BIGINT NOT NULL,
  CONSTRAINT `chk_advanced_migration_task_empty` CHECK (`task_count` = 0),
  CONSTRAINT `chk_advanced_migration_portfolio_empty` CHECK (`advanced_count` = 0)
);
INSERT INTO `wf_advanced_migration_guard` (`task_count`, `advanced_count`)
SELECT (SELECT COUNT(*) FROM `wf_ai_generation_task`),
       (SELECT COUNT(*) FROM `wf_portfolio` WHERE `template_type` = 'ADVANCED');
DROP TEMPORARY TABLE `wf_advanced_migration_guard`;

ALTER TABLE `wf_portfolio`
  ADD COLUMN `ai_context_json` JSON NULL
    COMMENT '高级草稿来源编号上下文，含nextSourceNo与当前来源列表，不进入访客配置';

ALTER TABLE `wf_ai_generation_task`
  DROP CHECK `chk_ai_task_cost`,
  DROP CHECK `chk_ai_task_status`,
  DROP INDEX `uk_ai_task_idempotency`,
  DROP INDEX `idx_ai_task_portfolio`,
  DROP INDEX `idx_ai_task_requester`,
  DROP COLUMN `applied_revision`,
  DROP COLUMN `prompt`,
  DROP COLUMN `selected_sources_json`,
  DROP COLUMN `result_schema_json`,
  DROP COLUMN `cost_points`,
  MODIFY COLUMN `portfolio_id` BIGINT UNSIGNED NULL
    COMMENT '提交时已有作品集ID，新建未保存时为空',
  MODIFY COLUMN `idempotency_key` VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL
    COMMENT '请求人作用域内的提交幂等键',
  MODIFY COLUMN `status` VARCHAR(32) CHARACTER SET ascii COLLATE ascii_bin NOT NULL DEFAULT 'PENDING'
    COMMENT 'PENDING RUNNING SUCCEEDED FAILED CANCELLED',
  MODIFY COLUMN `started_at` DATETIME(3) NULL COMMENT '最近一次领取时间，用于执行超时恢复',
  MODIFY COLUMN `completed_at` DATETIME(3) NULL COMMENT '任务进入终态的时间',
  MODIFY COLUMN `error_code` VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NULL COMMENT '最近一次失败错误码',
  MODIFY COLUMN `error_message` VARCHAR(500) NULL COMMENT '最近一次失败的安全摘要，可用于下次纠正',
  MODIFY COLUMN `version` INT UNSIGNED NOT NULL DEFAULT 0 COMMENT '乐观锁版本，也用于隔离不同执行代次',
  ADD COLUMN `owner_type` VARCHAR(32) CHARACTER SET ascii COLLATE ascii_bin NOT NULL
    COMMENT '归属类型USER或TEAM',
  ADD COLUMN `owner_id` BIGINT UNSIGNED NOT NULL COMMENT '归属用户或团队ID',
  ADD COLUMN `request_json` JSON NOT NULL
    COMMENT '冻结的编辑输入、来源事实、模型配置与契约版本，不含密钥',
  ADD COLUMN `result_json` JSON NULL
    COMMENT '合法结果，kind为CANDIDATE或CLARIFICATION，output为模型信封',
  ADD COLUMN `attempt_count` INT UNSIGNED NOT NULL DEFAULT 0 COMMENT '已领取执行次数，初次和自动重试共用',
  ADD COLUMN `next_run_at` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3)
    COMMENT '等待任务最早执行时间',
  ADD UNIQUE KEY `uk_ai_task_request_idempotency` (`requested_by`, `idempotency_key`, `deleted`),
  ADD KEY `idx_ai_task_queue` (`status`, `deleted`, `next_run_at`, `id`),
  ADD KEY `idx_ai_task_running` (`status`, `deleted`, `started_at`),
  ADD KEY `idx_ai_task_requester` (`requested_by`, `deleted`, `status`),
  ADD KEY `idx_ai_task_portfolio` (`portfolio_id`, `deleted`, `created_at`),
  ADD CONSTRAINT `chk_ai_task_owner_type` CHECK (`owner_type` IN ('USER', 'TEAM')),
  ADD CONSTRAINT `chk_ai_task_status`
    CHECK (`status` IN ('PENDING', 'RUNNING', 'SUCCEEDED', 'FAILED', 'CANCELLED')),
  COMMENT = '高级作品集AI异步任务';
