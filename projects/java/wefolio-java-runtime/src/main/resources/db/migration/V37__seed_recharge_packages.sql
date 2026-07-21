-- ============================================================
-- WeFolio V37 — 初始化微信支付充值套餐
-- ============================================================

SET @recharge_package_effective_from = CURRENT_TIMESTAMP(3);

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
) VALUES
  (
    'RECHARGE_1_YUAN', 1, '1 元档',
    100, 10, 0, 10, 10,
    @recharge_package_effective_from, NULL, 'ACTIVE',
    @recharge_package_effective_from, @recharge_package_effective_from, 0, 0
  ),
  (
    'RECHARGE_10_YUAN', 1, '10 元档',
    1000, 100, 0, 100, 20,
    @recharge_package_effective_from, NULL, 'ACTIVE',
    @recharge_package_effective_from, @recharge_package_effective_from, 0, 0
  ),
  (
    'RECHARGE_50_YUAN', 1, '50 元档',
    5000, 500, 20, 520, 30,
    @recharge_package_effective_from, NULL, 'ACTIVE',
    @recharge_package_effective_from, @recharge_package_effective_from, 0, 0
  ),
  (
    'RECHARGE_100_YUAN', 1, '100 元档',
    10000, 1000, 100, 1100, 40,
    @recharge_package_effective_from, NULL, 'ACTIVE',
    @recharge_package_effective_from, @recharge_package_effective_from, 0, 0
  );
