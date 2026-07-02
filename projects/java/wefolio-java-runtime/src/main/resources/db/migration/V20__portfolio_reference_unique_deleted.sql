-- ============================================================
-- WeFolio V20 — 作品集引用唯一索引兼容逻辑删除
-- ============================================================

SET NAMES utf8mb4;

ALTER TABLE `wf_portfolio_reference`
  DROP INDEX `uk_portfolio_reference_scope`,
  ADD UNIQUE KEY `uk_portfolio_reference_scope`
    (`portfolio_id`, `config_scope`, `component_path`, `reference_type`, `reference_id`, `deleted`);
