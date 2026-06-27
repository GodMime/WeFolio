-- ============================================================
-- WeFolio V9 — 积分规则增加展示分组
-- 背景: “积分规则”页面需要从后端动态获取扣分情况，并按维护/访客分组展示。
-- ============================================================

SET NAMES utf8mb4;

ALTER TABLE `wf_point_rule`
  ADD COLUMN `group_code` VARCHAR(32) CHARACTER SET ascii COLLATE ascii_bin
    NOT NULL DEFAULT 'OTHER' COMMENT '规则展示分组：MAINTENANCE维护 VISITOR访客 OTHER其他'
    AFTER `scene_code`;

UPDATE `wf_point_rule`
SET `group_code` = 'MAINTENANCE'
WHERE `scene_code` IN (
  'UPLOAD_IMAGE',
  'UPLOAD_VIDEO',
  'CREATE_TEAM',
  'MAINTAIN_STANDARD_PORTFOLIO',
  'MAINTAIN_ADVANCED_PORTFOLIO'
);

UPDATE `wf_point_rule`
SET `group_code` = 'VISITOR'
WHERE `scene_code` IN (
  'VISIT_PERSONAL_PORTFOLIO',
  'VIEW_PORTFOLIO_IMAGES',
  'VIEW_PORTFOLIO_VIDEO'
);

ALTER TABLE `wf_point_rule`
  ADD CONSTRAINT `chk_point_rule_group`
    CHECK (`group_code` IN ('MAINTENANCE', 'VISITOR', 'OTHER'));
