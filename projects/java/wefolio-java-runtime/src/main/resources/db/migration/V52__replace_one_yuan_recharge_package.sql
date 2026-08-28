-- ============================================================
-- WeFolio V52 — 将一元充值套餐替换为五元充值套餐
-- ============================================================

UPDATE `wf_recharge_package`
SET `status` = 'DISABLED',
    `effective_to` = CURRENT_TIMESTAMP(3),
    `updated_at` = CURRENT_TIMESTAMP(3),
    `version` = `version` + 1
WHERE `package_code` = 'RECHARGE_1_YUAN'
  AND `package_version` = 1
  AND `status` = 'ACTIVE'
  AND `deleted` = 0;

INSERT INTO `wf_recharge_package` (
  `package_code`,
  `package_version`,
  `package_name`,
  `amount_fen`,
  `base_points`,
  `bonus_points`,
  `total_points`,
  `sort_order`,
  `effective_from`,
  `effective_to`,
  `status`,
  `created_at`,
  `updated_at`,
  `deleted`,
  `version`
) VALUES (
  'RECHARGE_5_YUAN', 1, '5 元档',
  500, 500, 0, 500, 10,
  CURRENT_TIMESTAMP(3), NULL, 'ACTIVE',
  CURRENT_TIMESTAMP(3), CURRENT_TIMESTAMP(3), 0, 0
);
