-- ============================================================
-- WeFolio V32 — 新增每月作品存储积分结算
-- ============================================================

SET NAMES utf8mb4;

CREATE TABLE `wf_work_storage_monthly_bill` (
  `id` BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '主键',
  `user_id` BIGINT UNSIGNED NOT NULL COMMENT '用户ID',
  `billing_month` DATE NOT NULL COMMENT '账期月份，固定存当月1日',
  `work_count` BIGINT UNSIGNED NOT NULL DEFAULT 0 COMMENT '统计作品数',
  `total_file_size_bytes` BIGINT UNSIGNED NOT NULL DEFAULT 0 COMMENT '作品总字节数',
  `points_due` BIGINT UNSIGNED NOT NULL DEFAULT 0 COMMENT '应扣积分',
  `points_deducted` BIGINT UNSIGNED NOT NULL DEFAULT 0 COMMENT '实际扣除积分',
  `points_shortfall` BIGINT UNSIGNED NOT NULL DEFAULT 0 COMMENT '积分不足未扣部分',
  `balance_before` BIGINT UNSIGNED NOT NULL DEFAULT 0 COMMENT '扣除前余额',
  `balance_after` BIGINT UNSIGNED NOT NULL DEFAULT 0 COMMENT '扣除后余额',
  `point_transaction_id` BIGINT UNSIGNED NULL COMMENT '积分流水ID，未实际扣分时为空',
  `billing_status` VARCHAR(32) CHARACTER SET ascii COLLATE ascii_bin
    NOT NULL COMMENT 'NO_CHARGE无需扣分 CHARGED足额扣分 PARTIAL积分不足',
  `remark` VARCHAR(255) NULL COMMENT '计费结果说明',
  `processed_at` DATETIME(3) NOT NULL COMMENT '处理完成时间',
  `created_at` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '创建时间',
  `updated_at` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3)
    ON UPDATE CURRENT_TIMESTAMP(3) COMMENT '更新时间',
  `deleted` BIGINT UNSIGNED NOT NULL DEFAULT 0 COMMENT '逻辑删除：0未删除，已删除时为主键ID',
  `version` INT UNSIGNED NOT NULL DEFAULT 0 COMMENT '乐观锁版本号',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_work_storage_bill_user_month`
    (`user_id`, `billing_month`, `deleted`),
  KEY `idx_work_storage_bill_month_status`
    (`billing_month`, `billing_status`),
  CONSTRAINT `chk_work_storage_bill_status`
    CHECK (`billing_status` IN ('NO_CHARGE', 'CHARGED', 'PARTIAL')),
  CONSTRAINT `chk_work_storage_bill_points`
    CHECK (
      `points_deducted` <= `points_due`
      AND `points_shortfall` = `points_due` - `points_deducted`
    )
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
  ROW_FORMAT=DYNAMIC COMMENT='用户每月作品存储积分账单';

ALTER TABLE `wf_point_rule`
  DROP CHECK `chk_point_rule_calc_mode`;

ALTER TABLE `wf_point_rule`
  ADD CONSTRAINT `chk_point_rule_calc_mode` CHECK (
    `calc_mode` IN (
      'FIXED_PER_ACTION',
      'ACCUMULATED_THRESHOLD',
      'RECHARGE_PACKAGE',
      'MANUAL_ADJUSTMENT',
      'MONTHLY_STORAGE_SIZE'
    )
  );

INSERT INTO `wf_point_rule` (
  `rule_code`,
  `rule_version`,
  `rule_name`,
  `transaction_type`,
  `scene_code`,
  `group_code`,
  `calc_mode`,
  `unit_count`,
  `points_value`,
  `config_json`,
  `effective_from`,
  `effective_to`,
  `status`,
  `version`,
  `created_at`,
  `updated_at`,
  `deleted`
) VALUES (
  'MONTHLY_WORK_STORAGE',
  1,
  '作品存储月费',
  'CONSUMPTION',
  'MONTHLY_WORK_STORAGE',
  'MAINTENANCE',
  'MONTHLY_STORAGE_SIZE',
  10,
  1,
  JSON_OBJECT('source', 'MONTHLY_WORK_STORAGE_V1'),
  '2026-07-01 00:00:00.000',
  NULL,
  'ACTIVE',
  0,
  CURRENT_TIMESTAMP(3),
  CURRENT_TIMESTAMP(3),
  0
);
