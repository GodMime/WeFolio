-- 只增加独立前台时长指标，历史数据保持 NULL，不回填既有事件秒数。
ALTER TABLE wf_visit_record ADD COLUMN foreground_duration_ms BIGINT NULL DEFAULT NULL COMMENT '累计前台毫秒，NULL 表示未采集';

CREATE TABLE wf_visit_activity_session (
    id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '主键',
    visitor_id BIGINT UNSIGNED NOT NULL COMMENT '已认证访客 ID',
    portfolio_id BIGINT UNSIGNED NOT NULL COMMENT '作品集 ID',
    portfolio_type VARCHAR(32) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT 'PERSONAL 或 TEAM',
    visit_record_id BIGINT UNSIGNED NOT NULL COMMENT '访问汇总 ID',
    client_session_key VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '同次真实浏览的活动键',
    open_idempotency_key VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '首次打开幂等键',
    active_duration_ms BIGINT NOT NULL DEFAULT 0 COMMENT '会话前台累计高水位',
    last_reported_at DATETIME(3) NULL DEFAULT NULL COMMENT '最后成功采集时间',
    brand VARCHAR(64) NULL DEFAULT NULL COMMENT '设备品牌',
    model VARCHAR(128) NULL DEFAULT NULL COMMENT '设备型号',
    `system` VARCHAR(128) NULL DEFAULT NULL COMMENT '设备系统',
    platform VARCHAR(32) NULL DEFAULT NULL COMMENT '设备平台',
    created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    deleted BIGINT UNSIGNED NOT NULL DEFAULT 0,
    version INT NOT NULL DEFAULT 0,
    PRIMARY KEY (id),
    UNIQUE KEY uk_visit_activity_session (visitor_id, portfolio_type, portfolio_id, client_session_key, deleted),
    UNIQUE KEY uk_visit_activity_open (visitor_id, portfolio_type, portfolio_id, open_idempotency_key, deleted),
    KEY idx_visit_activity_device (visit_record_id, deleted, created_at, id),
    CONSTRAINT chk_visit_activity_type CHECK (portfolio_type IN ('PERSONAL', 'TEAM')),
    CONSTRAINT chk_visit_activity_duration CHECK (active_duration_ms >= 0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci ROW_FORMAT=DYNAMIC COMMENT='访客前台活动会话与设备快照';
