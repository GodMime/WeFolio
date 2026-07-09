ALTER TABLE `wf_user_auth`
  ADD COLUMN `open_id` VARCHAR(128) NULL COMMENT '微信 openid 明文，仅服务端用于维护者本人访问识别' AFTER `identifier_ciphertext`;
