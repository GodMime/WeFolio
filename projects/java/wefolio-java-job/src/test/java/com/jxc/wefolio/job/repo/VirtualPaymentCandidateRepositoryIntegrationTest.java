package com.jxc.wefolio.job.repo;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.time.LocalDateTime;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 微信虚拟支付候选任务只读仓储集成测试。
 */
class VirtualPaymentCandidateRepositoryIntegrationTest {

    private JdbcTemplate jdbcTemplate;
    private VirtualPaymentCandidateRepository repository;

    @BeforeEach
    void setUp() {
        String databaseName = "virtual_payment_candidates_"
                + UUID.randomUUID().toString().replace("-", "");
        DriverManagerDataSource dataSource = new DriverManagerDataSource(
                "jdbc:h2:mem:" + databaseName + ";MODE=MySQL;DB_CLOSE_DELAY=-1", "sa", "");
        jdbcTemplate = new JdbcTemplate(dataSource);
        repository = new VirtualPaymentCandidateRepository(jdbcTemplate);
        createTables();
    }

    /** 赠送候选只包含已到期且无有效租约的待处理订单，并稳定排序和限量。 */
    @Test
    void findDueGiftOrderIdsShouldRespectStateTimeLeaseAndLimit() {
        LocalDateTime now = LocalDateTime.now();
        insertGift(1L, "READY", now.minusMinutes(3), null, 0);
        insertGift(2L, "RETRY_WAIT", now.minusMinutes(2), now.minusMinutes(1), 0);
        insertGift(3L, "READY", now.plusMinutes(1), null, 0);
        insertGift(4L, "FAILED", now.minusMinutes(4), null, 0);
        insertGift(5L, "READY", now.minusMinutes(5), now.plusMinutes(1), 0);
        insertGift(6L, "READY", now.minusMinutes(6), null, 1);

        assertThat(repository.findDueGiftOrderIds(1)).containsExactly(1L);
        assertThat(repository.findDueGiftOrderIds(10)).containsExactly(1L, 2L);
    }

    /** 扣币候选包含到期待处理任务和租约过期的运行任务。 */
    @Test
    void findDueDebitTaskIdsShouldIncludeDueAndExpiredRunningTasks() {
        LocalDateTime now = LocalDateTime.now();
        insertDebit(11L, 7L, "WAITING", now.minusMinutes(3), null, 1, 0);
        insertDebit(12L, 8L, "RETRY_WAIT", now.minusMinutes(2), null, 1, 0);
        insertDebit(13L, 9L, "RUNNING", now.minusMinutes(4), now.minusMinutes(1), 1, 0);
        insertDebit(14L, 10L, "RUNNING", now.minusMinutes(5), now.plusMinutes(1), 1, 0);
        insertDebit(15L, 11L, "WAITING", now.plusMinutes(1), null, 1, 0);
        insertDebit(16L, 12L, "WAITING", now.minusMinutes(6), null, null, 0);

        assertThat(repository.findDueDebitTaskIds(2)).containsExactly(13L, 11L);
        assertThat(repository.findDueDebitTaskIds(10)).containsExactly(13L, 11L, 12L);
    }

    /** 仅返回仍有待扣且缺少活动任务的用户。 */
    @Test
    void findUsersMissingActiveDebitTasksShouldExcludeExistingActiveTasks() {
        insertAccount(21L, 7L, 100L, 0);
        insertAccount(22L, 8L, 50L, 0);
        insertAccount(23L, 9L, 0L, 0);
        insertAccount(24L, 10L, 80L, 1);
        insertDebit(31L, 8L, "WAITING", LocalDateTime.now(), null, 1, 0);

        assertThat(repository.findUsersMissingActiveDebitTasks(10)).containsExactly(7L);
    }

    /** 创建查询所需的最小共享表结构。 */
    private void createTables() {
        jdbcTemplate.execute("""
                CREATE TABLE wf_point_gift_order (
                  id BIGINT PRIMARY KEY,
                  status VARCHAR(32) NOT NULL,
                  next_execute_at TIMESTAMP NOT NULL,
                  lease_until TIMESTAMP NULL,
                  deleted TINYINT NOT NULL
                )
                """);
        jdbcTemplate.execute("""
                CREATE TABLE wf_point_debit_task (
                  id BIGINT PRIMARY KEY,
                  user_id BIGINT NOT NULL,
                  status VARCHAR(32) NOT NULL,
                  next_execute_at TIMESTAMP NOT NULL,
                  lease_until TIMESTAMP NULL,
                  active_flag TINYINT NULL,
                  deleted TINYINT NOT NULL
                )
                """);
        jdbcTemplate.execute("""
                CREATE TABLE wf_point_account (
                  id BIGINT PRIMARY KEY,
                  user_id BIGINT NOT NULL,
                  pending_debit BIGINT NOT NULL,
                  deleted TINYINT NOT NULL
                )
                """);
    }

    /** 插入赠送订单。 */
    private void insertGift(
            long id,
            String status,
            LocalDateTime nextExecuteAt,
            LocalDateTime leaseUntil,
            int deleted
    ) {
        jdbcTemplate.update("""
                INSERT INTO wf_point_gift_order
                    (id, status, next_execute_at, lease_until, deleted)
                VALUES (?, ?, ?, ?, ?)
                """, id, status, nextExecuteAt, leaseUntil, deleted);
    }

    /** 插入扣币任务。 */
    private void insertDebit(
            long id,
            long userId,
            String status,
            LocalDateTime nextExecuteAt,
            LocalDateTime leaseUntil,
            Integer activeFlag,
            int deleted
    ) {
        jdbcTemplate.update("""
                INSERT INTO wf_point_debit_task
                    (id, user_id, status, next_execute_at, lease_until, active_flag, deleted)
                VALUES (?, ?, ?, ?, ?, ?, ?)
                """, id, userId, status, nextExecuteAt, leaseUntil, activeFlag, deleted);
    }

    /** 插入积分账户。 */
    private void insertAccount(long id, long userId, long pendingDebit, int deleted) {
        jdbcTemplate.update("""
                INSERT INTO wf_point_account (id, user_id, pending_debit, deleted)
                VALUES (?, ?, ?, ?)
                """, id, userId, pendingDebit, deleted);
    }
}
