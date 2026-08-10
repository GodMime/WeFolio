package com.jxc.wefolio.service.payment;

import com.jxc.wefolio.mapper.PointDebitTaskEntityMapper;
import com.jxc.wefolio.mapper.PointGiftOrderEntityMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 微信虚拟支付租约接管后的数据库陈旧写入集成测试。
 */
@ActiveProfiles("test")
@SpringBootTest
class VirtualPaymentStaleWriteIntegrationTest {

    private static final String TOKEN_A = "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA";
    private static final String TOKEN_B = "BBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBB";

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private PointGiftOrderEntityMapper giftOrderMapper;

    @Autowired
    private PointDebitTaskEntityMapper debitTaskMapper;

    @Autowired
    private PointGiftOrderTransactionService giftOrderTransactionService;

    @Autowired
    private PointDebitTaskTransactionService debitTaskTransactionService;

    @BeforeEach
    void setUp() {
        createTables();
        insertFixtures();
    }

    @AfterEach
    void tearDown() {
        jdbcTemplate.execute("DROP TABLE IF EXISTS wf_point_transaction");
        jdbcTemplate.execute("DROP TABLE IF EXISTS wf_point_pending_debit");
        jdbcTemplate.execute("DROP TABLE IF EXISTS wf_point_debit_task");
        jdbcTemplate.execute("DROP TABLE IF EXISTS wf_point_gift_order");
        jdbcTemplate.execute("DROP TABLE IF EXISTS wf_point_account");
    }

    /** A 租约过期并由 B 接管后，A 不得写账户或流水，B 仍可完成赠送。 */
    @Test
    void staleGiftCompletionShouldNotWriteAccountOrTransaction() {
        assertThat(giftOrderTransactionService.tryClaim(17L, TOKEN_A)).isNotNull();
        expireLease("wf_point_gift_order", 17L);
        assertThat(giftOrderTransactionService.tryClaim(17L, TOKEN_B)).isNotNull();

        assertThatThrownBy(() -> giftOrderTransactionService.completeSuccess(
                17L, TOKEN_A, giftSuccessResult()))
                .isInstanceOf(ExecutionLeaseLostException.class);
        assertThat(accountValue(41L, "balance")).isEqualTo(100L);
        assertThat(accountValue(41L, "total_gifted")).isZero();
        assertThat(rowCount("wf_point_transaction")).isZero();

        giftOrderTransactionService.completeSuccess(17L, TOKEN_B, giftSuccessResult());

        assertThat(accountValue(41L, "balance")).isEqualTo(125L);
        assertThat(accountValue(41L, "total_gifted")).isEqualTo(25L);
        assertThat(rowCount("wf_point_transaction")).isEqualTo(1L);
        assertThat(textValue("wf_point_gift_order", 17L, "status")).isEqualTo("SUCCEEDED");
    }

    /** A 租约过期并由 B 接管后，A 不得同步或核销，B 可原子完成扣币。 */
    @Test
    void staleDebitExecutionShouldNotWriteAccountOrPendingDetails() {
        assertThat(debitTaskTransactionService.tryClaim(23L, TOKEN_A)).isNotNull();
        expireLease("wf_point_debit_task", 23L);
        assertThat(debitTaskTransactionService.tryClaim(23L, TOKEN_B)).isNotNull();

        assertThatThrownBy(() -> debitTaskTransactionService.syncBalance(
                23L, TOKEN_A, 900L, 100L))
                .isInstanceOf(ExecutionLeaseLostException.class);
        assertThatThrownBy(() -> debitTaskTransactionService.completeSuccess(
                23L, TOKEN_A, debitSuccessResult()))
                .isInstanceOf(ExecutionLeaseLostException.class);
        assertDebitStateUnchanged();

        debitTaskTransactionService.completeSuccess(23L, TOKEN_B, debitSuccessResult());

        assertThat(accountValue(42L, "pending_debit")).isZero();
        assertThat(accountValue(42L, "balance")).isEqualTo(450L);
        assertThat(longValue("wf_point_pending_debit", 51L, "remaining_amount")).isZero();
        assertThat(textValue("wf_point_debit_task", 23L, "status")).isEqualTo("SUCCEEDED");
    }

    /** 赠送结算后续失败时，事务内续租也必须随账户写入一起回滚。 */
    @Test
    void failedGiftCompletionShouldRollbackLeaseRenewal() {
        assertThat(giftOrderTransactionService.tryClaim(17L, TOKEN_A)).isNotNull();
        expireLease("wf_point_gift_order", 17L);
        LocalDateTime leaseUntilBefore = dateTimeValue(
                "wf_point_gift_order", 17L, "lease_until");
        jdbcTemplate.update("DELETE FROM wf_point_account WHERE id = 41");

        assertThatThrownBy(() -> giftOrderTransactionService.completeSuccess(
                17L, TOKEN_A, giftSuccessResult()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("积分账户不存在");

        assertThat(dateTimeValue("wf_point_gift_order", 17L, "lease_until"))
                .isEqualTo(leaseUntilBefore);
        assertThat(rowCount("wf_point_transaction")).isZero();
    }

    /** 扣币结算后续失败时，事务内续租与账户核销必须同时回滚。 */
    @Test
    void failedDebitCompletionShouldRollbackLeaseRenewalAndAccountSettlement() {
        assertThat(debitTaskTransactionService.tryClaim(23L, TOKEN_A)).isNotNull();
        expireLease("wf_point_debit_task", 23L);
        LocalDateTime leaseUntilBefore = dateTimeValue(
                "wf_point_debit_task", 23L, "lease_until");
        jdbcTemplate.update("DELETE FROM wf_point_pending_debit WHERE id = 51");

        assertThatThrownBy(() -> debitTaskTransactionService.completeSuccess(
                23L, TOKEN_A, debitSuccessResult()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("待扣来源明细总额不足");

        assertThat(dateTimeValue("wf_point_debit_task", 23L, "lease_until"))
                .isEqualTo(leaseUntilBefore);
        assertThat(accountValue(42L, "pending_debit")).isEqualTo(50L);
        assertThat(accountValue(42L, "balance")).isEqualTo(450L);
    }

    /** 断言旧扣币执行未修改账户和待扣来源。 */
    private void assertDebitStateUnchanged() {
        assertThat(accountValue(42L, "wechat_balance")).isEqualTo(500L);
        assertThat(accountValue(42L, "pending_debit")).isEqualTo(50L);
        assertThat(accountValue(42L, "balance")).isEqualTo(450L);
        assertThat(longValue("wf_point_pending_debit", 51L, "remaining_amount")).isEqualTo(50L);
    }

    /** 将指定任务租约改为数据库当前时间之前。 */
    private void expireLease(String table, long id) {
        jdbcTemplate.update("UPDATE " + table
                + " SET lease_until = DATEADD('SECOND', -1, CURRENT_TIMESTAMP) WHERE id = ?", id);
    }

    /** 读取积分账户数值字段。 */
    private long accountValue(long id, String column) {
        return longValue("wf_point_account", id, column);
    }

    /** 读取指定表的数值字段。 */
    private long longValue(String table, long id, String column) {
        Long value = jdbcTemplate.queryForObject(
                "SELECT " + column + " FROM " + table + " WHERE id = ?", Long.class, id);
        return value == null ? 0L : value;
    }

    /** 读取指定表的文本字段。 */
    private String textValue(String table, long id, String column) {
        return jdbcTemplate.queryForObject(
                "SELECT " + column + " FROM " + table + " WHERE id = ?", String.class, id);
    }

    /** 读取指定表的时间字段。 */
    private LocalDateTime dateTimeValue(String table, long id, String column) {
        return jdbcTemplate.queryForObject(
                "SELECT " + column + " FROM " + table + " WHERE id = ?", LocalDateTime.class, id);
    }

    /** 读取指定表总行数。 */
    private long rowCount(String table) {
        Long count = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM " + table, Long.class);
        return count == null ? 0L : count;
    }

    /** 构造赠送成功微信结果。 */
    private WechatVirtualPaymentResult giftSuccessResult() {
        return new WechatVirtualPaymentResult(
                0, null, WechatVirtualPaymentErrorType.SUCCESS,
                125L, 25L, 0L, null, 0L, 0L, 0);
    }

    /** 构造扣币成功微信结果。 */
    private WechatVirtualPaymentResult debitSuccessResult() {
        return new WechatVirtualPaymentResult(
                0, null, WechatVirtualPaymentErrorType.SUCCESS,
                450L, 50L, 0L, null, 0L, 0L, 0);
    }

    /** 插入赠送、扣币及账户最小完整业务数据。 */
    private void insertFixtures() {
        jdbcTemplate.update("""
                INSERT INTO wf_point_account (
                  id, user_id, balance, wechat_balance, wechat_present_balance,
                  pending_debit, total_recharged, total_gifted, total_consumed,
                  version, created_at, updated_at, deleted
                ) VALUES
                  (41, 7, 100, 100, 0, 0, 100, 0, 0, 0,
                   CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, 0),
                  (42, 8, 450, 500, 0, 50, 500, 0, 50, 0,
                   CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, 0)
                """);
        jdbcTemplate.update("""
                INSERT INTO wf_point_gift_order (
                  id, order_no, account_id, user_id, scene_code, amount,
                  business_type, business_id, business_snapshot, idempotency_key,
                  status, retry_count, next_execute_at, version,
                  created_at, updated_at, deleted
                ) VALUES (
                  17, 'WFG17', 41, 7, 'REGISTER_GIFT', 25,
                  'USER', '7', '{}', 'gift-17',
                  'READY', 0, DATEADD('SECOND', -1, CURRENT_TIMESTAMP), 0,
                  CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, 0
                )
                """);
        jdbcTemplate.update("""
                INSERT INTO wf_point_debit_task (
                  id, task_no, account_id, user_id, status, active_flag,
                  request_amount, settled_amount, used_present_amount, retry_count,
                  next_execute_at, version, created_at, updated_at, deleted
                ) VALUES (
                  23, 'WFD23', 42, 8, 'WAITING', 1,
                  50, 0, 0, 0,
                  DATEADD('SECOND', -1, CURRENT_TIMESTAMP), 0,
                  CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, 0
                )
                """);
        jdbcTemplate.update("""
                INSERT INTO wf_point_pending_debit (
                  id, account_id, user_id, point_transaction_id,
                  original_amount, remaining_amount, version,
                  created_at, updated_at, deleted
                ) VALUES (
                  51, 42, 8, 61, 50, 50, 0,
                  CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, 0
                )
                """);
    }

    /** 创建生产实体和 Mapper 所需的 H2 表结构。 */
    private void createTables() {
        tearDown();
        jdbcTemplate.execute("""
                CREATE TABLE wf_point_account (
                  id BIGINT PRIMARY KEY, user_id BIGINT NOT NULL, balance BIGINT NOT NULL,
                  wechat_balance BIGINT NOT NULL, wechat_present_balance BIGINT NOT NULL,
                  pending_debit BIGINT NOT NULL, wechat_balance_synced_at TIMESTAMP NULL,
                  total_recharged BIGINT NOT NULL, total_gifted BIGINT NOT NULL,
                  total_consumed BIGINT NOT NULL, created_at TIMESTAMP NOT NULL,
                  updated_at TIMESTAMP NOT NULL, deleted BIGINT NOT NULL, version INT NOT NULL
                )
                """);
        jdbcTemplate.execute("""
                CREATE TABLE wf_point_gift_order (
                  id BIGINT PRIMARY KEY, order_no VARCHAR(32) NOT NULL,
                  account_id BIGINT NOT NULL, user_id BIGINT NOT NULL,
                  scene_code VARCHAR(64) NOT NULL, amount BIGINT NOT NULL,
                  business_type VARCHAR(32) NOT NULL, business_id VARCHAR(128) NOT NULL,
                  business_snapshot VARCHAR(1000) NOT NULL, idempotency_key VARCHAR(64) NOT NULL,
                  status VARCHAR(32) NOT NULL, retry_count INT NOT NULL,
                  next_execute_at TIMESTAMP NOT NULL, lease_owner VARCHAR(64) NULL,
                  execution_lease_token CHAR(32) NULL, lease_until TIMESTAMP NULL,
                  point_transaction_id BIGINT NULL, wechat_balance_after BIGINT NULL,
                  wechat_present_balance_after BIGINT NULL, last_error_code VARCHAR(32) NULL,
                  last_error_message VARCHAR(255) NULL, last_failed_at TIMESTAMP NULL,
                  completed_at TIMESTAMP NULL, created_at TIMESTAMP NOT NULL,
                  updated_at TIMESTAMP NOT NULL, deleted BIGINT NOT NULL, version INT NOT NULL
                )
                """);
        jdbcTemplate.execute("""
                CREATE TABLE wf_point_debit_task (
                  id BIGINT PRIMARY KEY, task_no VARCHAR(32) NOT NULL,
                  account_id BIGINT NOT NULL, user_id BIGINT NOT NULL,
                  status VARCHAR(32) NOT NULL, active_flag INT NULL,
                  request_amount BIGINT NULL, settled_amount BIGINT NOT NULL,
                  used_present_amount BIGINT NOT NULL, pending_before BIGINT NULL,
                  pending_after BIGINT NULL, retry_count INT NOT NULL,
                  next_execute_at TIMESTAMP NOT NULL, lease_owner VARCHAR(64) NULL,
                  execution_lease_token CHAR(32) NULL, lease_until TIMESTAMP NULL,
                  session_version BIGINT NULL, wechat_balance_before BIGINT NULL,
                  wechat_balance_after BIGINT NULL, last_error_code VARCHAR(32) NULL,
                  last_error_message VARCHAR(255) NULL, last_failed_at TIMESTAMP NULL,
                  started_at TIMESTAMP NULL, completed_at TIMESTAMP NULL,
                  created_at TIMESTAMP NOT NULL, updated_at TIMESTAMP NOT NULL,
                  deleted BIGINT NOT NULL, version INT NOT NULL
                )
                """);
        jdbcTemplate.execute("""
                CREATE TABLE wf_point_pending_debit (
                  id BIGINT PRIMARY KEY, account_id BIGINT NOT NULL, user_id BIGINT NOT NULL,
                  point_transaction_id BIGINT NOT NULL, original_amount BIGINT NOT NULL,
                  remaining_amount BIGINT NOT NULL, last_settled_at TIMESTAMP NULL,
                  created_at TIMESTAMP NOT NULL, updated_at TIMESTAMP NOT NULL,
                  deleted BIGINT NOT NULL, version INT NOT NULL
                )
                """);
        jdbcTemplate.execute("""
                CREATE TABLE wf_point_transaction (
                  id BIGINT GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY,
                  account_id BIGINT NOT NULL, user_id BIGINT NOT NULL, rule_id BIGINT NULL,
                  transaction_type VARCHAR(32) NOT NULL, scene_code VARCHAR(64) NOT NULL,
                  points_change BIGINT NOT NULL, balance_before BIGINT NOT NULL,
                  balance_after BIGINT NOT NULL, business_type VARCHAR(32) NOT NULL,
                  business_id VARCHAR(128) NOT NULL, calculation_snapshot VARCHAR(1000) NOT NULL,
                  idempotency_key VARCHAR(64) NOT NULL, remark VARCHAR(255) NULL,
                  occurred_at TIMESTAMP NOT NULL, created_at TIMESTAMP NOT NULL,
                  updated_at TIMESTAMP NOT NULL, deleted BIGINT NOT NULL DEFAULT 0,
                  version INT NOT NULL DEFAULT 0
                )
                """);
    }
}
