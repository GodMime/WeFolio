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
                  id, user_id, balance, total_recharged, total_gifted,
                  total_consumed, version, created_at, updated_at, deleted
                ) VALUES (10, 7, 100, 0, 0, 0, 0, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, 0)
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
        assertThat(longValue("total_recharged")).isEqualTo(520L);
        assertThat(longValue("total_consumed")).isEqualTo(20L);
        assertThat(longValue("version")).isEqualTo(2L);
    }

    private long longValue(String column) {
        Long value = jdbcTemplate.queryForObject(
                "SELECT " + column + " FROM wf_point_account WHERE id = 10", Long.class);
        return value == null ? 0L : value;
    }
}
