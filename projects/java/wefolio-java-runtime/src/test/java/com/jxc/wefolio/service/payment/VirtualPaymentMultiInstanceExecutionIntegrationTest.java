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
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 微信虚拟支付共享数据库多实例领取集成测试。
 */
@ActiveProfiles("test")
@SpringBootTest
class VirtualPaymentMultiInstanceExecutionIntegrationTest {

    private static final String TOKEN_A = "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA";
    private static final String TOKEN_B = "BBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBB";

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private PointGiftOrderEntityMapper giftOrderMapper;

    @Autowired
    private PointDebitTaskEntityMapper debitTaskMapper;

    @BeforeEach
    void setUp() {
        createTables();
        LocalDateTime dueAt = LocalDateTime.now().minusMinutes(1);
        jdbcTemplate.update("""
                INSERT INTO wf_point_gift_order (
                  id, status, next_execute_at, lease_owner, execution_lease_token,
                  lease_until, version, created_at, updated_at, deleted
                ) VALUES (17, 'READY', ?, NULL, NULL, NULL, 0,
                          CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, 0)
                """, dueAt);
        jdbcTemplate.update("""
                INSERT INTO wf_point_debit_task (
                  id, status, active_flag, next_execute_at, lease_owner,
                  execution_lease_token, lease_until, started_at, version,
                  created_at, updated_at, deleted
                ) VALUES (23, 'WAITING', 1, ?, NULL, NULL, NULL, NULL, 0,
                          CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, 0)
                """, dueAt);
    }

    @AfterEach
    void tearDown() {
        jdbcTemplate.execute("DROP TABLE IF EXISTS wf_point_debit_task");
        jdbcTemplate.execute("DROP TABLE IF EXISTS wf_point_gift_order");
    }

    /** 两个 runtime 同时领取同一赠送订单时必须只有一个成功。 */
    @Test
    void concurrentGiftClaimsShouldHaveSingleWinner() throws Exception {
        LocalDateTime now = giftOrderMapper.selectCurrentTimestamp();

        List<Integer> results = concurrentClaims(
                () -> giftOrderMapper.tryClaim(17L, TOKEN_A, now.plusMinutes(1), now),
                () -> giftOrderMapper.tryClaim(17L, TOKEN_B, now.plusMinutes(1), now));

        assertThat(results).containsExactlyInAnyOrder(0, 1);
        assertExecutionTokenMatchesWinner("wf_point_gift_order", 17L);
    }

    /** 两个 runtime 同时领取同一扣币任务时必须只有一个成功。 */
    @Test
    void concurrentDebitClaimsShouldHaveSingleWinner() throws Exception {
        LocalDateTime now = debitTaskMapper.selectCurrentTimestamp();

        List<Integer> results = concurrentClaims(
                () -> debitTaskMapper.tryClaim(23L, TOKEN_A, now.plusMinutes(1), now),
                () -> debitTaskMapper.tryClaim(23L, TOKEN_B, now.plusMinutes(1), now));

        assertThat(results).containsExactlyInAnyOrder(0, 1);
        assertExecutionTokenMatchesWinner("wf_point_debit_task", 23L);
    }

    /** 租约过期被新 runtime 接管后，旧令牌不能续租或恢复执行权。 */
    @Test
    void expiredGiftLeaseShouldRejectOldTokenAfterTakeover() {
        LocalDateTime firstNow = giftOrderMapper.selectCurrentTimestamp();
        assertThat(giftOrderMapper.tryClaim(
                17L, TOKEN_A, firstNow.plusMinutes(1), firstNow)).isEqualTo(1);
        jdbcTemplate.update("""
                UPDATE wf_point_gift_order
                   SET lease_until = DATEADD('SECOND', -1, CURRENT_TIMESTAMP)
                 WHERE id = 17
                """);
        LocalDateTime secondNow = giftOrderMapper.selectCurrentTimestamp();
        assertThat(giftOrderMapper.tryClaim(
                17L, TOKEN_B, secondNow.plusMinutes(1), secondNow)).isEqualTo(1);

        assertThat(giftOrderMapper.renewLease(
                17L, TOKEN_A, secondNow.plusMinutes(2))).isZero();
        assertThat(giftOrderMapper.renewLease(
                17L, TOKEN_B, secondNow.plusMinutes(2))).isEqualTo(1);
        assertExecutionTokenMatchesWinner("wf_point_gift_order", 17L);
    }

    /** 并发执行两个领取动作并收集结果。 */
    private List<Integer> concurrentClaims(ClaimAction first, ClaimAction second) throws Exception {
        ExecutorService executor = Executors.newFixedThreadPool(2);
        CountDownLatch start = new CountDownLatch(1);
        try {
            Future<Integer> firstResult = executor.submit(() -> {
                start.await();
                return first.claim();
            });
            Future<Integer> secondResult = executor.submit(() -> {
                start.await();
                return second.claim();
            });
            start.countDown();
            return List.of(firstResult.get(), secondResult.get());
        } finally {
            executor.shutdownNow();
        }
    }

    /** 断言 Release B 只写新字段，旧字段在收缩迁移前保持为空。 */
    private void assertExecutionTokenMatchesWinner(String table, long id) {
        String leaseOwner = jdbcTemplate.queryForObject(
                "SELECT lease_owner FROM " + table + " WHERE id = ?", String.class, id);
        String executionLeaseToken = jdbcTemplate.queryForObject(
                "SELECT execution_lease_token FROM " + table + " WHERE id = ?", String.class, id);
        assertThat(leaseOwner).isNull();
        assertThat(executionLeaseToken).isIn(TOKEN_A, TOKEN_B);
    }

    /** 创建生产 Mapper SQL 所需的最小表结构。 */
    private void createTables() {
        jdbcTemplate.execute("DROP TABLE IF EXISTS wf_point_debit_task");
        jdbcTemplate.execute("DROP TABLE IF EXISTS wf_point_gift_order");
        jdbcTemplate.execute("""
                CREATE TABLE wf_point_gift_order (
                  id BIGINT PRIMARY KEY,
                  status VARCHAR(32) NOT NULL,
                  next_execute_at TIMESTAMP NOT NULL,
                  lease_owner VARCHAR(64) NULL,
                  execution_lease_token CHAR(32) NULL,
                  lease_until TIMESTAMP NULL,
                  version INT NOT NULL,
                  created_at TIMESTAMP NOT NULL,
                  updated_at TIMESTAMP NOT NULL,
                  deleted TINYINT NOT NULL
                )
                """);
        jdbcTemplate.execute("""
                CREATE TABLE wf_point_debit_task (
                  id BIGINT PRIMARY KEY,
                  status VARCHAR(32) NOT NULL,
                  active_flag TINYINT NULL,
                  next_execute_at TIMESTAMP NOT NULL,
                  lease_owner VARCHAR(64) NULL,
                  execution_lease_token CHAR(32) NULL,
                  lease_until TIMESTAMP NULL,
                  started_at TIMESTAMP NULL,
                  version INT NOT NULL,
                  created_at TIMESTAMP NOT NULL,
                  updated_at TIMESTAMP NOT NULL,
                  deleted TINYINT NOT NULL
                )
                """);
    }

    /** 可并发执行的领取动作。 */
    @FunctionalInterface
    private interface ClaimAction {
        int claim();
    }
}
