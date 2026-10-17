package com.jxc.wefolio.job.config;

import com.jxc.wefolio.job.service.PortfolioAiReferenceOwnerLock;
import com.zaxxer.hikari.HikariDataSource;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.jdbc.core.JdbcTemplate;
import javax.sql.DataSource;
import java.sql.Connection;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** 用真实单连接 Hikari 池验证命名锁与业务连接容量相互隔离。 */
public class PortfolioAiReferenceLockPoolTest {
    /** 同时启动业务自动配置和独立锁池，确保没有覆盖业务 DataSource Bean。 */
    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(DataSourceAutoConfiguration.class))
            .withUserConfiguration(PortfolioAiReferenceLockPool.class, PortfolioAiReferenceOwnerLock.class)
            .withPropertyValues("spring.datasource.url=jdbc:h2:mem:reference-job-pool",
                    "spring.datasource.driver-class-name=org.h2.Driver", "spring.datasource.username=sa",
                    "spring.datasource.hikari.maximum-pool-size=1", "spring.datasource.hikari.minimum-idle=0",
                    "spring.datasource.hikari.connection-timeout=250", "portfolio-ai.reference-lock-pool-size=1");

    /** 已持有全部锁连接时，动作内部仍能借用业务连接并提交查询。 */
    @Test void heldOwnerLockDoesNotConsumeBusinessPool() {
        runner.run(context -> {
            assertThat(context).hasSingleBean(DataSource.class);
            DataSource business = context.getBean(DataSource.class);
            JdbcTemplate jdbc = new JdbcTemplate(business);
            jdbc.execute("CREATE ALIAS GET_LOCK FOR 'com.jxc.wefolio.job.config.PortfolioAiReferenceLockPoolTest.acquire'");
            jdbc.execute("CREATE ALIAS IS_USED_LOCK FOR 'com.jxc.wefolio.job.config.PortfolioAiReferenceLockPoolTest.owned'");
            jdbc.execute("CREATE ALIAS CONNECTION_ID FOR 'com.jxc.wefolio.job.config.PortfolioAiReferenceLockPoolTest.session'");
            jdbc.execute("CREATE ALIAS RELEASE_LOCK FOR 'com.jxc.wefolio.job.config.PortfolioAiReferenceLockPoolTest.owned'");
            PortfolioAiReferenceOwnerLock lock = context.getBean(PortfolioAiReferenceOwnerLock.class);
            try (var held = lock.tryAcquire("USER", 7).orElseThrow()) {
                held.checkHeld();
                assertThat(jdbc.queryForObject("SELECT 1", Integer.class)).isEqualTo(1);
            }
        });
    }

    /** 锁池饱和只让附件锁快速失败，普通请求仍可借到业务连接。 */
    @Test void saturatedLockPoolLeavesBusinessPoolAvailable() {
        runner.run(context -> {
            HikariDataSource pool = context.getBean(PortfolioAiReferenceLockPool.class).dataSource();
            PortfolioAiReferenceOwnerLock lock = context.getBean(PortfolioAiReferenceOwnerLock.class);
            try (Connection held = pool.getConnection()) {
                assertThatThrownBy(() -> lock.tryAcquire("USER", 7)).isInstanceOf(RuntimeException.class);
                assertThat(new JdbcTemplate(context.getBean(DataSource.class))
                        .queryForObject("SELECT 1", Integer.class)).isEqualTo(1);
                assertThat(pool.getHikariPoolMXBean().getActiveConnections()).isEqualTo(1);
            }
        });
    }

    /** 内存数据库模拟领取成功；这里只验证连接隔离，MySQL 测试另验锁语义。 */
    public static int acquire(String name, int timeout) { return 1; }
    /** 内存数据库模拟当前会话持锁和释放成功。 */
    public static int owned(String name) { return 1; }
    /** 内存数据库提供与所有权查询一致的会话编号。 */
    public static int session() { return 1; }
}
