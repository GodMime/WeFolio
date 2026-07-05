-- 将历史访问汇总关联到第一条有效访客，并收紧全局访客 ID 非空约束。
ALTER TABLE `wf_visit_record`
  DROP INDEX `uk_visit_visitor_portfolio`,
  ADD KEY `idx_visit_visitor_portfolio` (`visitor_id`, `portfolio_id`, `deleted`);

UPDATE `wf_visit_record` AS `visit_record`
SET `visitor_id` = (
  SELECT `id` FROM `wf_visitor` WHERE `deleted` = 0 ORDER BY `id` ASC LIMIT 1
)
WHERE `visit_record`.`visitor_id` IS NULL;

ALTER TABLE `wf_visit_record`
  MODIFY COLUMN `visitor_id` BIGINT UNSIGNED NOT NULL COMMENT '全局访客 ID';
