-- ============================================================
-- WeFolio V14 — 新增团队成员信息变更确认请求
-- 背景: 团队拥有者修改成员角色、职业身份和引用范围时，需要成员本人确认后生效。
-- ============================================================

SET NAMES utf8mb4;

CREATE TABLE `wf_team_member_change_request` (
  `id` BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '主键',
  `team_id` BIGINT UNSIGNED NOT NULL COMMENT '团队ID',
  `member_id` BIGINT UNSIGNED NOT NULL COMMENT '团队成员关系ID',
  `target_user_id` BIGINT UNSIGNED NOT NULL COMMENT '被修改成员用户ID',
  `requested_by_user_id` BIGINT UNSIGNED NOT NULL COMMENT '发起修改用户ID',
  `member_version_before` INT UNSIGNED NOT NULL DEFAULT 0 COMMENT '发起时成员关系版本号',
  `role_before` VARCHAR(32) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '修改前团队角色',
  `role_after` VARCHAR(32) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '修改后团队角色',
  `profession_before` VARCHAR(50) NOT NULL DEFAULT '' COMMENT '修改前团队内职业',
  `profession_after` VARCHAR(50) NOT NULL DEFAULT '' COMMENT '修改后团队内职业',
  `allow_portfolio_before` TINYINT UNSIGNED NOT NULL DEFAULT 0 COMMENT '修改前是否允许引用个人作品集',
  `allow_portfolio_after` TINYINT UNSIGNED NOT NULL DEFAULT 0 COMMENT '修改后是否允许引用个人作品集',
  `allow_profile_before` TINYINT UNSIGNED NOT NULL DEFAULT 0 COMMENT '修改前是否允许引用头像资料',
  `allow_profile_after` TINYINT UNSIGNED NOT NULL DEFAULT 0 COMMENT '修改后是否允许引用头像资料',
  `allow_works_before` TINYINT UNSIGNED NOT NULL DEFAULT 0 COMMENT '修改前是否允许引用个人作品素材',
  `allow_works_after` TINYINT UNSIGNED NOT NULL DEFAULT 0 COMMENT '修改后是否允许引用个人作品素材',
  `status` VARCHAR(32) CHARACTER SET ascii COLLATE ascii_bin
    NOT NULL DEFAULT 'PENDING_CONFIRMATION' COMMENT 'PENDING_CONFIRMATION ACCEPTED REJECTED INVALIDATED',
  `pending_marker` TINYINT UNSIGNED GENERATED ALWAYS AS (
    CASE WHEN `status` = 'PENDING_CONFIRMATION' THEN 1 ELSE NULL END
  ) STORED COMMENT '待确认唯一标记',
  `requested_at` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '发起时间',
  `responded_at` DATETIME(3) NULL COMMENT '响应时间',
  `created_at` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '创建时间',
  `updated_at` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3)
    ON UPDATE CURRENT_TIMESTAMP(3) COMMENT '更新时间',
  `deleted` TINYINT UNSIGNED NOT NULL DEFAULT 0 COMMENT '逻辑删除：0未删除 1已删除',
  `version` INT UNSIGNED NOT NULL DEFAULT 0 COMMENT '乐观锁版本号',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_member_pending_change` (`member_id`, `pending_marker`, `deleted`),
  KEY `idx_tmcr_target_status` (`target_user_id`, `status`, `created_at`),
  KEY `idx_tmcr_team_member` (`team_id`, `member_id`, `status`),
  KEY `idx_tmcr_requester_time` (`requested_by_user_id`, `created_at`),
  CONSTRAINT `chk_tmcr_role_before` CHECK (`role_before` IN ('OWNER', 'MANAGER', 'MEMBER')),
  CONSTRAINT `chk_tmcr_role_after` CHECK (`role_after` IN ('MANAGER', 'MEMBER')),
  CONSTRAINT `chk_tmcr_status` CHECK (
    `status` IN ('PENDING_CONFIRMATION', 'ACCEPTED', 'REJECTED', 'INVALIDATED')
  ),
  CONSTRAINT `chk_tmcr_allow_portfolio_before` CHECK (`allow_portfolio_before` IN (0, 1)),
  CONSTRAINT `chk_tmcr_allow_portfolio_after` CHECK (`allow_portfolio_after` IN (0, 1)),
  CONSTRAINT `chk_tmcr_allow_profile_before` CHECK (`allow_profile_before` IN (0, 1)),
  CONSTRAINT `chk_tmcr_allow_profile_after` CHECK (`allow_profile_after` IN (0, 1)),
  CONSTRAINT `chk_tmcr_allow_works_before` CHECK (`allow_works_before` IN (0, 1)),
  CONSTRAINT `chk_tmcr_allow_works_after` CHECK (`allow_works_after` IN (0, 1))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
  ROW_FORMAT=DYNAMIC COMMENT='团队成员信息变更确认请求';

ALTER TABLE `wf_system_message`
  DROP CHECK `chk_msg_action_type`;

ALTER TABLE `wf_system_message`
  ADD CONSTRAINT `chk_msg_action_type` CHECK (
    `action_type` IN ('NONE', 'TEAM_INVITATION', 'TEAM_MEMBER_CHANGE', 'POINT_RECHARGE', 'PAGE_NAVIGATION')
  );
