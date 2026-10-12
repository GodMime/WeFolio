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
class VideoFrameBackfillLockTest {
    private DataSource dataSource;
    private Connection connection;
    private PreparedStatement acquire;
    private PreparedStatement check;
    private PreparedStatement release;
    private ResultSet acquired;
    private ResultSet held;
    private ResultSet released;
    private VideoFrameBackfillLock locks;

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
        locks = new VideoFrameBackfillLock(dataSource);
    }

    @Test
    void dedicatedSessionIsTransferredUntilExplicitCloseAndQueriesAreBounded() throws SQLException {
        var executionLock = locks.tryAcquire().orElseThrow();
        verify(connection).setAutoCommit(true);
        verify(connection,never()).close();
        executionLock.checkHeld();
        verify(acquire).setQueryTimeout(5);
        verify(check).setQueryTimeout(5);
        verify(acquire).setString(1,"wefolio:video-frame-backfill");
        verify(check).setString(1,"wefolio:video-frame-backfill");
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

    @Test
    void busyLockReturnsEmptyAndClosesOnlyItsUnclaimedSession() throws SQLException {
        when(acquired.getInt(1)).thenReturn(0);

        assertTrue(locks.tryAcquire().isEmpty());

        verify(connection).close();
        verify(connection,never()).prepareStatement("SELECT RELEASE_LOCK(?)");
        verify(connection,never()).abort(any());
    }

    @Test
    void nullAcquireResultIsUnavailableAndClosesTheSession() throws SQLException {
        when(acquired.wasNull()).thenReturn(true);

        var failure = assertThrows(IllegalStateException.class,locks::tryAcquire);

        assertEquals("历史视频补帧并发锁不可用，请检查数据库连接",failure.getMessage());
        verify(release).executeQuery();
        verify(connection).close();
    }

    @Test
    void acquisitionErrorDoesNotExposeJdbcDetailsAndStillCleansUp() throws SQLException {
        when(acquire.executeQuery()).thenThrow(new SQLException("jdbc password=not-for-output"));

        var failure = assertThrows(IllegalStateException.class,locks::tryAcquire);

        assertEquals("历史视频补帧并发锁不可用，请检查数据库连接",failure.getMessage());
        assertNull(failure.getCause());
        verify(release).executeQuery();
        verify(connection).close();
    }

    @Test
    void connectionFailureIsUnavailableWithoutAttemptingJdbcOperations() throws SQLException {
        when(dataSource.getConnection()).thenThrow(new SQLException("jdbc password=not-for-output"));

        var failure = assertThrows(IllegalStateException.class,locks::tryAcquire);

        assertEquals("历史视频补帧并发锁不可用，请检查数据库连接",failure.getMessage());
        assertNull(failure.getCause());
        verifyNoInteractions(connection);
    }

    @Test
    void lostLockStopsPaidWorkWithoutAutomaticallyReacquiring() throws SQLException {
        var executionLock = locks.tryAcquire().orElseThrow();
        when(held.getInt(1)).thenReturn(0);

        var failure = assertThrows(IllegalStateException.class,executionLock::checkHeld);

        assertEquals("历史视频补帧并发锁已失效，禁止继续付费提交",failure.getMessage());
        verify(acquire,times(1)).executeQuery();
        executionLock.close();
        verify(connection).close();
    }

    @Test
    void nullOwnershipAndQueryFailureBothStopWorkWithSafeMessages() throws SQLException {
        var executionLock = locks.tryAcquire().orElseThrow();
        when(held.wasNull()).thenReturn(true);
        assertThrows(IllegalStateException.class,executionLock::checkHeld);
        when(check.executeQuery()).thenThrow(new SQLException("jdbc password=not-for-output"));

        var failure = assertThrows(IllegalStateException.class,executionLock::checkHeld);

        assertEquals("历史视频补帧并发锁已失效，禁止继续付费提交",failure.getMessage());
        assertNull(failure.getCause());
        executionLock.close();
        verify(connection).close();
    }

    @Test
    void releaseFailureAbortsUnderlyingSessionBeforeReturningPooledConnection() throws SQLException {
        var executionLock = locks.tryAcquire().orElseThrow();
        when(release.executeQuery()).thenThrow(new SQLException("connection lost"));

        assertDoesNotThrow(executionLock::close);

        var ordered = inOrder(connection);
        ordered.verify(connection).abort(any());
        ordered.verify(connection).close();
    }

    @Test
    void abortFailureStillClosesConnectionAndDoesNotMaskTheBusinessResult() throws SQLException {
        var executionLock = locks.tryAcquire().orElseThrow();
        when(release.executeQuery()).thenThrow(new SQLException("connection lost"));
        doThrow(new SQLException("abort failed")).when(connection).abort(any());

        assertDoesNotThrow(executionLock::close);

        verify(connection).close();
    }
}
