package com.jxc.wefolio.job.service;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.concurrent.ThreadLocalRandom;

import static org.assertj.core.api.Assertions.*;

/** 显式隔离 MySQL 的跨连接命名锁验收；不建表、不读业务行、不修改持久数据。 */
@EnabledIfEnvironmentVariable(named = "WEFOLIO_REFERENCE_TEST_MYSQL_URL", matches = ".+")
class PortfolioAiReferenceOwnerLockMysqlTest {
    /** 仅使用专门注入的测试地址与凭据，不读取运行环境 .env。 */
    private TrackingDataSource source() {
        return new TrackingDataSource(System.getenv("WEFOLIO_REFERENCE_TEST_MYSQL_URL"),
                System.getenv().getOrDefault("WEFOLIO_REFERENCE_TEST_MYSQL_USER", "root"),
                System.getenv().getOrDefault("WEFOLIO_REFERENCE_TEST_MYSQL_PASSWORD", ""));
    }
    /** 远离常见业务 ID，仍使用完全相同的 owner 锁协议。 */
    private long ownerId() { return Long.MAX_VALUE - ThreadLocalRandom.current().nextLong(1, 1_000_000); }
    /** 单一有界标量查询，不执行任何表 DDL/DML。 */
    private Long query(Connection connection, String sql, String name) throws SQLException {
        try (var statement = connection.prepareStatement(sql)) {
            statement.setQueryTimeout(5);
            if (name != null) { statement.setString(1, name); }
            try (var result = statement.executeQuery()) { result.next(); long value = result.getLong(1); return result.wasNull() ? null : value; }
        }
    }

    @Test void runtimeCompatibleSqlLockExcludesJobAcrossCommitAndReleaseAllowsClaim() throws Exception {
        var data = source(); var locks = new PortfolioAiReferenceOwnerLock(data); long id = ownerId();
        String name = "pf-ai-ref:USER:" + id;
        try (Connection competitor = data.getConnection()) {
            assertThat(query(competitor, "SELECT GET_LOCK(?, 0)", name)).isEqualTo(1L);
            competitor.setAutoCommit(false); competitor.commit();
            assertThat(locks.tryAcquire("USER", id)).isEmpty();
            assertThat(data.last.isClosed()).isTrue();
            competitor.rollback(); assertThat(locks.tryAcquire("USER", id)).isEmpty();
            assertThat(query(competitor, "SELECT RELEASE_LOCK(?)", name)).isEqualTo(1L);
            try (var held = locks.tryAcquire("USER", id).orElseThrow()) {
                held.checkHeld();
                assertThat(query(competitor, "SELECT GET_LOCK(?, 0)", name)).isEqualTo(0L);
            }
            assertThat(data.last.isClosed()).isTrue();
            assertThat(query(competitor, "SELECT GET_LOCK(?, 0)", name)).isEqualTo(1L);
            query(competitor, "SELECT RELEASE_LOCK(?)", name);
        }
    }

    @Test void applicationFailureReturnsConnectionAndReleasesTeamLock() throws Exception {
        var data = source(); var locks = new PortfolioAiReferenceOwnerLock(data); long id = ownerId();
        String name = "pf-ai-ref:TEAM:" + id;
        assertThatThrownBy(() -> {
            try (var held = locks.tryAcquire("TEAM", id).orElseThrow()) { held.checkHeld(); throw new IllegalArgumentException("模拟业务失败"); }
        }).isInstanceOf(IllegalArgumentException.class);
        assertThat(data.last.isClosed()).isTrue();
        try (Connection competitor = data.getConnection()) {
            assertThat(query(competitor, "SELECT GET_LOCK(?, 0)", name)).isEqualTo(1L);
            query(competitor, "SELECT RELEASE_LOCK(?)", name);
        }
    }

    @Test void killedDedicatedSessionCannotContinueAndIndependentConnectionCanTakeOver() throws Exception {
        var data = source(); var locks = new PortfolioAiReferenceOwnerLock(data); long id = ownerId();
        String name = "pf-ai-ref:USER:" + id;
        var held = locks.tryAcquire("USER", id).orElseThrow();
        Connection original = data.last;
        long sessionId = query(original, "SELECT CONNECTION_ID()", null);
        try (Connection competitor = data.getConnection()) {
            // 只关闭本测试刚创建的专用会话，连接主键来自该会话自身。
            try (var statement = competitor.createStatement()) { statement.setQueryTimeout(5); statement.execute("KILL CONNECTION " + sessionId); }
            assertThatThrownBy(held::checkHeld).isInstanceOf(IllegalStateException.class);
            assertThat(query(competitor, "SELECT GET_LOCK(?, 0)", name)).isEqualTo(1L);
            query(competitor, "SELECT RELEASE_LOCK(?)", name);
        } finally { held.close(); }
        assertThat(original.isClosed()).isTrue();
    }

    /** 只观察本测试借出的专用会话是否被正确归还。 */
    private static final class TrackingDataSource extends DriverManagerDataSource {
        /** 最近借出的真实 JDBC 会话。 */
        private Connection last;
        private TrackingDataSource(String url, String user, String password) { super(url, user, password); }
        @Override public Connection getConnection() throws SQLException { last = super.getConnection(); return last; }
    }
}
