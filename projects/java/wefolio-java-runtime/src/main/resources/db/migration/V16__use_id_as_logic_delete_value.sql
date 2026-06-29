-- 将逻辑删除值从固定 1 调整为本行主键 ID，避免 deleted 参与唯一索引时重复删除冲突。

ALTER TABLE `wf_ai_generation_task`
  MODIFY COLUMN `deleted` BIGINT UNSIGNED NOT NULL DEFAULT 0 COMMENT '逻辑删除：0未删除，已删除时为主键ID';
UPDATE `wf_ai_generation_task` SET `deleted` = `id` WHERE `deleted` <> 0;

ALTER TABLE `wf_contact_lead`
  MODIFY COLUMN `deleted` BIGINT UNSIGNED NOT NULL DEFAULT 0 COMMENT '逻辑删除：0未删除，已删除时为主键ID';
UPDATE `wf_contact_lead` SET `deleted` = `id` WHERE `deleted` <> 0;

ALTER TABLE `wf_point_account`
  MODIFY COLUMN `deleted` BIGINT UNSIGNED NOT NULL DEFAULT 0 COMMENT '逻辑删除：0未删除，已删除时为主键ID';
UPDATE `wf_point_account` SET `deleted` = `id` WHERE `deleted` <> 0;

ALTER TABLE `wf_point_meter`
  MODIFY COLUMN `deleted` BIGINT UNSIGNED NOT NULL DEFAULT 0 COMMENT '逻辑删除：0未删除，已删除时为主键ID';
UPDATE `wf_point_meter` SET `deleted` = `id` WHERE `deleted` <> 0;

ALTER TABLE `wf_point_rule`
  MODIFY COLUMN `deleted` BIGINT UNSIGNED NOT NULL DEFAULT 0 COMMENT '逻辑删除：0未删除，已删除时为主键ID';
UPDATE `wf_point_rule` SET `deleted` = `id` WHERE `deleted` <> 0;

ALTER TABLE `wf_point_transaction`
  MODIFY COLUMN `deleted` BIGINT UNSIGNED NOT NULL DEFAULT 0 COMMENT '逻辑删除：0未删除，已删除时为主键ID';
UPDATE `wf_point_transaction` SET `deleted` = `id` WHERE `deleted` <> 0;

ALTER TABLE `wf_portfolio`
  MODIFY COLUMN `deleted` BIGINT UNSIGNED NOT NULL DEFAULT 0 COMMENT '逻辑删除：0未删除，已删除时为主键ID';
UPDATE `wf_portfolio` SET `deleted` = `id` WHERE `deleted` <> 0;

ALTER TABLE `wf_portfolio_history`
  MODIFY COLUMN `deleted` BIGINT UNSIGNED NOT NULL DEFAULT 0 COMMENT '逻辑删除：0未删除，已删除时为主键ID';
UPDATE `wf_portfolio_history` SET `deleted` = `id` WHERE `deleted` <> 0;

ALTER TABLE `wf_portfolio_reference`
  MODIFY COLUMN `deleted` BIGINT UNSIGNED NOT NULL DEFAULT 0 COMMENT '逻辑删除：0未删除，已删除时为主键ID';
UPDATE `wf_portfolio_reference` SET `deleted` = `id` WHERE `deleted` <> 0;

ALTER TABLE `wf_portfolio_share_record`
  MODIFY COLUMN `deleted` BIGINT UNSIGNED NOT NULL DEFAULT 0 COMMENT '逻辑删除：0未删除，已删除时为主键ID';
UPDATE `wf_portfolio_share_record` SET `deleted` = `id` WHERE `deleted` <> 0;

ALTER TABLE `wf_recharge_order`
  MODIFY COLUMN `deleted` BIGINT UNSIGNED NOT NULL DEFAULT 0 COMMENT '逻辑删除：0未删除，已删除时为主键ID';
UPDATE `wf_recharge_order` SET `deleted` = `id` WHERE `deleted` <> 0;

ALTER TABLE `wf_recharge_package`
  MODIFY COLUMN `deleted` BIGINT UNSIGNED NOT NULL DEFAULT 0 COMMENT '逻辑删除：0未删除，已删除时为主键ID';
UPDATE `wf_recharge_package` SET `deleted` = `id` WHERE `deleted` <> 0;

ALTER TABLE `wf_referral_relation`
  MODIFY COLUMN `deleted` BIGINT UNSIGNED NOT NULL DEFAULT 0 COMMENT '逻辑删除：0未删除，已删除时为主键ID';
UPDATE `wf_referral_relation` SET `deleted` = `id` WHERE `deleted` <> 0;

ALTER TABLE `wf_schedule`
  MODIFY COLUMN `deleted` BIGINT UNSIGNED NOT NULL DEFAULT 0 COMMENT '逻辑删除：0未删除，已删除时为主键ID';
UPDATE `wf_schedule` SET `deleted` = `id` WHERE `deleted` <> 0;

ALTER TABLE `wf_slot_definition`
  MODIFY COLUMN `deleted` BIGINT UNSIGNED NOT NULL DEFAULT 0 COMMENT '逻辑删除：0未删除，已删除时为主键ID';
UPDATE `wf_slot_definition` SET `deleted` = `id` WHERE `deleted` <> 0;

ALTER TABLE `wf_system_message`
  MODIFY COLUMN `deleted` BIGINT UNSIGNED NOT NULL DEFAULT 0 COMMENT '逻辑删除：0未删除，已删除时为主键ID';
UPDATE `wf_system_message` SET `deleted` = `id` WHERE `deleted` <> 0;

ALTER TABLE `wf_tag`
  MODIFY COLUMN `deleted` BIGINT UNSIGNED NOT NULL DEFAULT 0 COMMENT '逻辑删除：0未删除，已删除时为主键ID';
UPDATE `wf_tag` SET `deleted` = `id` WHERE `deleted` <> 0;

ALTER TABLE `wf_team`
  MODIFY COLUMN `deleted` BIGINT UNSIGNED NOT NULL DEFAULT 0 COMMENT '逻辑删除：0未删除，已删除时为主键ID';
UPDATE `wf_team` SET `deleted` = `id` WHERE `deleted` <> 0;

ALTER TABLE `wf_team_member`
  MODIFY COLUMN `deleted` BIGINT UNSIGNED NOT NULL DEFAULT 0 COMMENT '逻辑删除：0未删除，已删除时为主键ID';
UPDATE `wf_team_member` SET `deleted` = `id` WHERE `deleted` <> 0;

ALTER TABLE `wf_team_member_change_request`
  MODIFY COLUMN `deleted` BIGINT UNSIGNED NOT NULL DEFAULT 0 COMMENT '逻辑删除：0未删除，已删除时为主键ID';
UPDATE `wf_team_member_change_request` SET `deleted` = `id` WHERE `deleted` <> 0;

ALTER TABLE `wf_user`
  MODIFY COLUMN `deleted` BIGINT UNSIGNED NOT NULL DEFAULT 0 COMMENT '逻辑删除：0未删除，已删除时为主键ID';
UPDATE `wf_user` SET `deleted` = `id` WHERE `deleted` <> 0;

ALTER TABLE `wf_user_auth`
  MODIFY COLUMN `deleted` BIGINT UNSIGNED NOT NULL DEFAULT 0 COMMENT '逻辑删除：0未删除，已删除时为主键ID';
UPDATE `wf_user_auth` SET `deleted` = `id` WHERE `deleted` <> 0;

ALTER TABLE `wf_visit_event`
  MODIFY COLUMN `deleted` BIGINT UNSIGNED NOT NULL DEFAULT 0 COMMENT '逻辑删除：0未删除，已删除时为主键ID';
UPDATE `wf_visit_event` SET `deleted` = `id` WHERE `deleted` <> 0;

ALTER TABLE `wf_visit_record`
  MODIFY COLUMN `deleted` BIGINT UNSIGNED NOT NULL DEFAULT 0 COMMENT '逻辑删除：0未删除，已删除时为主键ID';
UPDATE `wf_visit_record` SET `deleted` = `id` WHERE `deleted` <> 0;

ALTER TABLE `wf_work`
  MODIFY COLUMN `deleted` BIGINT UNSIGNED NOT NULL DEFAULT 0 COMMENT '逻辑删除：0未删除，已删除时为主键ID';
UPDATE `wf_work` SET `deleted` = `id` WHERE `deleted` <> 0;

ALTER TABLE `wf_work_tag`
  MODIFY COLUMN `deleted` BIGINT UNSIGNED NOT NULL DEFAULT 0 COMMENT '逻辑删除：0未删除，已删除时为主键ID';
UPDATE `wf_work_tag` SET `deleted` = `id` WHERE `deleted` <> 0;

ALTER TABLE `wf_work_upload_task`
  MODIFY COLUMN `deleted` BIGINT UNSIGNED NOT NULL DEFAULT 0 COMMENT '逻辑删除：0未删除，已删除时为主键ID';
UPDATE `wf_work_upload_task` SET `deleted` = `id` WHERE `deleted` <> 0;
