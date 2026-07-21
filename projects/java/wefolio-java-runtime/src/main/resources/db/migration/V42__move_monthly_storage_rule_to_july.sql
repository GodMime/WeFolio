-- 作品存储月费从 2026-07 账期起按每完整 2MB 扣 1 积分执行。
UPDATE `wf_point_rule`
SET `effective_from` = CASE WHEN `rule_version` = 2 THEN '2026-07-01 00:00:00.000' ELSE `effective_from` END,
    `effective_to` = CASE WHEN `rule_version` = 1 THEN '2026-07-01 00:00:00.000' ELSE `effective_to` END,
    `updated_at` = CURRENT_TIMESTAMP(3)
WHERE `rule_code` = 'MONTHLY_WORK_STORAGE'
  AND `rule_version` IN (1, 2)
  AND `deleted` = 0;
