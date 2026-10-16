-- 参考原件仅保存当前清单及任务快照，不新增附件台账。
ALTER TABLE wf_portfolio
    ADD COLUMN ai_reference_attachments_json JSON NULL COMMENT '高级草稿参考附件清单及编号高水位，仅维护者可见' AFTER ai_context_json;

ALTER TABLE wf_ai_generation_task
    ADD COLUMN reference_attachments_json JSON NULL COMMENT '本次任务不可变参考附件快照，不含临时地址与回执' AFTER request_json;
