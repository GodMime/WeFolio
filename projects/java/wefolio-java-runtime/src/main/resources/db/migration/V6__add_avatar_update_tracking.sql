-- V6：用户表新增头像更新追踪字段，用于限制每月头像变更次数
ALTER TABLE wf_user
    ADD COLUMN last_avatar_updated_at DATETIME(3) NULL COMMENT '上次头像更新时间',
    ADD COLUMN avatar_update_count INT NOT NULL DEFAULT 0 COMMENT '当月头像变更次数';
