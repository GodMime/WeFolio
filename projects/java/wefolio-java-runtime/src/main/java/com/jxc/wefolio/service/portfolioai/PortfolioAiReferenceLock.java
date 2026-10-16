package com.jxc.wefolio.service.portfolioai;

import com.jxc.wefolio.exception.BusinessException;
import com.jxc.wefolio.config.PortfolioAiReferenceLockPool;
import com.jxc.wefolio.message.PortfolioAiReferenceMessage;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionSynchronization;
import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.function.Supplier;

/** 与 Job 共用 owner 命名锁；专用连接不参与业务事务，也不需要锁表。 */
@Component
@Slf4j
public class PortfolioAiReferenceLock {
    /** 跨工程固定锁协议。 */
    private static final String PREFIX="pf-ai-ref:", ACQUIRE="SELECT GET_LOCK(?,0)",
            CHECK="SELECT IS_USED_LOCK(?)=CONNECTION_ID()", RELEASE="SELECT RELEASE_LOCK(?)";
    /** 每次锁查询最多五秒。 */
    private static final int QUERY_SECONDS=5;
    /** 专用连接池入口。 */
    private final DataSource dataSource;
    /** 同线程嵌套同 owner 复用已持锁连接，避免自己的后置清理再次竞争。 */
    private final ThreadLocal<Held> current=new ThreadLocal<>();

    /** 生产环境必须使用独立锁池，避免与动作内部的业务事务争抢连接。 */
    @Autowired
    public PortfolioAiReferenceLock(PortfolioAiReferenceLockPool pool) { this(pool.dataSource()); }

    /** 允许锁协议测试提供专用测试会话。 */
    PortfolioAiReferenceLock(DataSource dataSource) { this.dataSource=dataSource; }

    /** 必须由调用方在最终事务外进入，返回时业务事务已经结束。 */
    public <T>T execute(String type,long id,Supplier<T> action) {
        String name=PREFIX+type+":"+id;
        Held nested=current.get();
        if(nested!=null) {
            if(!nested.name().equals(name)) throw new BusinessException(PortfolioAiReferenceMessage.BUSY);
            checkHeld(); return action.get();
        }
        if(TransactionSynchronizationManager.isActualTransactionActive()) throw new BusinessException(PortfolioAiReferenceMessage.BUSY);
        Connection connection=null;
        try {
            connection=dataSource.getConnection(); connection.setAutoCommit(true);
            if(!Integer.valueOf(1).equals(query(connection,ACQUIRE,name))) throw new BusinessException(PortfolioAiReferenceMessage.BUSY);
            current.set(new Held(name,connection));
            T result=action.get(); checkHeld(); return result;
        } catch(SQLException exception) {
            throw new BusinessException(PortfolioAiReferenceMessage.BUSY);
        } finally {
            current.remove();
            if(connection!=null) {
                try {
                    Integer released=query(connection,RELEASE,name);
                    // 0 表示当前会话未持锁，NULL 表示锁不存在；均不会遗留当前会话的命名锁。
                    if(released!=null && released!=0 && released!=1) throw new SQLException("命名锁释放结果未知");
                }
                catch(SQLException | RuntimeException exception) {
                    try { connection.abort(Runnable::run); } catch(SQLException ignored) { log.warn("参考附件锁连接终止失败"); }
                }
                try { connection.close(); } catch(SQLException exception) { log.warn("参考附件锁连接关闭失败"); }
            }
        }
    }

    /** 在最终引用写入或 COS 删除前检查专用连接仍持有锁。 */
    public void checkHeld() {
        Held held=current.get();
        try { if(held==null || !Integer.valueOf(1).equals(query(held.connection(),CHECK,held.name()))) throw new BusinessException(PortfolioAiReferenceMessage.BUSY); }
        catch(SQLException exception) { throw new BusinessException(PortfolioAiReferenceMessage.BUSY); }
    }

    /** 最终事务真正提交之前复验锁，失锁时回滚而不是提交后才报告失败。 */
    public void checkBeforeCommit() {
        checkHeld();
        if(TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override public void beforeCommit(boolean readOnly) { checkHeld(); }
            });
        }
    }

    /** 查询值 NULL 按失败处理，连接错误不能降级为无锁执行。 */
    private static Integer query(Connection connection,String sql,String name)throws SQLException {
        try(PreparedStatement statement=connection.prepareStatement(sql)) {
            statement.setQueryTimeout(QUERY_SECONDS); statement.setString(1,name);
            try(ResultSet result=statement.executeQuery()) { if(!result.next()) throw new SQLException("命名锁查询未返回结果"); int value=result.getInt(1); return result.wasNull()?null:value; }
        }
    }
    /** 当前线程持有的专用会话。 */
    private record Held(String name,Connection connection) { }
}
