-- 新增全局访客身份表，并让访问汇总关联到全局访客。
CREATE TABLE `wf_visitor` (
  `id` BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '主键',
  `openid` VARCHAR(128) NOT NULL COMMENT '微信 openid 明文',
  `unionid` VARCHAR(128) NULL COMMENT '微信 unionid',
  `visitor_key` CHAR(64) NOT NULL COMMENT '服务端生成的匿名访客稳定 key',
  `nickname` VARCHAR(50) NULL COMMENT '访客授权昵称',
  `avatar_url` VARCHAR(512) NULL COMMENT '访客授权头像地址',
  `profile_authorized_at` DATETIME(3) NULL COMMENT '头像昵称最近成功保存时间',
  `last_seen_at` DATETIME(3) NULL COMMENT '最近访问时间',
  `created_at` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '创建时间',
  `updated_at` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3)
    ON UPDATE CURRENT_TIMESTAMP(3) COMMENT '更新时间',
  `deleted` BIGINT UNSIGNED NOT NULL DEFAULT 0 COMMENT '逻辑删除：0 未删除，已删除时记录本行主键 ID',
  `version` INT UNSIGNED NOT NULL DEFAULT 0 COMMENT '乐观锁版本号',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_visitor_openid` (`openid`, `deleted`),
  UNIQUE KEY `uk_visitor_key` (`visitor_key`, `deleted`),
  KEY `idx_visitor_last_seen` (`last_seen_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
  ROW_FORMAT=DYNAMIC COMMENT='全局访客身份表';

ALTER TABLE `wf_visit_record`
  ADD COLUMN `visitor_id` BIGINT UNSIGNED NULL COMMENT '全局访客 ID' AFTER `id`,
  DROP INDEX `uk_visit_visitor_portfolio`,
  ADD UNIQUE KEY `uk_visit_visitor_portfolio` (`visitor_id`, `portfolio_id`, `deleted`),
  ADD UNIQUE KEY `uk_visit_key_portfolio_deleted` (`visitor_key`, `portfolio_id`, `deleted`),
  ADD KEY `idx_visit_visitor` (`visitor_id`, `last_visited_at`);
