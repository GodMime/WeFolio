-- 视频持久帧沿用原审核任务；已有任务默认 INTERVAL，部署切换不能改变旧参数。
ALTER TABLE `wf_work`
  ADD COLUMN `video_frames_json` JSON DEFAULT NULL COMMENT '视频完整持久帧清单，自有COS对象与真实截图时间' AFTER `duration_ms`,
  ADD CONSTRAINT `chk_work_video_frames_media` CHECK (`video_frames_json` IS NULL OR `media_type` = 'VIDEO');

ALTER TABLE `wf_work_audit_task`
  ADD COLUMN `snapshot_mode` VARCHAR(32) CHARACTER SET ascii COLLATE ascii_bin NOT NULL DEFAULT 'INTERVAL' COMMENT '冻结视频截帧模式：INTERVAL原间隔 AVERAGE均匀采样' AFTER `ci_score`,
  ADD COLUMN `snapshot_duration_ms` INT UNSIGNED DEFAULT NULL COMMENT '任务冻结的前端视频时长毫秒，未知为空' AFTER `snapshot_count`,
  ADD COLUMN `snapshot_policy_version` VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin DEFAULT NULL COMMENT '任务冻结的持久帧策略版本' AFTER `snapshot_duration_ms`,
  ADD COLUMN `snapshot_source_version` VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin DEFAULT NULL COMMENT '任务冻结的COS源对象身份摘要' AFTER `snapshot_policy_version`,
  ADD CONSTRAINT `chk_work_audit_snapshot_mode` CHECK (`snapshot_mode` IN ('INTERVAL', 'AVERAGE'));
