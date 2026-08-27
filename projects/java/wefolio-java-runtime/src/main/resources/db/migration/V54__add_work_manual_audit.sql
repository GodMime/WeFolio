ALTER TABLE wf_work
  ADD COLUMN manual_audit_no CHAR(34) CHARACTER SET ascii COLLATE ascii_bin NULL
    COMMENT '最终轮人工审核编号，存在即表示已经进入人工审核'
    AFTER audit_reject_reason,
  ADD COLUMN manual_audit_result_at DATETIME(3) NULL
    COMMENT '人工审核首次写入最终结论时间'
    AFTER manual_audit_no,
  ADD UNIQUE KEY uk_work_manual_audit_no (manual_audit_no, deleted);
