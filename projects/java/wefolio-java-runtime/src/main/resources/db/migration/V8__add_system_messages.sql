-- ============================================================
-- WeFolio V8 — 新增系统消息表
-- 背景: “我的”页面新增“我的消息”，用于承载积分不足、团队邀请、
--       团队角色变更和平台公告等系统消息。
-- ============================================================

SET NAMES utf8mb4;

CREATE TABLE `wf_system_message` (
  `id` BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '主键',
  `user_id` BIGINT UNSIGNED NOT NULL COMMENT '消息所属用户ID',
  `message_type` VARCHAR(32) CHARACTER SET ascii COLLATE ascii_bin
    NOT NULL COMMENT 'POINT_LOW_BALANCE TEAM_INVITATION TEAM_ROLE_CHANGED SYSTEM_NOTICE',
  `category` VARCHAR(32) CHARACTER SET ascii COLLATE ascii_bin
    NOT NULL DEFAULT 'SYSTEM' COMMENT 'POINT TEAM SYSTEM',
  `read_status` VARCHAR(32) CHARACTER SET ascii COLLATE ascii_bin
    NOT NULL DEFAULT 'UNREAD' COMMENT 'UNREAD未读 READ已读',
  `title` VARCHAR(100) NOT NULL DEFAULT '' COMMENT '消息标题',
  `content` VARCHAR(1000) NOT NULL DEFAULT '' COMMENT '消息正文',
  `action_type` VARCHAR(32) CHARACTER SET ascii COLLATE ascii_bin
    NOT NULL DEFAULT 'NONE' COMMENT 'NONE TEAM_INVITATION POINT_RECHARGE PAGE_NAVIGATION',
  `action_url` VARCHAR(500) NOT NULL DEFAULT '' COMMENT '小程序跳转路径或后端约定动作地址',
  `biz_type` VARCHAR(32) CHARACTER SET ascii COLLATE ascii_bin
    NOT NULL DEFAULT '' COMMENT '业务来源类型',
  `biz_id` BIGINT UNSIGNED NULL COMMENT '业务来源ID',
  `idempotency_key` VARCHAR(128) NULL COMMENT '消息幂等键',
  `read_at` DATETIME(3) NULL COMMENT '已读时间',
  `created_at` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '创建时间',
  `updated_at` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3)
    ON UPDATE CURRENT_TIMESTAMP(3) COMMENT '更新时间',
  `deleted` TINYINT UNSIGNED NOT NULL DEFAULT 0 COMMENT '逻辑删除：0未删除 1已删除',
  `version` INT UNSIGNED NOT NULL DEFAULT 0 COMMENT '乐观锁版本号',
  PRIMARY KEY (`id`),
  KEY `idx_msg_user_status_time` (`user_id`, `read_status`, `created_at`),
  KEY `idx_msg_user_category_time` (`user_id`, `category`, `created_at`),
  UNIQUE KEY `uk_msg_idempotency` (`idempotency_key`, `deleted`),
  CONSTRAINT `chk_msg_type` CHECK (
    `message_type` IN ('POINT_LOW_BALANCE', 'TEAM_INVITATION', 'TEAM_ROLE_CHANGED', 'SYSTEM_NOTICE')
  ),
  CONSTRAINT `chk_msg_category` CHECK (`category` IN ('POINT', 'TEAM', 'SYSTEM')),
  CONSTRAINT `chk_msg_read_status` CHECK (`read_status` IN ('UNREAD', 'READ')),
  CONSTRAINT `chk_msg_action_type` CHECK (
    `action_type` IN ('NONE', 'TEAM_INVITATION', 'POINT_RECHARGE', 'PAGE_NAVIGATION')
  )
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
  ROW_FORMAT=DYNAMIC COMMENT='系统消息';
