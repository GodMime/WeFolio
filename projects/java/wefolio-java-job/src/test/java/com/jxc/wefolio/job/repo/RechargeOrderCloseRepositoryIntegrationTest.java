package com.jxc.wefolio.job.repo;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.time.LocalDateTime;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 过期充值订单关闭仓储集成测试 — 验证生产更新语句的状态、边界、批量上限和幂等语义。
 *
 * <p>H2 的 MySQL 模式不会按 MySQL 语义执行 UPDATE ORDER BY，排序子句由仓储契约测试验证。</p>
 */
class RechargeOrderCloseRepositoryIntegrationTest {

    private JdbcTemplate jdbcTemplate;
    private RechargeOrderCloseRepository repository;

    @BeforeEach
    void setUp() {
        String databaseName = "recharge_close_" + UUID.randomUUID().toString().replace("-", "");
        DriverManagerDataSource dataSource = new DriverManagerDataSource(
                "jdbc:h2:mem:" + databaseName + ";MODE=MySQL;DB_CLOSE_DELAY=-1", "sa", "");
        jdbcTemplate = new JdbcTemplate(dataSource);
        repository = new RechargeOrderCloseRepository(jdbcTemplate);
        jdbcTemplate.execute("""
                CREATE TABLE wf_recharge_order (
                  id BIGINT AUTO_INCREMENT PRIMARY KEY,
                  status VARCHAR(32) NOT NULL,
                  expire_at TIMESTAMP NOT NULL,
                  closed_at TIMESTAMP NULL,
                  updated_at TIMESTAMP NOT NULL,
                  deleted TINYINT NOT NULL DEFAULT 0,
                  version INT NOT NULL
                )
                """);
    }

    @Test
    void closeShouldRespectExpiryStateLimitAndRemainIdempotent() {
        LocalDateTime now = LocalDateTime.of(2026, 7, 17, 12, 0, 30);
        insert("PENDING_PAYMENT", now.minusMinutes(1));
        insert("PENDING_PAYMENT", now.minusMinutes(2));
        insert("PENDING_PAYMENT", now);
        insert("PAID", now.minusMinutes(3));
        insert("CLOSED", now.minusMinutes(4));

        assertThat(repository.closeExpiredOrders(now, 1)).isEqualTo(1);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM wf_recharge_order WHERE id IN (1, 2) AND status = 'CLOSED'",
                Long.class)).isEqualTo(1L);

        assertThat(repository.closeExpiredOrders(now, 1)).isEqualTo(1);
        assertThat(status(2)).isEqualTo("CLOSED");
        assertThat(status(1)).isEqualTo("CLOSED");
        assertThat(repository.closeExpiredOrders(now, 10)).isZero();

        assertThat(status(3)).isEqualTo("PENDING_PAYMENT");
        assertThat(status(4)).isEqualTo("PAID");
        assertThat(status(5)).isEqualTo("CLOSED");
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM wf_recharge_order WHERE closed_at = ?",
                Long.class, now)).isEqualTo(2L);
    }

    @Test
    void closeShouldIgnoreLogicallyDeletedOrders() {
        LocalDateTime now = LocalDateTime.of(2026, 7, 17, 12, 0, 30);
        insert("PENDING_PAYMENT", now.minusMinutes(2), 1);
        insert("PENDING_PAYMENT", now.minusMinutes(1));

        assertThat(repository.closeExpiredOrders(now, 10)).isEqualTo(1);
        assertThat(status(1)).isEqualTo("PENDING_PAYMENT");
        assertThat(status(2)).isEqualTo("CLOSED");
    }

    private void insert(String status, LocalDateTime expireAt) {
        insert(status, expireAt, 0);
    }

    private void insert(String status, LocalDateTime expireAt, int deleted) {
        jdbcTemplate.update("""
                INSERT INTO wf_recharge_order (
                  status, expire_at, closed_at, updated_at, deleted, version
                ) VALUES (?, ?, NULL, ?, ?, 0)
                """, status, expireAt, expireAt.minusMinutes(1), deleted);
    }

    private String status(long id) {
        return jdbcTemplate.queryForObject(
                "SELECT status FROM wf_recharge_order WHERE id = ?", String.class, id);
    }
}
