package com.jxc.wefolio.service.portfolioai;

import com.jxc.wefolio.exception.BusinessException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** 锁异常资源收尾；真正的跨连接串行由独立 MySQL 集成验证负责。 */
class PortfolioAiReferenceLockTest {
    /** 测试后清理事务同步线程状态。 */
    @AfterEach void clear() {if(TransactionSynchronizationManager.isSynchronizationActive())TransactionSynchronizationManager.clearSynchronization();TransactionSynchronizationManager.setActualTransactionActive(false);}

    /** 禁止已经获取业务行锁的事务反向申请 owner 锁。 */
    @Test void rejectsAcquisitionInsideTransaction() {
        DataSource source=mock(DataSource.class);TransactionSynchronizationManager.setActualTransactionActive(true);
        assertThatThrownBy(()->new PortfolioAiReferenceLock(source).execute("USER",7,()->null)).isInstanceOf(BusinessException.class);
        verifyNoInteractions(source);
    }
    /** 释放结果未知必须终止连接，不能把潜在持锁会话直接归还连接池。 */
    @Test void abortsConnectionWhenReleaseIsUnknown()throws Exception {
        DataSource source=mock(DataSource.class);Connection connection=mock(Connection.class);when(source.getConnection()).thenReturn(connection);
        PreparedStatement statement=mock(PreparedStatement.class);ResultSet result=mock(ResultSet.class);
        when(connection.prepareStatement(anyString())).thenReturn(statement);when(statement.executeQuery()).thenReturn(result);when(result.next()).thenReturn(true);
        when(result.getInt(1)).thenReturn(1,1);when(result.wasNull()).thenReturn(false);
        when(statement.executeQuery()).thenReturn(result,result).thenThrow(new SQLException("释放查询超时"));
        new PortfolioAiReferenceLock(source).execute("USER",7,()->null);
        verify(connection).abort(any());verify(connection).close();
    }
    /** 未抢到锁时不能终止健康会话，释放返回零说明锁由其他会话持有。 */
    @Test void contentionReturnsHealthyConnectionWithoutAborting()throws Exception {
        DataSource source=mock(DataSource.class);Connection connection=mock(Connection.class);when(source.getConnection()).thenReturn(connection);
        PreparedStatement statement=mock(PreparedStatement.class);ResultSet result=mock(ResultSet.class);
        when(connection.prepareStatement(anyString())).thenReturn(statement);when(statement.executeQuery()).thenReturn(result);when(result.next()).thenReturn(true);
        when(result.getInt(1)).thenReturn(0);when(result.wasNull()).thenReturn(false);
        assertThatThrownBy(()->new PortfolioAiReferenceLock(source).execute("USER",7,()->null)).isInstanceOf(BusinessException.class);
        verify(connection,never()).abort(any());verify(connection).close();
    }
    /** 释放返回 SQL NULL 表示锁已不存在，可以安全归还连接。 */
    @Test void absentLockReturnsHealthyConnectionWithoutAborting()throws Exception {
        DataSource source=mock(DataSource.class);Connection connection=mock(Connection.class);when(source.getConnection()).thenReturn(connection);
        PreparedStatement statement=mock(PreparedStatement.class);ResultSet result=mock(ResultSet.class);
        when(connection.prepareStatement(anyString())).thenReturn(statement);when(statement.executeQuery()).thenReturn(result);when(result.next()).thenReturn(true);
        when(result.getInt(1)).thenReturn(1,1,0);when(result.wasNull()).thenReturn(false,false,true);
        new PortfolioAiReferenceLock(source).execute("USER",7,()->null);
        verify(connection,never()).abort(any());verify(connection).close();
    }
    /** 真正提交前再次检查连接持锁状态，丢锁不得让事务提交。 */
    @Test void registersBeforeCommitOwnershipCheck()throws Exception {
        DataSource source=mock(DataSource.class);Connection connection=mock(Connection.class);when(source.getConnection()).thenReturn(connection);
        PreparedStatement statement=mock(PreparedStatement.class);ResultSet result=mock(ResultSet.class);
        when(connection.prepareStatement(anyString())).thenReturn(statement);when(statement.executeQuery()).thenReturn(result);when(result.next()).thenReturn(true);
        when(result.getInt(1)).thenReturn(1,1,0,1);when(result.wasNull()).thenReturn(false);
        PortfolioAiReferenceLock lock=new PortfolioAiReferenceLock(source);
        assertThatThrownBy(()->lock.execute("USER",7,()->{
            TransactionSynchronizationManager.initSynchronization();lock.checkBeforeCommit();
            TransactionSynchronizationManager.getSynchronizations().forEach(sync->sync.beforeCommit(false));return null;
        })).isInstanceOf(BusinessException.class);
        verify(connection).close();
    }
}
