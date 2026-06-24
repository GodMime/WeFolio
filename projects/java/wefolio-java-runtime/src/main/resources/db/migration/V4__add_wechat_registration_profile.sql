-- ============================================================
-- WeFolio V4 — 微信首次注册资料字段
-- ============================================================

ALTER TABLE `wf_user`
  ADD COLUMN `phone_number` VARCHAR(32) NULL COMMENT '微信手机号快速验证获得的手机号' AFTER `contact_phone_ciphertext`,
  ADD COLUMN `phone_country_code` VARCHAR(8) NULL COMMENT '手机号国家或地区码' AFTER `phone_number`,
  ADD COLUMN `phone_last4` CHAR(4) NULL COMMENT '手机号尾号' AFTER `phone_country_code`,
  ADD COLUMN `wechat_openpid` VARCHAR(128) NULL COMMENT '微信插件用户唯一标识openpid' AFTER `phone_last4`,
  ADD KEY `idx_user_phone_number` (`phone_number`);
