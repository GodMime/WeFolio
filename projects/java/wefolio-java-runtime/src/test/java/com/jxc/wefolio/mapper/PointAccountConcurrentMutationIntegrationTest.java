package com.jxc.wefolio.mapper;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 积分账户充值与消费并发集成测试。
 */
@ActiveProfiles("test")
@SpringBootTest
class PointAccountConcurrentMutationIntegrationTest {

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private PointAccountEntityMapper pointAccountEntityMapper;

    @BeforeEach
    void setUp() {
        jdbcTemplate.execute("DROP TABLE IF EXISTS wf_point_account");
        jdbcTemplate.execute("""
                CREATE TABLE wf_point_account (
                  id BIGINT AUTO_INCREMENT PRIMARY KEY,
                  user_id BIGINT NOT NULL,
                  balance BIGINT NOT NULL,
                  wechat_balance BIGINT NOT NULL,
                  wechat_present_balance BIGINT NOT NULL,
                  pending_debit BIGINT NOT NULL,
                  wechat_balance_synced_at TIMESTAMP NULL,
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
        jdbcTemplate.update("""
                INSERT INTO wf_point_account (
                  id, user_id, balance, wechat_balance, wechat_present_balance,
                  pending_debit, wechat_balance_synced_at, total_recharged, total_gifted,
                  total_consumed, version, created_at, updated_at, deleted
                ) VALUES (10, 7, 100, 100, 0, 0, NULL, 0, 0, 0, 0, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, 0)
                """);
    }

    @AfterEach
    void tearDown() {
        jdbcTemplate.execute("DROP TABLE IF EXISTS wf_point_account");
    }

    @Test
    void concurrentRechargeAndConsumptionShouldNotLoseAccountUpdates() throws Exception {
        ExecutorService executor = Executors.newFixedThreadPool(2);
        CountDownLatch start = new CountDownLatch(1);
        try {
            Future<Integer> recharge = executor.submit(() -> {
                start.await();
                return pointAccountEntityMapper.addRechargedPoints(10L, 7L, 520L);
            });
            Future<Integer> consumption = executor.submit(() -> {
                start.await();
                return pointAccountEntityMapper.deductConsumedPoints(10L, 7L, 20L);
            });
            start.countDown();

            assertThat(recharge.get()).isEqualTo(1);
            assertThat(consumption.get()).isEqualTo(1);
        } finally {
            executor.shutdownNow();
        }

        assertThat(longValue("balance")).isEqualTo(600L);
        assertThat(longValue("wechat_balance")).isEqualTo(620L);
        assertThat(longValue("pending_debit")).isEqualTo(20L);
        assertThat(longValue("total_recharged")).isEqualTo(520L);
        assertThat(longValue("total_consumed")).isEqualTo(20L);
        assertThat(longValue("version")).isEqualTo(2L);
    }

    /** 余额同步已包含充值时，迟到结算只计充值总额，不再把同笔充值加到余额。 */
    @Test
    void delayedRechargeShouldKeepAuthoritativeBalanceAndPendingDebit() {
        pointAccountEntityMapper.deductForVisitor(10L, 7L, 20L);
        pointAccountEntityMapper.syncWechatBalance(10L, 600L, 50L);

        assertThat(pointAccountEntityMapper.applyRechargeWechatBalance(10L, 7L, 500L, 600L, 50L))
                .isEqualTo(1);

        assertThat(longValue("balance")).isEqualTo(580L);
        assertThat(longValue("wechat_balance")).isEqualTo(600L);
        assertThat(longValue("wechat_present_balance")).isEqualTo(50L);
        assertThat(longValue("total_recharged")).isEqualTo(500L);
        assertThat(longValue("pending_debit")).isEqualTo(20L);
    }

    /** 首次 NULL 快照不等于退款；退款必须同时属于当前账户和用户。 */
    @Test
    void staleRefundLookupShouldRequireMatchingExistingRefund() {
        jdbcTemplate.execute("DROP TABLE IF EXISTS wf_recharge_order");
        jdbcTemplate.execute("""
                CREATE TABLE wf_recharge_order (
                  id BIGINT PRIMARY KEY, account_id BIGINT, user_id BIGINT,
                  status VARCHAR(32), deleted BIGINT
                )
                """);
        try {
            assertThat(pointAccountEntityMapper.selectRefundStaleAccount(7L)).isNull();
            jdbcTemplate.update("INSERT INTO wf_recharge_order VALUES (1, 99, 7, 'REFUNDED', 0)");
            assertThat(pointAccountEntityMapper.selectRefundStaleAccount(7L)).isNull();
            jdbcTemplate.update("UPDATE wf_recharge_order SET account_id = 10");
            assertThat(pointAccountEntityMapper.selectRefundStaleAccount(7L)).isNotNull();
            pointAccountEntityMapper.syncWechatBalance(10L, 0L, 0L);
            assertThat(pointAccountEntityMapper.selectRefundStaleAccount(7L)).isNull();
        } finally {
            jdbcTemplate.execute("DROP TABLE wf_recharge_order");
        }
    }

    /** 读取账户指定聚合值。 */
    private long longValue(String column) {
        Long value = jdbcTemplate.queryForObject(
                "SELECT " + column + " FROM wf_point_account WHERE id = 10", Long.class);
        return value == null ? 0L : value;
    }
}
