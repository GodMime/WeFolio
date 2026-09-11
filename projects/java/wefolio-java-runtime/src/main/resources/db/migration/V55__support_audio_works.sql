-- 音频复用现有作品、上传任务和审核字段，仅放宽媒体类型约束。
ALTER TABLE `wf_work`
  DROP CHECK `chk_work_media_type`,
  ADD CONSTRAINT `chk_work_media_type`
    CHECK (`media_type` IN ('IMAGE', 'VIDEO', 'ANIMATION', 'AUDIO'));

ALTER TABLE `wf_work_upload_task`
  DROP CHECK `chk_work_upload_task_media_type`,
  ADD CONSTRAINT `chk_work_upload_task_media_type`
    CHECK (`media_type` IN ('IMAGE', 'VIDEO', 'ANIMATION', 'AUDIO'));

ALTER TABLE `wf_work_audit_task`
  DROP CHECK `chk_work_audit_task_media_type`,
  ADD CONSTRAINT `chk_work_audit_task_media_type`
    CHECK (`media_type` IN ('IMAGE', 'VIDEO', 'ANIMATION', 'AUDIO'));
