-- 参考附件轮询和清理按归属读取任务快照，避免扫描全量任务历史。
ALTER TABLE wf_ai_generation_task
    ADD KEY idx_ai_task_owner (owner_type, owner_id);
