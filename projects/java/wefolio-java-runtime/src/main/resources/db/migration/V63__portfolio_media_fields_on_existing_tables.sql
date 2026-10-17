-- 媒体摘要归属作品、逐调用预算归属 AI 任务；复用既有表，不新增媒体缓存或费用明细表。
ALTER TABLE `wf_work`
  ADD COLUMN `ai_media_summary_json` JSON DEFAULT NULL COMMENT '版本化媒体摘要缓存，含源、模型、提示词与策略身份，不含签名' AFTER `video_frames_json`,
  ADD COLUMN `ai_media_summary_expires_at` DATETIME(3) DEFAULT NULL COMMENT '媒体摘要缓存过期时间，过期不删除作品帧' AFTER `ai_media_summary_json`,
  ADD KEY `idx_work_ai_media_summary_expiry` (`ai_media_summary_expires_at`,`id`);

ALTER TABLE `wf_ai_generation_task`
  ADD COLUMN `media_budget_json` JSON DEFAULT NULL COMMENT '版本化逐调用预算JSON，费用单位分，未知用量不退还预留' AFTER `result_json`,
  ADD COLUMN `media_budget_unknown_count` INT UNSIGNED NOT NULL DEFAULT 0 COMMENT '完整用量未返回的预留笔数，普通文字任务默认零' AFTER `media_budget_json`;
