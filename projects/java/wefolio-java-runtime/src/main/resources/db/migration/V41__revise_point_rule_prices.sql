-- ============================================================
-- WeFolio V41 — 调整积分规则价格与作品存储月费计量单位
-- ============================================================

SET @point_rule_cutover = CURRENT_TIMESTAMP(3);
SET @monthly_storage_cutover = '2026-08-01 00:00:00.000';

-- 五项操作与访客规则随 migration 上线立即切换。
UPDATE `wf_point_rule`
SET `effective_to` = @point_rule_cutover,
    `updated_at` = @point_rule_cutover
WHERE `scene_code` IN (
    'CREATE_TEAM',
    'MAINTAIN_ADVANCED_PORTFOLIO',
    'MAINTAIN_STANDARD_PORTFOLIO',
    'VIEW_PORTFOLIO_VIDEO',
    'VISIT_PERSONAL_PORTFOLIO'
  )
  AND `status` = 'ACTIVE'
  AND `effective_to` IS NULL
  AND `deleted` = 0;

-- 月费从 2026-08 账期开始按每完整 2MB 扣 1 积分。
UPDATE `wf_point_rule`
SET `effective_to` = @monthly_storage_cutover,
    `updated_at` = @point_rule_cutover
WHERE `scene_code` = 'MONTHLY_WORK_STORAGE'
  AND `status` = 'ACTIVE'
  AND `effective_to` IS NULL
  AND `deleted` = 0;

INSERT INTO `wf_point_rule` (
  `rule_code`,
  `rule_version`,
  `rule_name`,
  `transaction_type`,
  `scene_code`,
  `group_code`,
  `calc_mode`,
  `unit_count`,
  `points_value`,
  `config_json`,
  `effective_from`,
  `effective_to`,
  `status`,
  `version`,
  `created_at`,
  `updated_at`,
  `deleted`
) VALUES
  (
    'CREATE_TEAM', 2, '新建团队',
    'CONSUMPTION', 'CREATE_TEAM', 'MAINTENANCE',
    'FIXED_PER_ACTION', 1, 2000,
    JSON_OBJECT('source', 'POINT_RULE_PRICE_UPDATE_2026_07_20'),
    @point_rule_cutover, NULL, 'ACTIVE', 0,
    @point_rule_cutover, @point_rule_cutover, 0
  ),
  (
    'MAINTAIN_ADVANCED_PORTFOLIO', 2, '发布高级作品集',
    'CONSUMPTION', 'MAINTAIN_ADVANCED_PORTFOLIO', 'MAINTENANCE',
    'FIXED_PER_ACTION', 1, 20,
    JSON_OBJECT('source', 'POINT_RULE_PRICE_UPDATE_2026_07_20'),
    @point_rule_cutover, NULL, 'ACTIVE', 0,
    @point_rule_cutover, @point_rule_cutover, 0
  ),
  (
    'MAINTAIN_STANDARD_PORTFOLIO', 3, '发布标准作品集',
    'CONSUMPTION', 'MAINTAIN_STANDARD_PORTFOLIO', 'MAINTENANCE',
    'FIXED_PER_ACTION', 1, 10,
    JSON_OBJECT('source', 'POINT_RULE_PRICE_UPDATE_2026_07_20'),
    @point_rule_cutover, NULL, 'ACTIVE', 0,
    @point_rule_cutover, @point_rule_cutover, 0
  ),
  (
    'VIEW_PORTFOLIO_VIDEO', 3, '查看作品集视频',
    'CONSUMPTION', 'VIEW_PORTFOLIO_VIDEO', 'VISITOR',
    'FIXED_PER_ACTION', 1, 10,
    JSON_OBJECT(
      'source', 'POINT_RULE_PRICE_UPDATE_2026_07_20',
      'dedupeWindowHours', 2,
      'dedupeScope', 'WORK'
    ),
    @point_rule_cutover, NULL, 'ACTIVE', 0,
    @point_rule_cutover, @point_rule_cutover, 0
  ),
  (
    'VISIT_PERSONAL_PORTFOLIO', 3, '访问个人作品集',
    'CONSUMPTION', 'VISIT_PERSONAL_PORTFOLIO', 'VISITOR',
    'FIXED_PER_ACTION', 1, 10,
    JSON_OBJECT(
      'source', 'POINT_RULE_PRICE_UPDATE_2026_07_20',
      'dedupeWindowHours', 2,
      'dedupeScope', 'PORTFOLIO'
    ),
    @point_rule_cutover, NULL, 'ACTIVE', 0,
    @point_rule_cutover, @point_rule_cutover, 0
  ),
  (
    'MONTHLY_WORK_STORAGE', 2, '作品存储月费',
    'CONSUMPTION', 'MONTHLY_WORK_STORAGE', 'MAINTENANCE',
    'MONTHLY_STORAGE_SIZE', 2, 1,
    JSON_OBJECT('source', 'POINT_RULE_PRICE_UPDATE_2026_07_20'),
    @monthly_storage_cutover, NULL, 'ACTIVE', 0,
    @point_rule_cutover, @point_rule_cutover, 0
  );
