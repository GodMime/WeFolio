package com.jxc.wefolio.job.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** 验证无表跨实例锁的准入、会话所有权及连接池清理边界，不访问真实数据库。 */
class VideoAudioWorkLockTest {
    /** 模拟连接池借出独立会话，不连接真实 MySQL。 */
    private DataSource dataSource;
    /** 持有单件命名锁直到显式关闭的专用连接。 */
    private Connection connection;
    /** 领取命名锁的 SQL 替身。 */
    private PreparedStatement acquire;
    /** 检查命名锁仍归当前会话所有的 SQL 替身。 */
    private PreparedStatement check;
    /** 归还连接前释放命名锁的 SQL 替身。 */
    private PreparedStatement release;
    /** 可调整成功、竞争和未知状态的领取响应。 */
    private ResultSet acquired;
    /** 可模拟失锁或 SQL NULL 的持有响应。 */
    private ResultSet held;
    /** 可模拟释放成功或失败的清理响应。 */
    private ResultSet released;
    /** 使用真实锁命名与连接清理逻辑的被测组件。 */
    private VideoAudioWorkLock locks;

    /** 默认建立成功的领取、验证及释放路径，个别测试覆盖异常响应。 */
    @BeforeEach
    void setup() throws SQLException {
        dataSource = mock(DataSource.class);
        connection = mock(Connection.class);
        acquire = mock(PreparedStatement.class);
        check = mock(PreparedStatement.class);
        release = mock(PreparedStatement.class);
        acquired = mock(ResultSet.class);
        held = mock(ResultSet.class);
        released = mock(ResultSet.class);
        when(dataSource.getConnection()).thenReturn(connection);
        when(connection.prepareStatement("SELECT GET_LOCK(?, 0)")).thenReturn(acquire);
        when(connection.prepareStatement("SELECT IS_USED_LOCK(?) = CONNECTION_ID()")).thenReturn(check);
        when(connection.prepareStatement("SELECT RELEASE_LOCK(?)")).thenReturn(release);
        when(acquire.executeQuery()).thenReturn(acquired);
        when(check.executeQuery()).thenReturn(held);
        when(release.executeQuery()).thenReturn(released);
        when(acquired.next()).thenReturn(true);
        when(held.next()).thenReturn(true);
        when(released.next()).thenReturn(true);
        when(acquired.getInt(1)).thenReturn(1);
        when(held.getInt(1)).thenReturn(1);
        when(released.getInt(1)).thenReturn(1);
        locks = new VideoAudioWorkLock(dataSource);
    }

    /** 生命周期内只持有会话锁，各查询有限时，关闭操作保持幂等。 */
    @Test
    void dedicatedSessionIsTransferredUntilExplicitCloseAndQueriesAreBounded() throws SQLException {
        var executionLock = locks.tryAcquire(17).orElseThrow();
        verify(connection).setAutoCommit(true);
        verify(connection,never()).close();
        executionLock.checkHeld();
        verify(acquire).setQueryTimeout(5);
        verify(check).setQueryTimeout(5);
        verify(acquire).setString(1,"wefolio:video-audio:17");
        verify(check).setString(1,"wefolio:video-audio:17");
        verify(connection,never()).commit();
        verify(connection,never()).rollback();

        executionLock.close();
        executionLock.close();

        var ordered = inOrder(release,connection);
        ordered.verify(release).executeQuery();
        ordered.verify(connection).close();
        verify(release,times(1)).executeQuery();
        verify(connection,times(1)).close();
        verify(connection,never()).abort(any());
        assertThrows(IllegalStateException.class,executionLock::checkHeld);
    }

    /** 已有实例持锁时立即跳过并归还未领取成功的连接。 */
    @Test
    void busyLockReturnsEmptyAndClosesOnlyItsUnclaimedSession() throws SQLException {
        when(acquired.getInt(1)).thenReturn(0);

        assertTrue(locks.tryAcquire(17).isEmpty());

        verify(connection).close();
        verify(connection,never()).prepareStatement("SELECT RELEASE_LOCK(?)");
        verify(connection,never()).abort(any());
    }

    /** 未知领取结果必须作为不可用处理，不能继续付费。 */
    @Test
    void nullAcquireResultIsUnavailableAndClosesTheSession() throws SQLException {
        when(acquired.wasNull()).thenReturn(true);

        var failure = assertThrows(IllegalStateException.class,() -> locks.tryAcquire(17));

        assertEquals("视频音频转写并发锁不可用，请检查数据库连接",failure.getMessage());
        verify(release).executeQuery();
        verify(connection).close();
    }

    /** JDBC 异常不能把连接凭据带入业务异常。 */
    @Test
    void acquisitionErrorDoesNotExposeJdbcDetailsAndStillCleansUp() throws SQLException {
        when(acquire.executeQuery()).thenThrow(new SQLException("jdbc password=not-for-output"));

        var failure = assertThrows(IllegalStateException.class,() -> locks.tryAcquire(17));

        assertEquals("视频音频转写并发锁不可用，请检查数据库连接",failure.getMessage());
        assertNull(failure.getCause());
        verify(release).executeQuery();
        verify(connection).close();
    }

    /** 连接池借出失败时不尝试操作不存在的会话。 */
    @Test
    void connectionFailureIsUnavailableWithoutAttemptingJdbcOperations() throws SQLException {
        when(dataSource.getConnection()).thenThrow(new SQLException("jdbc password=not-for-output"));

        var failure = assertThrows(IllegalStateException.class,() -> locks.tryAcquire(17));

        assertEquals("视频音频转写并发锁不可用，请检查数据库连接",failure.getMessage());
        assertNull(failure.getCause());
        verifyNoInteractions(connection);
    }

    /** 失锁后拒绝继续付费，也不自动重新领取掩盖竞争。 */
    @Test
    void lostLockStopsPaidWorkWithoutAutomaticallyReacquiring() throws SQLException {
        var executionLock = locks.tryAcquire(17).orElseThrow();
        when(held.getInt(1)).thenReturn(0);

        var failure = assertThrows(IllegalStateException.class,executionLock::checkHeld);

        assertEquals("视频音频转写并发锁已失效，禁止继续付费提交",failure.getMessage());
        verify(acquire,times(1)).executeQuery();
        executionLock.close();
        verify(connection).close();
    }

    /** 未知持有状态和检查异常都必须阻止后续工作。 */
    @Test
    void nullOwnershipAndQueryFailureBothStopWorkWithSafeMessages() throws SQLException {
        var executionLock = locks.tryAcquire(17).orElseThrow();
        when(held.wasNull()).thenReturn(true);
        assertThrows(IllegalStateException.class,executionLock::checkHeld);
        when(check.executeQuery()).thenThrow(new SQLException("jdbc password=not-for-output"));

        var failure = assertThrows(IllegalStateException.class,executionLock::checkHeld);

        assertEquals("视频音频转写并发锁已失效，禁止继续付费提交",failure.getMessage());
        assertNull(failure.getCause());
        executionLock.close();
        verify(connection).close();
    }

    /** 释放结果未知时终止物理会话，避免锁随连接返回池中。 */
    @Test
    void releaseFailureAbortsUnderlyingSessionBeforeReturningPooledConnection() throws SQLException {
        var executionLock = locks.tryAcquire(17).orElseThrow();
        when(release.executeQuery()).thenThrow(new SQLException("connection lost"));

        assertDoesNotThrow(executionLock::close);

        var ordered = inOrder(connection);
        ordered.verify(connection).abort(any());
        ordered.verify(connection).close();
    }

    /** 强制终止失败仍尝试关闭连接，且不覆盖原业务结果。 */
    @Test
    void abortFailureStillClosesConnectionAndDoesNotMaskTheBusinessResult() throws SQLException {
        var executionLock = locks.tryAcquire(17).orElseThrow();
        when(release.executeQuery()).thenThrow(new SQLException("connection lost"));
        doThrow(new SQLException("abort failed")).when(connection).abort(any());

        assertDoesNotThrow(executionLock::close);

        verify(connection).close();
    }
}
