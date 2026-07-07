-- 为作品表补充审核拒绝原因摘要，具体业务拦截后续再接入。
ALTER TABLE `wf_work`
  ADD COLUMN `audit_reject_reason` VARCHAR(512) NULL COMMENT '审核拒绝原因：违规、疑似、失败等未通过原因摘要'
  AFTER `audit_status`;
