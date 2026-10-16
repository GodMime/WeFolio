package com.jxc.wefolio.config;

import com.zaxxer.hikari.HikariDataSource;
import jakarta.annotation.PreDestroy;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.jdbc.DataSourceProperties;
import org.springframework.stereotype.Component;

/** 参考附件命名锁独占小型连接池，不占用业务事务池或替代其自动配置。 */
@Component
public class PortfolioAiReferenceLockPool {
    /** 借锁连接最多等待一秒；池满只拒绝本次附件动作，不拖住普通业务。 */
    private static final long CONNECTION_TIMEOUT_MS = 1_000;
    /** 数据库监控中区分锁会话与业务会话。 */
    private static final String POOL_NAME = "portfolio-reference-runtime-locks";
    /** 延迟初始化的专用池；Bean 本身不实现 DataSource，避免 Spring 业务池自动配置回退。 */
    private final HikariDataSource pool;

    /** 复用数据库地址和认证配置，但使用独立连接容量和归还周期。 */
    public PortfolioAiReferenceLockPool(DataSourceProperties properties,
            @Value("${portfolio-ai.reference-lock-pool-size:4}") int maximumPoolSize) {
        if (maximumPoolSize < 1) { throw new IllegalArgumentException("参考附件锁池容量必须大于零"); }
        pool = properties.initializeDataSourceBuilder().type(HikariDataSource.class).build();
        pool.setPoolName(POOL_NAME);
        pool.setMaximumPoolSize(maximumPoolSize);
        pool.setMinimumIdle(0);
        pool.setConnectionTimeout(CONNECTION_TIMEOUT_MS);
        pool.setValidationTimeout(CONNECTION_TIMEOUT_MS);
        pool.setInitializationFailTimeout(-1);
    }

    /** 仅提供给命名锁组件，不作为业务 DataSource 注册。 */
    public HikariDataSource dataSource() { return pool; }

    /** 关闭应用时终止专用会话，释放遗留命名锁。 */
    @PreDestroy
    public void close() { pool.close(); }
}
