-- 微信手机号注册需要数据库唯一约束兜底，避免并发首次注册生成重复用户。
ALTER TABLE `wf_user`
  DROP INDEX `idx_user_phone_number`,
  ADD UNIQUE KEY `uk_user_phone_number` (`phone_number`, `deleted`);
