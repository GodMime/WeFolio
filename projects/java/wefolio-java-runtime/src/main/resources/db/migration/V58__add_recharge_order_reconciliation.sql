-- 保存充值订单后台核对进度，兼容存量未配置核对时间的订单。
ALTER TABLE wf_recharge_order
    ADD COLUMN next_query_at DATETIME(3) NULL COMMENT '后台下次权威核对时间';
ALTER TABLE wf_recharge_order
    ADD COLUMN query_retry_count INT NOT NULL DEFAULT 0 COMMENT '连续未完成核对次数';
CREATE INDEX idx_recharge_reconciliation ON wf_recharge_order (pay_channel, status, next_query_at, id);
