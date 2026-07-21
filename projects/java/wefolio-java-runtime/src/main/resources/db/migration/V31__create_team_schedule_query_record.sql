CREATE TABLE `wf_team_schedule_query_record` (
  `id` BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '主键',
  `portfolio_id` BIGINT UNSIGNED NOT NULL COMMENT '来源团队作品集ID',
  `portfolio_revision` INT UNSIGNED NOT NULL COMMENT '查询时发布修订号',
  `portfolio_title_snapshot` VARCHAR(100) NOT NULL COMMENT '来源作品集标题快照',
  `team_id` BIGINT UNSIGNED NOT NULL COMMENT '记录归属团队ID',
  `visit_record_id` BIGINT UNSIGNED NOT NULL COMMENT '访问汇总记录ID',
  `visitor_id` BIGINT UNSIGNED NULL COMMENT '全局访客ID',
  `visitor_key` CHAR(64) NOT NULL COMMENT '匿名访客摘要',
  `source_type` VARCHAR(32) CHARACTER SET ascii COLLATE ascii_bin
    NOT NULL DEFAULT 'UNKNOWN' COMMENT '访问来源类型',
  `display_mode` VARCHAR(32) CHARACTER SET ascii COLLATE ascii_bin
    NOT NULL DEFAULT 'MODAL_CALENDAR' COMMENT '查档组件展示方式',
  `queried_date` DATE NOT NULL COMMENT '查询日期',
  `result_status` VARCHAR(32) CHARACTER SET ascii COLLATE ascii_bin NOT NULL
    COMMENT 'TEAM_AVAILABLE TEAM_PARTIAL_AVAILABLE TEAM_FULL',
  `result_status_text` VARCHAR(32) NOT NULL COMMENT '查询结果状态文案',
  `available` TINYINT UNSIGNED NOT NULL COMMENT '是否至少有一名成员可约',
  `result_message` VARCHAR(100) NOT NULL COMMENT '查询结果提示',
  `team_result_json` JSON NOT NULL COMMENT '成员级档期结果快照',
  `available_member_count` INT UNSIGNED NOT NULL DEFAULT 0 COMMENT '空闲成员数',
  `partial_available_member_count` INT UNSIGNED NOT NULL DEFAULT 0 COMMENT '部分档期空闲成员数',
  `full_member_count` INT UNSIGNED NOT NULL DEFAULT 0 COMMENT '已满成员数',
  `queried_at` DATETIME(3) NOT NULL COMMENT '查询成功时间',
  `created_at` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '创建时间',
  `updated_at` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3)
    ON UPDATE CURRENT_TIMESTAMP(3) COMMENT '更新时间',
  `deleted` BIGINT UNSIGNED NOT NULL DEFAULT 0 COMMENT '逻辑删除：0未删除，已删除时为主键ID',
  `version` INT UNSIGNED NOT NULL DEFAULT 0 COMMENT '乐观锁版本号',
  PRIMARY KEY (`id`),
  KEY `idx_team_schedule_query_team_time` (`team_id`, `queried_at`, `id`),
  KEY `idx_team_schedule_query_visit_time` (`visit_record_id`, `queried_at`),
  KEY `idx_team_schedule_query_portfolio_time` (`portfolio_id`, `queried_at`),
  CONSTRAINT `chk_team_schedule_query_status` CHECK (
    `result_status` IN ('TEAM_AVAILABLE', 'TEAM_PARTIAL_AVAILABLE', 'TEAM_FULL')
  ),
  CONSTRAINT `chk_team_schedule_query_available` CHECK (`available` IN (0, 1))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
  ROW_FORMAT=DYNAMIC COMMENT='团队作品集访客查档记录';
