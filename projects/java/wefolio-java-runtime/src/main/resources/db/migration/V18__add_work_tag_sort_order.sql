-- ============================================================
-- WeFolio V18 — 作品标签内独立排序
-- ============================================================

ALTER TABLE `wf_work_tag`
  ADD COLUMN `sort_order` INT NOT NULL DEFAULT 0 COMMENT '标签内排序值' AFTER `tag_id`,
  ADD KEY `idx_work_tag_user_tag_sort` (`user_id`, `tag_id`, `deleted`, `sort_order`, `work_id`);

UPDATE `wf_work_tag` wt
JOIN `wf_work` w ON w.`id` = wt.`work_id`
SET wt.`sort_order` = w.`sort_order`
WHERE wt.`deleted` = 0
  AND w.`deleted` = 0;
