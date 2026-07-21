-- 将作品集发布场景的积分规则名称统一为“发布”文案。
UPDATE `wf_point_rule`
SET `rule_name` = '发布标准作品集',
    `updated_at` = CURRENT_TIMESTAMP(3)
WHERE `scene_code` = 'MAINTAIN_STANDARD_PORTFOLIO'
  AND `deleted` = 0;

UPDATE `wf_point_rule`
SET `rule_name` = '发布高级作品集',
    `updated_at` = CURRENT_TIMESTAMP(3)
WHERE `scene_code` = 'MAINTAIN_ADVANCED_PORTFOLIO'
  AND `deleted` = 0;
