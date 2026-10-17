-- 上线前隔离旧注册、访客身份写入与受影响账户资金入口。
-- 只调整两个身份列，不修改数据库或表的默认字符集与排序规则。
ALTER TABLE `wf_user_auth`
  MODIFY COLUMN `open_id` VARCHAR(128) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin
    NULL DEFAULT NULL COMMENT '微信 openid 明文，仅服务端用于维护者本人访问识别',
  ADD UNIQUE KEY `uk_auth_type_open_id` (`auth_type`, `open_id`, `deleted`);

ALTER TABLE `wf_visitor`
  MODIFY COLUMN `openid` VARCHAR(128) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin
    NOT NULL COMMENT '微信 openid 明文';

ALTER TABLE `wf_user`
  DROP INDEX `uk_user_phone_number`,
  ADD INDEX `idx_user_phone_number` (`phone_number`, `deleted`);
