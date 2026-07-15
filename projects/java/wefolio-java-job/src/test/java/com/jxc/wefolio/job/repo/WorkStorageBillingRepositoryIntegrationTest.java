package com.jxc.wefolio.job.repo;

import com.jxc.wefolio.job.model.WorkStorageBillingModels.BillingCalculation;
import com.jxc.wefolio.job.model.WorkStorageBillingModels.PointTransactionWrite;
import com.jxc.wefolio.job.model.WorkStorageBillingModels.UserStorageAggregate;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 作品存储结算仓储集成测试 — 使用 H2 MySQL 模式验证生产查询和唯一键语义。
 */
class WorkStorageBillingRepositoryIntegrationTest {

    private JdbcTemplate jdbcTemplate;
    private WorkStorageBillingRepository repository;

    @BeforeEach
    void setUp() {
        String databaseName = "work_storage_" + UUID.randomUUID().toString().replace("-", "");
        DriverManagerDataSource dataSource = new DriverManagerDataSource(
                "jdbc:h2:mem:" + databaseName + ";MODE=MySQL;DB_CLOSE_DELAY=-1", "sa", "");
        jdbcTemplate = new JdbcTemplate(dataSource);
        repository = new WorkStorageBillingRepository(jdbcTemplate);
        createSchema();
    }

    @Test
    void findActiveRuleShouldRequireStatusAndEffectiveRangeAndUseLatestVersion() {
        insertRule(1, 1, "ACTIVE", "2026-07-01 00:00:00", null);
        insertRule(2, 2, "DISABLED", "2026-07-01 00:00:00", null);
        insertRule(3, 3, "ACTIVE", "2026-08-01 00:00:00", null);

        var rule = repository.findActiveRule(LocalDateTime.of(2026, 7, 31, 23, 59, 59, 999_000_000));

        assertThat(rule.id()).isEqualTo(1L);
        assertThat(rule.ruleVersion()).isEqualTo(1);
        assertThat(rule.unitCount()).isEqualTo(10);
        assertThat(rule.pointsValue()).isEqualTo(1L);
    }

    @Test
    void aggregateShouldFilterUsersAndWorksAndExcludeExistingBill() {
        jdbcTemplate.update("INSERT INTO wf_user(id, status, deleted) VALUES (1, 'ACTIVE', 0), (2, 'ACTIVE', 0), (3, 'DISABLED', 0)");
        jdbcTemplate.update("""
                INSERT INTO wf_work(id, user_id, file_size, deleted) VALUES
                (11, 2, NULL, 0), (12, 2, 10485760, 0), (13, 2, 999, 1), (14, 3, 999, 0)
                """);
        insertBill(1, LocalDate.of(2026, 7, 1), 0);

        List<UserStorageAggregate> result = repository.findUnbilledUserAggregates(
                LocalDate.of(2026, 7, 1), 0, 100);

        assertThat(result).containsExactly(new UserStorageAggregate(2, 2, 10L * 1024 * 1024));
    }

    @Test
    void billClaimShouldReturnNullForDuplicateActiveUserMonth() {
        UserStorageAggregate aggregate = new UserStorageAggregate(7, 1, 10L * 1024 * 1024);
        BillingCalculation calculation = new BillingCalculation(10L * 1024 * 1024, 1, 1, "10.00");
        LocalDateTime now = LocalDateTime.of(2026, 8, 1, 2, 0);

        Long first = repository.tryInsertBill(7, LocalDate.of(2026, 7, 1), aggregate, calculation, now);
        Long duplicate = repository.tryInsertBill(7, LocalDate.of(2026, 7, 1), aggregate, calculation, now);

        assertThat(first).isNotNull();
        assertThat(duplicate).isNull();
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM wf_work_storage_monthly_bill", Long.class)).isEqualTo(1L);
    }

    @Test
    void deletedBillAndTransactionShouldStillRejectReusedTransactionIdempotencyKey() {
        PointTransactionWrite first = transactionWrite("WORK_STORAGE:202607:7");
        long transactionId = repository.insertPointTransaction(first);
        jdbcTemplate.update("UPDATE wf_point_transaction SET deleted = id WHERE id = ?", transactionId);

        assertThatThrownBy(() -> repository.insertPointTransaction(transactionWrite("WORK_STORAGE:202607:7")))
                .isInstanceOf(DuplicateKeyException.class);
    }

    @Test
    void ensurePointAccountShouldOnlyIgnoreDuplicateKey() {
        LocalDateTime now = LocalDateTime.of(2026, 8, 1, 2, 0);

        repository.ensurePointAccount(7, now);
        repository.ensurePointAccount(7, now);

        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM wf_point_account WHERE user_id = 7", Long.class)).isEqualTo(1L);
    }

    @Test
    void insertPointTransactionShouldBindCompleteProductionStatement() {
        long transactionId = repository.insertPointTransaction(transactionWrite("WORK_STORAGE:202607:7"));

        assertThat(transactionId).isPositive();
        assertThat(jdbcTemplate.queryForObject(
                "SELECT points_change FROM wf_point_transaction WHERE id = ?",
                Long.class,
                transactionId)).isEqualTo(-8L);
    }

    private PointTransactionWrite transactionWrite(String idempotencyKey) {
        return new PointTransactionWrite(
                101, 10, 7, 32, -8, 8, 0,
                "{\"billingMonth\":\"2026-07\"}",
                idempotencyKey,
                "2026-07 作品总大小 205.00 MB，应扣 20 积分，积分不足，实际扣除 8 积分",
                LocalDateTime.of(2026, 8, 1, 2, 0)
        );
    }

    private void createSchema() {
        jdbcTemplate.execute("""
                CREATE TABLE wf_point_rule (
                  id BIGINT PRIMARY KEY,
                  rule_code VARCHAR(64) NOT NULL,
                  rule_version INT NOT NULL,
                  scene_code VARCHAR(64) NOT NULL,
                  status VARCHAR(32) NOT NULL,
                  unit_count INT NOT NULL,
                  points_value BIGINT NOT NULL,
                  effective_from TIMESTAMP NOT NULL,
                  effective_to TIMESTAMP NULL,
                  deleted BIGINT NOT NULL,
                  version INT NOT NULL
                )
                """);
        jdbcTemplate.execute("CREATE TABLE wf_user (id BIGINT PRIMARY KEY, status VARCHAR(32), deleted BIGINT)");
        jdbcTemplate.execute("CREATE TABLE wf_work (id BIGINT PRIMARY KEY, user_id BIGINT, file_size BIGINT, deleted BIGINT)");
        jdbcTemplate.execute("""
                CREATE TABLE wf_work_storage_monthly_bill (
                  id BIGINT AUTO_INCREMENT PRIMARY KEY,
                  user_id BIGINT NOT NULL,
                  billing_month DATE NOT NULL,
                  work_count BIGINT NOT NULL,
                  total_file_size_bytes BIGINT NOT NULL,
                  points_due BIGINT NOT NULL,
                  points_deducted BIGINT NOT NULL,
                  points_shortfall BIGINT NOT NULL,
                  balance_before BIGINT NOT NULL,
                  balance_after BIGINT NOT NULL,
                  point_transaction_id BIGINT NULL,
                  billing_status VARCHAR(32) NOT NULL,
                  remark VARCHAR(255),
                  processed_at TIMESTAMP NOT NULL,
                  created_at TIMESTAMP NOT NULL,
                  updated_at TIMESTAMP NOT NULL,
                  deleted BIGINT NOT NULL,
                  version INT NOT NULL,
                  CONSTRAINT uk_work_storage_bill_user_month UNIQUE(user_id, billing_month, deleted)
                )
                """);
        jdbcTemplate.execute("""
                CREATE TABLE wf_point_transaction (
                  id BIGINT AUTO_INCREMENT PRIMARY KEY,
                  account_id BIGINT NOT NULL,
                  user_id BIGINT NOT NULL,
                  rule_id BIGINT,
                  transaction_type VARCHAR(32) NOT NULL,
                  scene_code VARCHAR(64) NOT NULL,
                  points_change BIGINT NOT NULL,
                  balance_before BIGINT NOT NULL,
                  balance_after BIGINT NOT NULL,
                  business_type VARCHAR(32) NOT NULL,
                  business_id VARCHAR(64) NOT NULL,
                  calculation_snapshot JSON NOT NULL,
                  idempotency_key VARCHAR(64) NOT NULL,
                  remark VARCHAR(255),
                  occurred_at TIMESTAMP NOT NULL,
                  created_at TIMESTAMP NOT NULL,
                  updated_at TIMESTAMP NOT NULL,
                  deleted BIGINT NOT NULL,
                  version INT NOT NULL,
                  CONSTRAINT uk_point_tx_idempotency UNIQUE(idempotency_key)
                )
                """);
        jdbcTemplate.execute("""
                CREATE TABLE wf_point_account (
                  id BIGINT AUTO_INCREMENT PRIMARY KEY,
                  user_id BIGINT NOT NULL,
                  balance BIGINT NOT NULL,
                  total_recharged BIGINT NOT NULL,
                  total_gifted BIGINT NOT NULL,
                  total_consumed BIGINT NOT NULL,
                  version INT NOT NULL,
                  created_at TIMESTAMP NOT NULL,
                  updated_at TIMESTAMP NOT NULL,
                  deleted BIGINT NOT NULL,
                  CONSTRAINT uk_point_account_user UNIQUE(user_id)
                )
                """);
    }

    private void insertRule(long id, int version, String status, String from, String to) {
        jdbcTemplate.update("""
                INSERT INTO wf_point_rule (
                  id, rule_code, rule_version, scene_code, status, unit_count,
                  points_value, effective_from, effective_to, deleted, version
                ) VALUES (?, 'MONTHLY_WORK_STORAGE', ?, 'MONTHLY_WORK_STORAGE', ?, 10, 1, ?, ?, 0, 0)
                """, id, version, status, LocalDateTime.parse(from.replace(' ', 'T')),
                to == null ? null : LocalDateTime.parse(to.replace(' ', 'T')));
    }

    private void insertBill(long userId, LocalDate billingMonth, long deleted) {
        jdbcTemplate.update("""
                INSERT INTO wf_work_storage_monthly_bill (
                  user_id, billing_month, work_count, total_file_size_bytes,
                  points_due, points_deducted, points_shortfall,
                  balance_before, balance_after, billing_status, remark,
                  processed_at, created_at, updated_at, deleted, version
                ) VALUES (?, ?, 0, 0, 0, 0, 0, 0, 0, 'NO_CHARGE', '', CURRENT_TIMESTAMP,
                          CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, ?, 0)
                """, userId, billingMonth, deleted);
    }
}
