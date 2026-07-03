-- V21：用户表新增微信二维码更新追踪字段，用于限制每月二维码变更次数
ALTER TABLE wf_user
    ADD COLUMN last_wechat_qr_updated_at DATETIME(3) NULL COMMENT '上次微信二维码更新时间',
    ADD COLUMN wechat_qr_update_count INT NOT NULL DEFAULT 0 COMMENT '当月微信二维码变更次数';
