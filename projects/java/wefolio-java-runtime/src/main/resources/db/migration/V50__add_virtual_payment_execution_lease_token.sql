SET NAMES utf8mb4;

ALTER TABLE `wf_point_debit_task`
    ADD COLUMN `execution_lease_token` CHAR(32) CHARACTER SET ascii COLLATE ascii_bin NULL
        COMMENT '当前执行租约令牌，每次成功领取任务时重新生成'
        AFTER `lease_owner`;

ALTER TABLE `wf_point_gift_order`
    ADD COLUMN `execution_lease_token` CHAR(32) CHARACTER SET ascii COLLATE ascii_bin NULL
        COMMENT '当前执行租约令牌，每次成功领取订单时重新生成'
        AFTER `lease_owner`;
