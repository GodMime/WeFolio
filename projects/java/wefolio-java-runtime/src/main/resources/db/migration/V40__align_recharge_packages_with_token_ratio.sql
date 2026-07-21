-- ============================================================
-- WeFolio V40 — 对齐充值套餐与每人民币 100 代币的兑换比例
-- ============================================================

SET NAMES utf8mb4;

UPDATE `wf_recharge_package`
SET `base_points` = CASE `package_code`
      WHEN 'RECHARGE_1_YUAN' THEN 100
      WHEN 'RECHARGE_10_YUAN' THEN 1000
      WHEN 'RECHARGE_50_YUAN' THEN 5000
      WHEN 'RECHARGE_100_YUAN' THEN 10000
    END,
    `bonus_points` = CASE `package_code`
      WHEN 'RECHARGE_1_YUAN' THEN 0
      WHEN 'RECHARGE_10_YUAN' THEN 0
      WHEN 'RECHARGE_50_YUAN' THEN 200
      WHEN 'RECHARGE_100_YUAN' THEN 1000
    END,
    `total_points` = CASE `package_code`
      WHEN 'RECHARGE_1_YUAN' THEN 100
      WHEN 'RECHARGE_10_YUAN' THEN 1000
      WHEN 'RECHARGE_50_YUAN' THEN 5200
      WHEN 'RECHARGE_100_YUAN' THEN 11000
    END,
    `version` = `version` + 1,
    `updated_at` = CURRENT_TIMESTAMP(3)
WHERE `package_code` IN (
    'RECHARGE_1_YUAN',
    'RECHARGE_10_YUAN',
    'RECHARGE_50_YUAN',
    'RECHARGE_100_YUAN'
  )
  AND `package_version` = 1
  AND `status` = 'ACTIVE'
  AND `deleted` = 0;
