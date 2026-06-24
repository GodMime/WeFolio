-- ============================================================
-- WeFolio V5 — 补齐 BaseEntity 公共字段，统一 lock_version → version
-- 背景: 部分表建表时遗漏了 BaseEntity 所需的公共列 (created_at/
--       updated_at/deleted/version)，或使用了 lock_version 替代 version，
--       导致 MyBatis-Plus selectList 时拼出 unknown column 报错。
-- ============================================================

-- 1. 缺 updated_at（5 张表）
ALTER TABLE `wf_point_transaction`
  ADD COLUMN `updated_at` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3)
  ON UPDATE CURRENT_TIMESTAMP(3) COMMENT '更新时间';

ALTER TABLE `wf_portfolio_share_record`
  ADD COLUMN `updated_at` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3)
  ON UPDATE CURRENT_TIMESTAMP(3) COMMENT '更新时间';

ALTER TABLE `wf_referral_relation`
  ADD COLUMN `updated_at` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3)
  ON UPDATE CURRENT_TIMESTAMP(3) COMMENT '更新时间';

ALTER TABLE `wf_visit_event`
  ADD COLUMN `updated_at` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3)
  ON UPDATE CURRENT_TIMESTAMP(3) COMMENT '更新时间';

ALTER TABLE `wf_work_tag`
  ADD COLUMN `updated_at` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3)
  ON UPDATE CURRENT_TIMESTAMP(3) COMMENT '更新时间';

-- 2. 缺 created_at + updated_at（1 张表）
ALTER TABLE `wf_portfolio_history`
  ADD COLUMN `created_at` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '创建时间',
  ADD COLUMN `updated_at` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3)
  ON UPDATE CURRENT_TIMESTAMP(3) COMMENT '更新时间';

-- 3. lock_version → version 重命名（3 张表）
ALTER TABLE `wf_point_meter`
  CHANGE COLUMN `lock_version` `version` INT UNSIGNED NOT NULL DEFAULT 0 COMMENT '乐观锁版本号';

ALTER TABLE `wf_point_rule`
  CHANGE COLUMN `lock_version` `version` INT UNSIGNED NOT NULL DEFAULT 0 COMMENT '乐观锁版本号';

ALTER TABLE `wf_portfolio`
  CHANGE COLUMN `lock_version` `version` INT UNSIGNED NOT NULL DEFAULT 0 COMMENT '乐观锁版本号';
