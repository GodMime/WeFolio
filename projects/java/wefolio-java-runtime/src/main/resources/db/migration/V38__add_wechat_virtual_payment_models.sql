-- ============================================================
-- WeFolio V38 — 新增微信虚拟支付账户、任务、赠送和会话模型
-- ============================================================

SET NAMES utf8mb4;

ALTER TABLE `wf_point_account`
  DROP INDEX `uk_point_account_user`,
  MODIFY COLUMN `balance` BIGINT NOT NULL DEFAULT 0 COMMENT '实际可用积分，可为负数',
  ADD COLUMN `wechat_balance` BIGINT NOT NULL DEFAULT 0 COMMENT '微信总代币余额快照' AFTER `balance`,
  ADD COLUMN `wechat_present_balance` BIGINT NOT NULL DEFAULT 0 COMMENT '微信赠送代币余额快照' AFTER `wechat_balance`,
  ADD COLUMN `pending_debit` BIGINT NOT NULL DEFAULT 0 COMMENT '当前待扣代币聚合' AFTER `wechat_present_balance`,
  ADD COLUMN `wechat_balance_synced_at` DATETIME(3) NULL COMMENT '最近微信权威余额同步时间' AFTER `pending_debit`,
  ADD UNIQUE KEY `uk_point_account_user` (`user_id`, `deleted`),
  ADD CONSTRAINT `chk_point_account_wechat_balance` CHECK (`wechat_balance` >= 0),
  ADD CONSTRAINT `chk_point_account_present_balance` CHECK (`wechat_present_balance` >= 0),
  ADD CONSTRAINT `chk_point_account_pending_debit` CHECK (`pending_debit` >= 0),
  ADD CONSTRAINT `chk_point_account_present_total` CHECK (`wechat_present_balance` <= `wechat_balance`);

-- 历史本地余额先映射为微信权威余额快照，确保账户公式约束建立时数据合法。
UPDATE `wf_point_account`
SET `wechat_balance` = `balance`,
    `wechat_present_balance` = 0,
    `pending_debit` = 0,
    `wechat_balance_synced_at` = NULL;

ALTER TABLE `wf_point_account`
  ADD CONSTRAINT `chk_point_account_balance_formula`
    CHECK (`balance` = `wechat_balance` - `pending_debit`);

ALTER TABLE `wf_point_transaction`
  DROP CHECK `chk_point_tx_type`,
  DROP INDEX `uk_point_tx_idempotency`,
  MODIFY COLUMN `balance_before` BIGINT NOT NULL COMMENT '变动前实际可用积分',
  MODIFY COLUMN `balance_after` BIGINT NOT NULL COMMENT '变动后实际可用积分',
  ADD UNIQUE KEY `uk_point_tx_idempotency` (`idempotency_key`, `deleted`),
  ADD CONSTRAINT `chk_point_tx_type` CHECK (
    `transaction_type` IN ('RECHARGE', 'CONSUMPTION', 'REFUND', 'GIFT', 'WECHAT_SYNC')
  );

CREATE TABLE `wf_point_pending_debit` (
  `id` BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '主键',
  `account_id` BIGINT UNSIGNED NOT NULL COMMENT '积分账户ID',
  `user_id` BIGINT UNSIGNED NOT NULL COMMENT '被扣费维护者用户ID',
  `point_transaction_id` BIGINT UNSIGNED NOT NULL COMMENT '消费积分流水ID',
  `original_amount` BIGINT NOT NULL COMMENT '原始待扣金额',
  `remaining_amount` BIGINT NOT NULL COMMENT '当前剩余待扣金额',
  `last_settled_at` DATETIME(3) NULL COMMENT '最近核销时间',
  `created_at` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '创建时间',
  `updated_at` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3)
    ON UPDATE CURRENT_TIMESTAMP(3) COMMENT '更新时间',
  `deleted` BIGINT UNSIGNED NOT NULL DEFAULT 0 COMMENT '逻辑删除：0未删除，已删除时为主键ID',
  `version` INT UNSIGNED NOT NULL DEFAULT 0 COMMENT '乐观锁版本号',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_point_pending_transaction` (`point_transaction_id`, `deleted`),
  KEY `idx_point_pending_user_time` (`user_id`, `deleted`, `created_at`, `id`),
  CONSTRAINT `chk_point_pending_original` CHECK (`original_amount` > 0),
  CONSTRAINT `chk_point_pending_remaining` CHECK (
    `remaining_amount` >= 0 AND `remaining_amount` <= `original_amount`
  )
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
  ROW_FORMAT=DYNAMIC COMMENT='积分消费待扣来源明细';

CREATE TABLE `wf_point_debit_task` (
  `id` BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '主键',
  `task_no` VARCHAR(32) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '本地任务号及微信扣币订单号',
  `account_id` BIGINT UNSIGNED NOT NULL COMMENT '积分账户ID',
  `user_id` BIGINT UNSIGNED NOT NULL COMMENT '被扣费维护者用户ID',
  `status` VARCHAR(32) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '扣币任务状态',
  `active_flag` TINYINT UNSIGNED NULL COMMENT '阻塞后续任务时固定为1',
  `request_amount` BIGINT NULL COMMENT '已持久化微信请求金额',
  `settled_amount` BIGINT NOT NULL DEFAULT 0 COMMENT '本任务成功核销金额',
  `used_present_amount` BIGINT NOT NULL DEFAULT 0 COMMENT '微信返回的赠送余额消耗',
  `pending_before` BIGINT NULL COMMENT '处理前待扣金额',
  `pending_after` BIGINT NULL COMMENT '处理后待扣金额',
  `retry_count` INT UNSIGNED NOT NULL DEFAULT 0 COMMENT '已执行自动重试次数',
  `next_execute_at` DATETIME(3) NOT NULL COMMENT '下次执行时间',
  `lease_owner` VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NULL COMMENT '任务租约实例',
  `lease_until` DATETIME(3) NULL COMMENT '任务租约截止时间',
  `session_version` BIGINT UNSIGNED NULL COMMENT '本次调用使用的会话版本',
  `wechat_balance_before` BIGINT NULL COMMENT '调用前微信余额',
  `wechat_balance_after` BIGINT NULL COMMENT '调用后微信余额',
  `last_error_code` VARCHAR(32) CHARACTER SET ascii COLLATE ascii_bin NULL COMMENT '最后失败码',
  `last_error_message` VARCHAR(255) NULL COMMENT '脱敏失败原因',
  `last_failed_at` DATETIME(3) NULL COMMENT '最后失败时间',
  `started_at` DATETIME(3) NULL COMMENT '首次开始时间',
  `completed_at` DATETIME(3) NULL COMMENT '完成时间',
  `created_at` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '创建时间',
  `updated_at` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3)
    ON UPDATE CURRENT_TIMESTAMP(3) COMMENT '更新时间',
  `deleted` BIGINT UNSIGNED NOT NULL DEFAULT 0 COMMENT '逻辑删除：0未删除，已删除时为主键ID',
  `version` INT UNSIGNED NOT NULL DEFAULT 0 COMMENT '乐观锁版本号',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_point_debit_task_no` (`task_no`, `deleted`),
  UNIQUE KEY `uk_point_debit_task_active` (`user_id`, `active_flag`, `deleted`),
  KEY `idx_point_debit_task_scan` (`status`, `next_execute_at`, `lease_until`),
  CONSTRAINT `chk_point_debit_task_status` CHECK (
    `status` IN (
      'WAITING', 'RUNNING', 'WAITING_SESSION', 'RETRY_WAIT',
      'SUCCEEDED', 'PARTIAL', 'NO_BALANCE', 'FAILED'
    )
  ),
  CONSTRAINT `chk_point_debit_task_active` CHECK (`active_flag` IS NULL OR `active_flag` = 1),
  CONSTRAINT `chk_point_debit_task_amount` CHECK (
    (`request_amount` IS NULL OR `request_amount` > 0)
    AND `settled_amount` >= 0
    AND `used_present_amount` >= 0
  )
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
  ROW_FORMAT=DYNAMIC COMMENT='微信代币扣币历史逻辑任务';

CREATE TABLE `wf_point_gift_order` (
  `id` BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '主键',
  `order_no` VARCHAR(32) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '稳定微信赠送订单号',
  `account_id` BIGINT UNSIGNED NOT NULL COMMENT '积分账户ID',
  `user_id` BIGINT UNSIGNED NOT NULL COMMENT '受赠用户ID',
  `scene_code` VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '赠送场景编码',
  `amount` BIGINT NOT NULL COMMENT '赠送积分',
  `business_type` VARCHAR(32) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '来源业务类型',
  `business_id` VARCHAR(128) NOT NULL COMMENT '来源业务ID',
  `business_snapshot` JSON NOT NULL COMMENT '不可变来源快照',
  `idempotency_key` VARCHAR(64) NOT NULL COMMENT '业务幂等键',
  `status` VARCHAR(32) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '赠送订单状态',
  `retry_count` INT UNSIGNED NOT NULL DEFAULT 0 COMMENT '自动重试次数',
  `next_execute_at` DATETIME(3) NOT NULL COMMENT '下次执行时间',
  `lease_owner` VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NULL COMMENT '领取实例',
  `lease_until` DATETIME(3) NULL COMMENT '租约截止时间',
  `point_transaction_id` BIGINT UNSIGNED NULL COMMENT '赠送积分流水ID',
  `wechat_balance_after` BIGINT NULL COMMENT '微信返回总代币余额',
  `wechat_present_balance_after` BIGINT NULL COMMENT '微信返回赠送代币余额',
  `last_error_code` VARCHAR(32) CHARACTER SET ascii COLLATE ascii_bin NULL COMMENT '最后失败码',
  `last_error_message` VARCHAR(255) NULL COMMENT '脱敏失败原因',
  `last_failed_at` DATETIME(3) NULL COMMENT '最后失败时间',
  `completed_at` DATETIME(3) NULL COMMENT '完成时间',
  `created_at` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '创建时间',
  `updated_at` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3)
    ON UPDATE CURRENT_TIMESTAMP(3) COMMENT '更新时间',
  `deleted` BIGINT UNSIGNED NOT NULL DEFAULT 0 COMMENT '逻辑删除：0未删除，已删除时为主键ID',
  `version` INT UNSIGNED NOT NULL DEFAULT 0 COMMENT '乐观锁版本号',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_point_gift_order_no` (`order_no`, `deleted`),
  UNIQUE KEY `uk_point_gift_idempotency` (`idempotency_key`, `deleted`),
  KEY `idx_point_gift_order_scan` (`status`, `next_execute_at`, `lease_until`),
  KEY `idx_point_gift_order_user` (`user_id`, `created_at`),
  CONSTRAINT `chk_point_gift_order_amount` CHECK (`amount` > 0),
  CONSTRAINT `chk_point_gift_order_status` CHECK (
    `status` IN ('READY', 'RETRY_WAIT', 'SUCCEEDED', 'FAILED')
  )
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
  ROW_FORMAT=DYNAMIC COMMENT='微信代币赠送订单';

CREATE TABLE `wf_maintainer_wechat_session` (
  `id` BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '主键',
  `user_id` BIGINT UNSIGNED NOT NULL COMMENT '维护者用户ID',
  `auth_id` BIGINT UNSIGNED NOT NULL COMMENT '微信认证记录ID',
  `session_key_ciphertext` VARCHAR(512) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT 'AES-GCM会话密文',
  `session_version` BIGINT UNSIGNED NOT NULL COMMENT '会话版本',
  `status` VARCHAR(32) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '会话状态',
  `last_user_ip` VARCHAR(45) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '可信代理链解析的客户端IP',
  `refreshed_at` DATETIME(3) NOT NULL COMMENT '最近刷新时间',
  `invalidated_at` DATETIME(3) NULL COMMENT '失效时间',
  `invalid_reason` VARCHAR(128) NULL COMMENT '脱敏失效原因',
  `created_at` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '创建时间',
  `updated_at` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3)
    ON UPDATE CURRENT_TIMESTAMP(3) COMMENT '更新时间',
  `deleted` BIGINT UNSIGNED NOT NULL DEFAULT 0 COMMENT '逻辑删除：0未删除，已删除时为主键ID',
  `version` INT UNSIGNED NOT NULL DEFAULT 0 COMMENT '乐观锁版本号',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_maintainer_wechat_session_user` (`user_id`, `deleted`),
  KEY `idx_maintainer_wechat_session_auth` (`auth_id`),
  CONSTRAINT `chk_maintainer_wechat_session_status` CHECK (`status` IN ('AVAILABLE', 'INVALID'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
  ROW_FORMAT=DYNAMIC COMMENT='维护者微信会话';

ALTER TABLE `wf_recharge_order`
  DROP CHECK `chk_recharge_status`,
  DROP CHECK `chk_recharge_channel`,
  DROP INDEX `uk_recharge_merchant_order`,
  DROP INDEX `uk_recharge_payment_transaction`,
  ADD COLUMN `buy_quantity` BIGINT UNSIGNED NULL COMMENT '虚拟支付购买代币数量' AFTER `total_points`,
  ADD COLUMN `wechat_order_id` VARCHAR(128) NULL COMMENT '微信虚拟支付订单ID' AFTER `pay_channel`,
  ADD COLUMN `channel_order_id` VARCHAR(128) NULL COMMENT '渠道订单ID' AFTER `wechat_order_id`,
  ADD COLUMN `wxpay_order_id` VARCHAR(128) NULL COMMENT '微信支付侧订单ID' AFTER `channel_order_id`,
  ADD COLUMN `paid_fee` BIGINT UNSIGNED NULL COMMENT '微信查询返回实际支付分数' AFTER `wxpay_order_id`,
  ADD COLUMN `point_transaction_id` BIGINT UNSIGNED NULL COMMENT '基础充值积分流水ID' AFTER `paid_fee`,
  ADD COLUMN `bonus_gift_order_id` BIGINT UNSIGNED NULL COMMENT '充值赠送订单ID' AFTER `point_transaction_id`,
  ADD COLUMN `last_query_error_code` VARCHAR(32) CHARACTER SET ascii COLLATE ascii_bin NULL COMMENT '最近查单错误码' AFTER `bonus_gift_order_id`,
  ADD COLUMN `last_query_error_message` VARCHAR(255) NULL COMMENT '最近查单脱敏错误' AFTER `last_query_error_code`,
  ADD COLUMN `last_query_error_at` DATETIME(3) NULL COMMENT '最近查单失败时间' AFTER `last_query_error_message`,
  ADD COLUMN `refunded_at` DATETIME(3) NULL COMMENT '平台退款完成时间' AFTER `closed_at`;

UPDATE `wf_recharge_order`
SET `buy_quantity` = `base_points`,
    `pay_channel` = 'WECHAT_VIRTUAL_PAYMENT';

ALTER TABLE `wf_recharge_order`
  MODIFY COLUMN `buy_quantity` BIGINT UNSIGNED NOT NULL COMMENT '虚拟支付购买代币数量',
  MODIFY COLUMN `status` VARCHAR(32) CHARACTER SET ascii COLLATE ascii_bin
    NOT NULL DEFAULT 'PENDING_PAYMENT' COMMENT '虚拟支付充值订单状态',
  MODIFY COLUMN `pay_channel` VARCHAR(32) CHARACTER SET ascii COLLATE ascii_bin
    NOT NULL DEFAULT 'WECHAT_VIRTUAL_PAYMENT' COMMENT '固定微信虚拟支付',
  DROP COLUMN `prepay_id`,
  DROP COLUMN `payment_transaction_id`,
  ADD UNIQUE KEY `uk_recharge_merchant_order` (`merchant_order_no`, `deleted`),
  ADD UNIQUE KEY `uk_recharge_wechat_order` (`wechat_order_id`, `deleted`),
  ADD UNIQUE KEY `uk_recharge_channel_order` (`channel_order_id`, `deleted`),
  ADD CONSTRAINT `chk_recharge_status` CHECK (
    `status` IN ('PENDING_PAYMENT', 'PAID', 'PAYMENT_FAILED', 'CLOSED', 'REFUNDED')
  ),
  ADD CONSTRAINT `chk_recharge_channel` CHECK (`pay_channel` = 'WECHAT_VIRTUAL_PAYMENT'),
  ADD CONSTRAINT `chk_recharge_buy_quantity` CHECK (`buy_quantity` = `base_points`);

UPDATE `wf_work_storage_monthly_bill`
SET `billing_status` = 'SKIPPED_NON_POSITIVE_BALANCE',
    `points_deducted` = 0,
    `points_shortfall` = `points_due`
WHERE `billing_status` = 'PARTIAL';

ALTER TABLE `wf_work_storage_monthly_bill`
  DROP CHECK `chk_work_storage_bill_status`,
  MODIFY COLUMN `balance_before` BIGINT NOT NULL DEFAULT 0 COMMENT '扣除前实际可用积分',
  MODIFY COLUMN `balance_after` BIGINT NOT NULL DEFAULT 0 COMMENT '扣除后实际可用积分',
  ADD CONSTRAINT `chk_work_storage_bill_status` CHECK (
    `billing_status` IN ('NO_CHARGE', 'CHARGED', 'SKIPPED_NON_POSITIVE_BALANCE')
  );
