-- ============================================================
-- WeFolio V22 — 作品集删除后访问与线索展示快照
-- ============================================================

SET NAMES utf8mb4;

ALTER TABLE `wf_visit_record`
  ADD COLUMN `portfolio_title_snapshot` VARCHAR(100) NULL COMMENT '被访问作品集标题快照' AFTER `portfolio_id`,
  ADD COLUMN `portfolio_share_code_snapshot` VARCHAR(32) NULL COMMENT '被访问作品集分享编码快照' AFTER `portfolio_title_snapshot`;

ALTER TABLE `wf_contact_lead`
  ADD COLUMN `portfolio_title_snapshot` VARCHAR(100) NULL COMMENT '来源作品集标题快照' AFTER `portfolio_id`,
  ADD COLUMN `portfolio_share_code_snapshot` VARCHAR(32) NULL COMMENT '来源作品集分享编码快照' AFTER `portfolio_title_snapshot`;
