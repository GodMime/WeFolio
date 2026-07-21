-- ============================================================
-- WeFolio V35 — 新增个人作品集访客滚动扣费窗口
-- ============================================================

SET NAMES utf8mb4;

CREATE TABLE `wf_point_billing_window` (
  `id` BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '主键',
  `account_id` BIGINT UNSIGNED NULL COMMENT '最近一次扣费使用的积分账户ID',
  `user_id` BIGINT UNSIGNED NOT NULL COMMENT '被扣费维护者用户ID',
  `visitor_id` BIGINT UNSIGNED NOT NULL COMMENT '全局访客ID',
  `scene_code` VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin
    NOT NULL COMMENT '访客扣费积分场景',
  `scope_type` VARCHAR(32) CHARACTER SET ascii COLLATE ascii_bin
    NOT NULL COMMENT 'PORTFOLIO作品集 WORK作品',
  `scope_id` BIGINT UNSIGNED NOT NULL COMMENT '作品集ID或作品ID',
  `last_charged_at` DATETIME(3) NULL COMMENT '最近一次成功扣费时间',
  `point_transaction_id` BIGINT UNSIGNED NULL COMMENT '最近一次积分流水ID',
  `created_at` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '创建时间',
  `updated_at` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3)
    ON UPDATE CURRENT_TIMESTAMP(3) COMMENT '更新时间',
  `deleted` BIGINT UNSIGNED NOT NULL DEFAULT 0
    COMMENT '逻辑删除：0未删除，已删除时为主键ID',
  `version` INT UNSIGNED NOT NULL DEFAULT 0 COMMENT '乐观锁版本号',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_point_billing_window_identity`
    (`user_id`, `scene_code`, `visitor_id`, `scope_type`, `scope_id`, `deleted`),
  KEY `idx_point_billing_window_transaction` (`point_transaction_id`),
  CONSTRAINT `chk_point_billing_window_scene` CHECK (
    `scene_code` IN (
      'VISIT_PERSONAL_PORTFOLIO',
      'VIEW_PORTFOLIO_IMAGES',
      'VIEW_PORTFOLIO_VIDEO'
    )
  ),
  CONSTRAINT `chk_point_billing_window_scope` CHECK (
    `scope_type` IN ('PORTFOLIO', 'WORK')
  )
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
  ROW_FORMAT=DYNAMIC COMMENT='个人作品集访客滚动扣费窗口';
