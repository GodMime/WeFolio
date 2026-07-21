-- ============================================================
-- WeFolio V33 — 将作品存储月费首个结算账期调整为 2026-06
-- ============================================================

SET NAMES utf8mb4;

UPDATE `wf_point_rule`
SET `effective_from` = '2026-06-01 00:00:00.000',
    `updated_at` = CURRENT_TIMESTAMP(3)
WHERE `rule_code` = 'MONTHLY_WORK_STORAGE'
  AND `rule_version` = 1
  AND `deleted` = 0;
