-- ============================================================
-- WeFolio V3 — 用户隔离字段 + 唯一索引修正
-- 注意: 当前无数据，直接 NOT NULL 和 DROP/CREATE 索引。
-- ============================================================

-- ============================================================
-- A. 追加用户隔离字段
-- ============================================================

-- A1. wf_work_tag — 新增 user_id + 修正唯一索引含 deleted
ALTER TABLE `wf_work_tag`
  ADD COLUMN `user_id` BIGINT UNSIGNED NOT NULL COMMENT '所属用户ID' AFTER `id`,
  ADD KEY `idx_work_tag_user` (`user_id`, `work_id`),
  DROP INDEX `uk_work_tag`,
  ADD UNIQUE KEY `uk_work_tag` (`work_id`, `tag_id`, `deleted`);


-- A2. wf_visit_event — 新增 owner_type / owner_id
--     idempotency_key 唯一索引故意不含 deleted（幂等键不可复用）
ALTER TABLE `wf_visit_event`
  ADD COLUMN `owner_type` VARCHAR(16) CHARACTER SET ascii COLLATE ascii_bin
    NOT NULL COMMENT 'USER用户 TEAM团队' AFTER `work_id`,
  ADD COLUMN `owner_id` BIGINT UNSIGNED
    NOT NULL COMMENT '事件归属用户或团队ID' AFTER `owner_type`,
  ADD CONSTRAINT `chk_visit_event_owner_type`
    CHECK (`owner_type` IN ('USER', 'TEAM')),
  ADD KEY `idx_visit_event_owner` (`owner_type`, `owner_id`, `occurred_at`);


-- A3. wf_portfolio_share_record — 新增 owner_type / owner_id
ALTER TABLE `wf_portfolio_share_record`
  ADD COLUMN `owner_type` VARCHAR(16) CHARACTER SET ascii COLLATE ascii_bin
    NOT NULL COMMENT 'USER用户 TEAM团队' AFTER `shared_by_user_id`,
  ADD COLUMN `owner_id` BIGINT UNSIGNED
    NOT NULL COMMENT '分享归属用户或团队ID' AFTER `owner_type`,
  ADD CONSTRAINT `chk_share_owner_type`
    CHECK (`owner_type` IN ('USER', 'TEAM')),
  ADD KEY `idx_share_owner_time` (`owner_type`, `owner_id`, `created_at`);


-- ============================================================
-- B. 唯一索引补充 deleted 字段
--    逻辑删除后应允许重建同值记录
-- ============================================================

-- B1. wf_user — unique_code 删除后可复用
ALTER TABLE `wf_user`
  DROP INDEX `uk_user_unique_code`,
  ADD UNIQUE KEY `uk_user_unique_code` (`unique_code`, `deleted`);

-- B2. wf_referral_relation — 被推荐人删除后可重新被推荐
ALTER TABLE `wf_referral_relation`
  DROP INDEX `uk_referral_referred_user`,
  ADD UNIQUE KEY `uk_referral_referred_user` (`referred_user_id`, `deleted`);

-- B3. wf_tag — 用户内标签名删除后可重建
ALTER TABLE `wf_tag`
  DROP INDEX `uk_tag_user_name`,
  ADD UNIQUE KEY `uk_tag_user_name` (`user_id`, `name`, `deleted`);

-- B4. wf_team — unique_code 删除后可复用
ALTER TABLE `wf_team`
  DROP INDEX `uk_team_unique_code`,
  ADD UNIQUE KEY `uk_team_unique_code` (`unique_code`, `deleted`);

-- B5. wf_portfolio — share_code 删除后可复用
ALTER TABLE `wf_portfolio`
  DROP INDEX `uk_portfolio_share_code`,
  ADD UNIQUE KEY `uk_portfolio_share_code` (`share_code`, `deleted`);
