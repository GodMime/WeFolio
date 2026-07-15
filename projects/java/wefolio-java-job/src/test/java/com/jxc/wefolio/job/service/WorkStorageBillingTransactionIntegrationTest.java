package com.jxc.wefolio.job.service;

import com.jxc.wefolio.job.model.WorkStorageBillingModels.BillingRule;
import com.jxc.wefolio.job.model.WorkStorageBillingModels.BillCompletion;
import com.jxc.wefolio.job.model.WorkStorageBillingModels.SettlementResult;
import com.jxc.wefolio.job.model.WorkStorageBillingModels.UserStorageAggregate;
import com.jxc.wefolio.job.repo.WorkStorageBillingRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;
import org.springframework.transaction.interceptor.TransactionInterceptor;

import javax.sql.DataSource;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 单用户作品存储结算事务集成测试。
 *
 * <p>使用真实数据源事务管理器和 Spring 事务代理，验证账户、账单、流水和消息的原子性。</p>
 */
class WorkStorageBillingTransactionIntegrationTest {

    private static final LocalDate BILLING_MONTH = LocalDate.of(2026, 7, 1);
    private static final Clock FIXED_CLOCK = Clock.fixed(
            Instant.parse("2026-07-31T18:00:00Z"), ZoneId.of("Asia/Shanghai"));
    private static final long MB = 1024L * 1024L;

    private DataSource dataSource;
    private JdbcTemplate jdbcTemplate;

    @BeforeEach
    void setUp() {
        String databaseName = "work_storage_tx_" + UUID.randomUUID().toString().replace("-", "");
        dataSource = new DriverManagerDataSource(
                "jdbc:h2:mem:" + databaseName + ";MODE=MySQL;DB_CLOSE_DELAY=-1", "sa", "");
        jdbcTemplate = new JdbcTemplate(dataSource);
        createSchema();
    }

    @Test
    void successfulSettlementShouldCommitAccountBillTransactionAndMessage() {
        insertAccount(7, 30);
        WorkStorageBillingTransactionService service = transactionalService(
                new WorkStorageBillingRepository(jdbcTemplate));

        SettlementResult result = service.settleUser(rule(), BILLING_MONTH, aggregate());

        assertThat(result.pointsDeducted()).isEqualTo(20);
        assertThat(longValue("SELECT balance FROM wf_point_account WHERE user_id = 7")).isEqualTo(10);
        assertThat(longValue("SELECT total_consumed FROM wf_point_account WHERE user_id = 7")).isEqualTo(20);
        assertThat(longValue("SELECT COUNT(*) FROM wf_work_storage_monthly_bill")).isEqualTo(1);
        assertThat(longValue("SELECT points_deducted FROM wf_work_storage_monthly_bill")).isEqualTo(20);
        assertThat(longValue("SELECT COUNT(*) FROM wf_point_transaction")).isEqualTo(1);
        assertThat(longValue("SELECT points_change FROM wf_point_transaction")).isEqualTo(-20);
        assertThat(longValue("SELECT COUNT(*) FROM wf_system_message")).isEqualTo(1);
    }

    @Test
    void failureAfterMessageShouldRollbackAllFourTables() {
        insertAccount(7, 30);
        WorkStorageBillingTransactionService service = transactionalService(
                new FailingCompleteBillRepository(jdbcTemplate));

        assertThatThrownBy(() -> service.settleUser(rule(), BILLING_MONTH, aggregate()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("模拟账单完成失败");

        assertThat(longValue("SELECT balance FROM wf_point_account WHERE user_id = 7")).isEqualTo(30);
        assertThat(longValue("SELECT total_consumed FROM wf_point_account WHERE user_id = 7")).isZero();
        assertThat(longValue("SELECT COUNT(*) FROM wf_work_storage_monthly_bill")).isZero();
        assertThat(longValue("SELECT COUNT(*) FROM wf_point_transaction")).isZero();
        assertThat(longValue("SELECT COUNT(*) FROM wf_system_message")).isZero();
    }

    @Test
    void deletedBillRetryShouldRollbackNewBillAndBalanceWhenTransactionKeyExists() {
        insertAccount(7, 30);
        WorkStorageBillingTransactionService service = transactionalService(
                new WorkStorageBillingRepository(jdbcTemplate));
        service.settleUser(rule(), BILLING_MONTH, aggregate());
        jdbcTemplate.update("UPDATE wf_work_storage_monthly_bill SET deleted = id WHERE deleted = 0");

        assertThatThrownBy(() -> service.settleUser(rule(), BILLING_MONTH, aggregate()))
                .isInstanceOf(DuplicateKeyException.class);

        assertThat(longValue("SELECT balance FROM wf_point_account WHERE user_id = 7")).isEqualTo(10);
        assertThat(longValue("SELECT COUNT(*) FROM wf_work_storage_monthly_bill")).isEqualTo(1);
        assertThat(longValue("SELECT COUNT(*) FROM wf_work_storage_monthly_bill WHERE deleted = 0")).isZero();
        assertThat(longValue("SELECT COUNT(*) FROM wf_point_transaction")).isEqualTo(1);
        assertThat(longValue("SELECT COUNT(*) FROM wf_system_message")).isEqualTo(1);
    }

    @Test
    void concurrentSettlementWithUserMonthLockShouldDeductOnlyOnce() throws Exception {
        insertAccount(7, 100);
        WorkStorageBillingTransactionService service = transactionalService(
                new WorkStorageBillingRepository(jdbcTemplate));
        WorkStorageBillingUserLockService lockService = new WorkStorageBillingUserLockService(service);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        CountDownLatch start = new CountDownLatch(1);
        try {
            Future<SettlementResult> first = executor.submit(() -> {
                start.await();
                return lockService.settleWithLock(rule(), BILLING_MONTH, aggregate());
            });
            Future<SettlementResult> second = executor.submit(() -> {
                start.await();
                return lockService.settleWithLock(rule(), BILLING_MONTH, aggregate());
            });
            start.countDown();

            List<SettlementResult> results = List.of(first.get(), second.get());

            assertThat(results).extracting(SettlementResult::skipped)
                    .containsExactlyInAnyOrder(false, true);
            assertThat(longValue("SELECT balance FROM wf_point_account WHERE user_id = 7")).isEqualTo(80);
            assertThat(longValue("SELECT COUNT(*) FROM wf_work_storage_monthly_bill")).isEqualTo(1);
            assertThat(longValue("SELECT COUNT(*) FROM wf_point_transaction")).isEqualTo(1);
        } finally {
            executor.shutdownNow();
        }
    }

    private WorkStorageBillingTransactionService transactionalService(
            WorkStorageBillingRepository repository
    ) {
        WorkStorageBillingTransactionService target = new WorkStorageBillingTransactionService(
                repository, new WorkStorageBillingCalculator(), FIXED_CLOCK);
        DataSourceTransactionManager transactionManager = new DataSourceTransactionManager(dataSource);
        TransactionInterceptor interceptor = new TransactionInterceptor(
                transactionManager, new AnnotationTransactionAttributeSource());
        ProxyFactory proxyFactory = new ProxyFactory(target);
        proxyFactory.addAdvice(interceptor);
        return (WorkStorageBillingTransactionService) proxyFactory.getProxy();
    }

    private BillingRule rule() {
        return new BillingRule(32L, "MONTHLY_WORK_STORAGE", 1, 10, 1);
    }

    private UserStorageAggregate aggregate() {
        return new UserStorageAggregate(7, 15, 205L * MB);
    }

    private long longValue(String sql) {
        Long value = jdbcTemplate.queryForObject(sql, Long.class);
        return value == null ? 0 : value;
    }

    private void insertAccount(long userId, long balance) {
        jdbcTemplate.update("""
                INSERT INTO wf_point_account (
                  user_id, balance, total_recharged, total_gifted, total_consumed,
                  version, created_at, updated_at, deleted
                ) VALUES (?, ?, 0, 0, 0, 0, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, 0)
                """, userId, balance);
    }

    private void createSchema() {
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
                CREATE TABLE wf_system_message (
                  id BIGINT AUTO_INCREMENT PRIMARY KEY,
                  user_id BIGINT NOT NULL,
                  message_type VARCHAR(32) NOT NULL,
                  category VARCHAR(32) NOT NULL,
                  read_status VARCHAR(32) NOT NULL,
                  title VARCHAR(100) NOT NULL,
                  content VARCHAR(1000) NOT NULL,
                  action_type VARCHAR(32) NOT NULL,
                  action_url VARCHAR(500) NOT NULL,
                  biz_type VARCHAR(32) NOT NULL,
                  biz_id BIGINT,
                  idempotency_key VARCHAR(128),
                  created_at TIMESTAMP NOT NULL,
                  updated_at TIMESTAMP NOT NULL,
                  deleted BIGINT NOT NULL,
                  version INT NOT NULL,
                  CONSTRAINT uk_msg_idempotency UNIQUE(idempotency_key, deleted)
                )
                """);
    }

    /** 在消息写入后模拟账单完成异常。 */
    private static final class FailingCompleteBillRepository extends WorkStorageBillingRepository {

        private FailingCompleteBillRepository(JdbcTemplate jdbcTemplate) {
            super(jdbcTemplate);
        }

        @Override
        public void completeBill(BillCompletion completion) {
            throw new IllegalStateException("模拟账单完成失败");
        }
    }
}
