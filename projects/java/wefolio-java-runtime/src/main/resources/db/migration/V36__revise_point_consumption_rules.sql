-- ============================================================
-- WeFolio V36 — 调整作品维护与访客查看积分规则
-- ============================================================

SET @point_rule_cutover = CURRENT_TIMESTAMP(3);

UPDATE `wf_point_rule`
SET `effective_to` = @point_rule_cutover,
    `updated_at` = @point_rule_cutover
WHERE `scene_code` IN (
    'MAINTAIN_STANDARD_PORTFOLIO',
    'UPLOAD_IMAGE',
    'UPLOAD_VIDEO',
    'VISIT_PERSONAL_PORTFOLIO',
    'VIEW_PORTFOLIO_IMAGES',
    'VIEW_PORTFOLIO_VIDEO'
  )
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
    'MAINTAIN_STANDARD_PORTFOLIO', 2, '发布标准作品集',
    'CONSUMPTION', 'MAINTAIN_STANDARD_PORTFOLIO', 'MAINTENANCE',
    'FIXED_PER_ACTION', 1, 5,
    JSON_OBJECT('source', 'POINT_RULE_UPDATE_2026_07_16'),
    @point_rule_cutover, NULL, 'ACTIVE', 0,
    @point_rule_cutover, @point_rule_cutover, 0
  ),
  (
    'UPLOAD_IMAGE', 2, '上传图片作品',
    'CONSUMPTION', 'UPLOAD_IMAGE', 'MAINTENANCE',
    'FIXED_PER_ACTION', 1, 5,
    JSON_OBJECT('source', 'POINT_RULE_UPDATE_2026_07_16'),
    @point_rule_cutover, NULL, 'ACTIVE', 0,
    @point_rule_cutover, @point_rule_cutover, 0
  ),
  (
    'UPLOAD_VIDEO', 2, '上传视频作品',
    'CONSUMPTION', 'UPLOAD_VIDEO', 'MAINTENANCE',
    'FIXED_PER_ACTION', 1, 10,
    JSON_OBJECT('source', 'POINT_RULE_UPDATE_2026_07_16'),
    @point_rule_cutover, NULL, 'ACTIVE', 0,
    @point_rule_cutover, @point_rule_cutover, 0
  ),
  (
    'VISIT_PERSONAL_PORTFOLIO', 2, '访问个人作品集',
    'CONSUMPTION', 'VISIT_PERSONAL_PORTFOLIO', 'VISITOR',
    'FIXED_PER_ACTION', 1, 1,
    JSON_OBJECT(
      'source', 'POINT_RULE_UPDATE_2026_07_16',
      'dedupeWindowHours', 2,
      'dedupeScope', 'PORTFOLIO'
    ),
    @point_rule_cutover, NULL, 'ACTIVE', 0,
    @point_rule_cutover, @point_rule_cutover, 0
  ),
  (
    'VIEW_PORTFOLIO_IMAGES', 2, '查看作品集图片',
    'CONSUMPTION', 'VIEW_PORTFOLIO_IMAGES', 'VISITOR',
    'FIXED_PER_ACTION', 1, 1,
    JSON_OBJECT(
      'source', 'POINT_RULE_UPDATE_2026_07_16',
      'dedupeWindowHours', 2,
      'dedupeScope', 'WORK'
    ),
    @point_rule_cutover, NULL, 'ACTIVE', 0,
    @point_rule_cutover, @point_rule_cutover, 0
  ),
  (
    'VIEW_PORTFOLIO_VIDEO', 2, '查看作品集视频',
    'CONSUMPTION', 'VIEW_PORTFOLIO_VIDEO', 'VISITOR',
    'FIXED_PER_ACTION', 1, 5,
    JSON_OBJECT(
      'source', 'POINT_RULE_UPDATE_2026_07_16',
      'dedupeWindowHours', 2,
      'dedupeScope', 'WORK'
    ),
    @point_rule_cutover, NULL, 'ACTIVE', 0,
    @point_rule_cutover, @point_rule_cutover, 0
  );
