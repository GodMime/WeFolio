-- ============================================================
-- WeFolio V39 — 重置历史测试积分并创建迁移赠送订单
-- ============================================================

SET NAMES utf8mb4;

DELETE FROM `wf_system_message`
WHERE `category` = 'POINT';

DELETE FROM `wf_point_billing_window`;
DELETE FROM `wf_point_meter`;
DELETE FROM `wf_work_storage_monthly_bill`;
DELETE FROM `wf_recharge_order`;
DELETE FROM `wf_point_pending_debit`;
DELETE FROM `wf_point_debit_task`;
DELETE FROM `wf_point_gift_order`;
DELETE FROM `wf_point_transaction`;

UPDATE `wf_point_account`
SET `balance` = 0,
    `wechat_balance` = 0,
    `wechat_present_balance` = 0,
    `pending_debit` = 0,
    `wechat_balance_synced_at` = NULL,
    `total_recharged` = 0,
    `total_gifted` = 0,
    `total_consumed` = 0,
    `version` = `version` + 1,
    `updated_at` = CURRENT_TIMESTAMP(3)
WHERE `deleted` = 0;

INSERT INTO `wf_point_account` (
  `user_id`, `balance`, `wechat_balance`, `wechat_present_balance`, `pending_debit`,
  `wechat_balance_synced_at`, `total_recharged`, `total_gifted`, `total_consumed`,
  `created_at`, `updated_at`, `deleted`, `version`
)
SELECT
  u.`id`, 0, 0, 0, 0,
  NULL, 0, 0, 0,
  CURRENT_TIMESTAMP(3), CURRENT_TIMESTAMP(3), 0, 0
FROM `wf_user` u
WHERE u.`status` = 'ACTIVE'
  AND u.`deleted` = 0
  AND NOT EXISTS (
    SELECT 1
    FROM `wf_point_account` a
    WHERE a.`user_id` = u.`id`
      AND a.`deleted` = 0
  );

INSERT INTO `wf_point_gift_order` (
  `order_no`, `account_id`, `user_id`, `scene_code`, `amount`,
  `business_type`, `business_id`, `business_snapshot`, `idempotency_key`,
  `status`, `retry_count`, `next_execute_at`,
  `created_at`, `updated_at`, `deleted`, `version`
)
SELECT
  CONCAT('WFG', SUBSTRING(SHA2(CONCAT('HISTORICAL_USER_MIGRATION_GIFT:', u.`id`), 256), 1, 29)),
  a.`id`,
  u.`id`,
  'HISTORICAL_USER_MIGRATION_GIFT',
  500,
  'HISTORICAL_USER',
  CAST(u.`id` AS CHAR),
  JSON_OBJECT('source', 'V39', 'userId', u.`id`, 'amount', 500),
  CONCAT('HISTORICAL_USER_MIGRATION_GIFT:', u.`id`),
  'READY',
  0,
  CURRENT_TIMESTAMP(3),
  CURRENT_TIMESTAMP(3),
  CURRENT_TIMESTAMP(3),
  0,
  0
FROM `wf_user` u
JOIN `wf_point_account` a
  ON a.`user_id` = u.`id`
 AND a.`deleted` = 0
WHERE u.`status` = 'ACTIVE'
  AND u.`deleted` = 0;
