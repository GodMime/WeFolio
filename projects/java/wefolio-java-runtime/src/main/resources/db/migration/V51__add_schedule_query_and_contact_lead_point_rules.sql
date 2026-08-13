-- ============================================================
-- WeFolio V51 — 新增访客查档与预留联系信息积分规则
-- ============================================================

ALTER TABLE `wf_point_billing_window`
  DROP CHECK `chk_point_billing_window_scene`,
  ADD CONSTRAINT `chk_point_billing_window_scene` CHECK (
    `scene_code` IN (
      'VISIT_PERSONAL_PORTFOLIO',
      'VIEW_PORTFOLIO_IMAGES',
      'VIEW_PORTFOLIO_VIDEO',
      'QUERY_PORTFOLIO_SCHEDULE',
      'SUBMIT_CONTACT_LEAD'
    )
  );

SET @visitor_intent_rule_effective_from = CURRENT_TIMESTAMP(3);

INSERT INTO `wf_point_rule` (
  `rule_code`, `rule_version`, `rule_name`,
  `transaction_type`, `scene_code`, `group_code`,
  `calc_mode`, `unit_count`, `points_value`, `config_json`,
  `effective_from`, `effective_to`, `status`, `version`,
  `created_at`, `updated_at`, `deleted`
) VALUES
  (
    'QUERY_PORTFOLIO_SCHEDULE', 1, '访客查询档期',
    'CONSUMPTION', 'QUERY_PORTFOLIO_SCHEDULE', 'VISITOR',
    'FIXED_PER_ACTION', 1, 10,
    JSON_OBJECT('source', 'POINT_RULE_UPDATE_2026_08_13',
      'dedupeWindowHours', 2, 'dedupeScope', 'PORTFOLIO'),
    @visitor_intent_rule_effective_from, NULL, 'ACTIVE', 0,
    @visitor_intent_rule_effective_from, @visitor_intent_rule_effective_from, 0
  ),
  (
    'SUBMIT_CONTACT_LEAD', 1, '访客预留联系信息',
    'CONSUMPTION', 'SUBMIT_CONTACT_LEAD', 'VISITOR',
    'FIXED_PER_ACTION', 1, 10,
    JSON_OBJECT('source', 'POINT_RULE_UPDATE_2026_08_13',
      'dedupeWindowHours', 2, 'dedupeScope', 'PORTFOLIO'),
    @visitor_intent_rule_effective_from, NULL, 'ACTIVE', 0,
    @visitor_intent_rule_effective_from, @visitor_intent_rule_effective_from, 0
  );
