package com.jxc.wefolio.job.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Optional;

/** 视频音频转写的跨实例单飞锁，专用数据库会话只持有命名锁，不创建业务表或长期事务。 */
@Component
@RequiredArgsConstructor
@Slf4j
public class VideoAudioWorkLock {
    /** 同一 MySQL 服务上的音频转写同一作品只允许一个执行者。 */
    private static final String LOCK_PREFIX = "wefolio:video-audio:";
    /** 准入阶段不等待其他批次结束。 */
    private static final String ACQUIRE_LOCK_SQL = "SELECT GET_LOCK(?, 0)";
    /** 付费提交前确认命名锁仍归当前连接所有。 */
    private static final String CHECK_LOCK_SQL = "SELECT IS_USED_LOCK(?) = CONNECTION_ID()";
    /** 归还连接池前主动释放当前会话的命名锁。 */
    private static final String RELEASE_LOCK_SQL = "SELECT RELEASE_LOCK(?)";
    /** 单次锁操作有界，不随历史作品数量延长数据库查询。 */
    private static final int QUERY_TIMEOUT_SECONDS = 5;
    /** 三种命名锁查询的成功值。 */
    private static final int LOCK_SUCCESS = 1;
    /** 锁不可用时只暴露固定文案，避免 JDBC 异常携带连接凭证。 */
    private static final String UNAVAILABLE_MESSAGE = "视频音频转写并发锁不可用，请检查数据库连接";
    /** 会话失锁后禁止继续提交新的付费任务。 */
    private static final String LOCK_LOST_MESSAGE = "视频音频转写并发锁已失效，禁止继续付费提交";
    /** 为每件作品独立借出一条连接，不参与业务事务。 */
    private final DataSource dataSource;

    /** 付费处理前尝试领取；空结果只表示已有批次持锁，数据库故障明确返回不可用。 */
    public Optional<ExecutionLock> tryAcquire(long workId) {
        Connection connection = null;
        try {
            connection = dataSource.getConnection();
            connection.setAutoCommit(true);
            String lockName = LOCK_PREFIX + workId;
            Integer result = queryLock(connection, ACQUIRE_LOCK_SQL, lockName);
            if (result != null && result == LOCK_SUCCESS) {
                return Optional.of(new ConnectionExecutionLock(connection, lockName));
            }
            if (result != null && result == 0) {
                closeConnection(connection);
                return Optional.empty();
            }
            throw new SQLException("音频转写命名锁领取结果为空或非法");
        } catch (SQLException | RuntimeException exception) {
            log.warn("音频转写命名锁领取失败: exceptionType={}", exception.getClass().getSimpleName());
            if (connection != null) { releaseAndClose(connection, LOCK_PREFIX + workId); }
            throw new IllegalStateException(UNAVAILABLE_MESSAGE);
        }
    }

    /** 在同一专用会话中执行一个有界命名锁查询；SQL NULL 保留为未知结果。 */
    private static Integer queryLock(Connection connection, String sql, String lockName) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setQueryTimeout(QUERY_TIMEOUT_SECONDS);
            statement.setString(1, lockName);
            try (ResultSet results = statement.executeQuery()) {
                if (!results.next()) { throw new SQLException("音频转写命名锁查询未返回结果"); }
                int result = results.getInt(1);
                return results.wasNull() ? null : result;
            }
        }
    }

    /** 释放结果未知时先终止底层会话，防止命名锁随连接归还池中后继续残留。 */
    private static void releaseAndClose(Connection connection, String lockName) {
        boolean releaseKnown = false;
        try {
            Integer result = queryLock(connection, RELEASE_LOCK_SQL, lockName);
            // 0 表示当前会话没有该锁，NULL 表示锁已不存在；两者均可安全归还连接。
            releaseKnown = result == null || result == 0 || result == LOCK_SUCCESS;
        } catch (SQLException | RuntimeException exception) {
            log.warn("音频转写命名锁释放结果未知: exceptionType={}", exception.getClass().getSimpleName());
        } finally {
            if (!releaseKnown) { abortConnection(connection); }
            closeConnection(connection);
        }
    }

    /** 强制终止释放结果未知的专用会话，不输出数据库异常消息。 */
    private static void abortConnection(Connection connection) {
        try { connection.abort(Runnable::run); }
        catch (SQLException | RuntimeException exception) {
            log.warn("音频转写命名锁会话终止失败: exceptionType={}", exception.getClass().getSimpleName());
        }
    }

    /** 所有领取失败、锁忙和批次结束路径都必须归还专用连接。 */
    private static void closeConnection(Connection connection) {
        try { connection.close(); }
        catch (SQLException | RuntimeException exception) {
            log.warn("音频转写命名锁连接关闭失败: exceptionType={}", exception.getClass().getSimpleName());
            abortConnection(connection);
        }
    }

    /** 已领取的会话锁随单件预检结果跨阶段复用，批次结束必须关闭。 */
    public interface ExecutionLock extends AutoCloseable {
        /** 每次付费提交及写回前确认仍持锁，失锁时终止后续提交。 */
        void checkHeld();

        /** 幂等释放锁及专用会话，不覆盖批次原始结果。 */
        @Override
        void close();
    }

    /** 只有成功领取路径能创建的连接锁，保证同一专用会话的操作串行。 */
    private static final class ConnectionExecutionLock implements ExecutionLock {
        /** 只供命名锁使用的数据库会话。 */
        private final Connection connection;
        /** 当前作品的独占锁名称。 */
        private final String lockName;
        /** 幂等结束会话，关闭后不得继续验证或付费提交。 */
        private boolean closed;

        /** 仅由成功领取的准入路径创建锁凭证。 */
        private ConnectionExecutionLock(Connection connection, String lockName) {
            this.connection = connection; this.lockName = lockName;
        }

        /** 每次付费提交及写回前确认仍持锁；失锁或检查失败时终止后续提交，不自动重新领取。 */
        @Override
        public synchronized void checkHeld() {
            if (closed) { throw new IllegalStateException(LOCK_LOST_MESSAGE); }
            Integer result;
            try {
                result = queryLock(connection, CHECK_LOCK_SQL, lockName);
            } catch (SQLException | RuntimeException exception) {
                log.warn("音频转写命名锁检查失败: exceptionType={}", exception.getClass().getSimpleName());
                throw new IllegalStateException(LOCK_LOST_MESSAGE);
            }
            if (result == null || result != LOCK_SUCCESS) { throw new IllegalStateException(LOCK_LOST_MESSAGE); }
        }

        /** 幂等释放锁并关闭会话，清理错误只记安全日志，不覆盖批次原始结果。 */
        @Override
        public synchronized void close() {
            if (closed) { return; }
            closed = true;
            releaseAndClose(connection, lockName);
        }
    }
}
