-- 仅扩容现有错误摘要字段，按执行次数追加失败历史，不新增表或字段。
ALTER TABLE wf_ai_generation_task
    MODIFY COLUMN error_message TEXT NULL COMMENT '按执行次数追加的安全失败摘要，不含模型原文或凭据';
