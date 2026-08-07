-- 合并 V25 之后可能产生的有效访问汇总重复行，再恢复数据库唯一约束。
CREATE TEMPORARY TABLE `tmp_visit_record_merge` AS
SELECT
  `visitor_id`,
  `portfolio_id`,
  MIN(`id`) AS `keeper_id`,
  SUM(`visit_count`) AS `visit_count`,
  SUM(`view_work_count`) AS `view_work_count`,
  SUM(`play_video_count`) AS `play_video_count`,
  SUM(`schedule_query_count`) AS `schedule_query_count`,
  SUM(`qr_action_count`) AS `qr_action_count`,
  SUM(`contact_submit_count`) AS `contact_submit_count`,
  SUM(`total_duration_seconds`) AS `total_duration_seconds`,
  MIN(`first_visited_at`) AS `first_visited_at`,
  MAX(`last_visited_at`) AS `last_visited_at`
FROM `wf_visit_record`
WHERE `deleted` = 0
GROUP BY `visitor_id`, `portfolio_id`
HAVING COUNT(*) > 1;

UPDATE `wf_visit_record` AS `keeper`
JOIN `tmp_visit_record_merge` AS `merged`
  ON `keeper`.`id` = `merged`.`keeper_id`
SET
  `keeper`.`visit_count` = `merged`.`visit_count`,
  `keeper`.`view_work_count` = `merged`.`view_work_count`,
  `keeper`.`play_video_count` = `merged`.`play_video_count`,
  `keeper`.`schedule_query_count` = `merged`.`schedule_query_count`,
  `keeper`.`qr_action_count` = `merged`.`qr_action_count`,
  `keeper`.`contact_submit_count` = `merged`.`contact_submit_count`,
  `keeper`.`total_duration_seconds` = `merged`.`total_duration_seconds`,
  `keeper`.`first_visited_at` = `merged`.`first_visited_at`,
  `keeper`.`last_visited_at` = `merged`.`last_visited_at`;

UPDATE `wf_visit_event` AS `event`
JOIN `wf_visit_record` AS `duplicate_record`
  ON `event`.`visit_record_id` = `duplicate_record`.`id`
JOIN `tmp_visit_record_merge` AS `merged`
  ON `duplicate_record`.`visitor_id` = `merged`.`visitor_id`
 AND `duplicate_record`.`portfolio_id` = `merged`.`portfolio_id`
SET `event`.`visit_record_id` = `merged`.`keeper_id`
WHERE `duplicate_record`.`deleted` = 0
  AND `duplicate_record`.`id` <> `merged`.`keeper_id`;

DELETE `duplicate_record`
FROM `wf_visit_record` AS `duplicate_record`
JOIN `tmp_visit_record_merge` AS `merged`
  ON `duplicate_record`.`visitor_id` = `merged`.`visitor_id`
 AND `duplicate_record`.`portfolio_id` = `merged`.`portfolio_id`
WHERE `duplicate_record`.`deleted` = 0
  AND `duplicate_record`.`id` <> `merged`.`keeper_id`;

DROP TEMPORARY TABLE `tmp_visit_record_merge`;

ALTER TABLE `wf_visit_record`
  DROP INDEX `idx_visit_visitor_portfolio`,
  ADD UNIQUE KEY `uk_visit_visitor_portfolio` (`visitor_id`, `portfolio_id`, `deleted`);

-- 逻辑删除后的身份记录允许按原标识重新创建。
ALTER TABLE `wf_user_auth`
  DROP INDEX `uk_auth_type_identifier`,
  DROP INDEX `uk_auth_union_identifier`,
  ADD UNIQUE KEY `uk_auth_type_identifier` (`auth_type`, `identifier_hash`, `deleted`),
  ADD UNIQUE KEY `uk_auth_union_identifier` (`union_identifier_hash`, `deleted`);

-- 逻辑删除后的成员允许重新入团，也不再占用团队有效拥有者唯一位。
ALTER TABLE `wf_team_member`
  DROP INDEX `uk_team_member`,
  DROP INDEX `uk_team_active_owner`,
  ADD UNIQUE KEY `uk_team_member` (`team_id`, `user_id`, `deleted`),
  ADD UNIQUE KEY `uk_team_active_owner` (`team_id`, `active_owner_marker`, `deleted`);
