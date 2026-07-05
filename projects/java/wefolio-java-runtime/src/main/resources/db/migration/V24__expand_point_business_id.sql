-- 扩大积分业务 ID 字段，支持作品集 ID、作品 ID 与访客 key 组合写入。
ALTER TABLE `wf_point_meter`
  MODIFY COLUMN `business_id` VARCHAR(128) NOT NULL COMMENT '计量业务ID';

ALTER TABLE `wf_point_transaction`
  MODIFY COLUMN `business_id` VARCHAR(128) NOT NULL COMMENT '关联业务ID';
