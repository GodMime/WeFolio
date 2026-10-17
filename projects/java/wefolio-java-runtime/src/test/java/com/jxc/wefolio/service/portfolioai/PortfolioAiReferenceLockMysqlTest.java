package com.jxc.wefolio.service.portfolioai;

import com.jxc.wefolio.message.PortfolioAiReferenceMessage;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** 在显式指定的隔离 MySQL 中验证命名锁；不读取应用配置或使用 H2 替代。 */
@EnabledIfEnvironmentVariable(named = "WEFOLIO_REFERENCE_TEST_MYSQL_URL", matches = ".+")
class PortfolioAiReferenceLockMysqlTest {
    /** 测试容器使用空密码且仅监听回环地址，没有业务数据。 */
    private DriverManagerDataSource source() {
        return new DriverManagerDataSource(System.getenv("WEFOLIO_REFERENCE_TEST_MYSQL_URL"), "root", "");
    }

    /** 真实 MySQL 执行增量脚本，历史行默认 NULL，原列和行数保持不变。 */
    @Test void migrationAddsNullableJsonColumnsWithoutRewritingExistingRows() throws Exception {
        try (Connection connection = source().getConnection(); var statement = connection.createStatement()) {
            statement.execute("CREATE TEMPORARY TABLE wf_portfolio (id BIGINT PRIMARY KEY, ai_context_json JSON)");
            statement.execute("CREATE TEMPORARY TABLE wf_ai_generation_task (id BIGINT PRIMARY KEY, request_json JSON NOT NULL)");
            statement.execute("INSERT INTO wf_portfolio VALUES (1,JSON_OBJECT('version',1))");
            statement.execute("INSERT INTO wf_ai_generation_task VALUES (1,JSON_OBJECT('input','original'))");
            String migration = Files.readString(Path.of("src/main/resources/db/migration/V66__add_portfolio_ai_reference_attachments.sql"));
            for (String sql : migration.split(";")) { if (!sql.isBlank()) { statement.execute(sql); } }
            try (ResultSet result = statement.executeQuery("SELECT ai_context_json,ai_reference_attachments_json FROM wf_portfolio WHERE id=1")) {
                assertThat(result.next()).isTrue();
                assertThat(result.getString(1)).contains("version"); assertThat(result.getString(2)).isNull();
            }
            try (ResultSet result = statement.executeQuery("SELECT request_json,reference_attachments_json FROM wf_ai_generation_task WHERE id=1")) {
                assertThat(result.next()).isTrue();
                assertThat(result.getString(1)).contains("original"); assertThat(result.getString(2)).isNull();
            }
        }
    }

    /** 两个独立 Service/连接争用同 owner，释放后第二个消费者能够接续。 */
    @Test void competingConnectionsCannotEnterSameOwner() throws Exception {
        PortfolioAiReferenceLock first = new PortfolioAiReferenceLock(source());
        PortfolioAiReferenceLock second = new PortfolioAiReferenceLock(source());
        try (var executor = Executors.newSingleThreadExecutor()) {
            first.execute("USER", 101L, () -> {
                assertThat(executor.submit(() -> {
                    try { second.execute("USER", 101L, () -> true); return false; }
                    catch (RuntimeException exception) { return PortfolioAiReferenceMessage.BUSY.equals(exception.getMessage()); }
                })).succeedsWithin(5, TimeUnit.SECONDS).isEqualTo(true);
                assertThat(second.execute("TEAM", 101L, () -> true)).isTrue();
                return null;
            });
            assertThat(second.execute("USER", 101L, () -> true)).isTrue();
        }
    }

    /** 外部 MySQL 客户端进程持锁时，Runtime 也不能进入同一 owner。 */
    @Test
    @EnabledIfEnvironmentVariable(named = "WEFOLIO_REFERENCE_EXTERNAL_LOCK", matches = "true")
    void respectsOwnerHeldByAnotherProcess() {
        PortfolioAiReferenceLock lock = new PortfolioAiReferenceLock(source());
        assertThatThrownBy(() -> lock.execute("USER", 104L, () -> true)).hasMessage(PortfolioAiReferenceMessage.BUSY);
    }

    /** 异常回滚同样释放命名锁；业务事务结束前保持锁可由另一连接观测。 */
    @Test void lockSurvivesTransactionAndReleasesAfterFailure() {
        DriverManagerDataSource dataSource = source();
        PortfolioAiReferenceLock lock = new PortfolioAiReferenceLock(dataSource);
        TransactionTemplate transaction = new TransactionTemplate(new DataSourceTransactionManager(dataSource));
        assertThatThrownBy(() -> lock.execute("USER", 102L, () -> transaction.execute(status -> {
            assertThat(isFree(dataSource, "pf-ai-ref:USER:102")).isFalse();
            lock.checkHeld();
            throw new IllegalStateException("测试回滚");
        }))).hasMessage("测试回滚");
        assertThat(isFree(dataSource, "pf-ai-ref:USER:102")).isTrue();
        assertThat(lock.execute("USER", 102L, () -> true)).isTrue();
    }

    /** 拒绝事务内反向取锁，防止与清理消费者形成不同锁序。 */
    @Test void rejectsOwnerLockAfterBusinessTransactionStarts() {
        DriverManagerDataSource dataSource = source();
        PortfolioAiReferenceLock lock = new PortfolioAiReferenceLock(dataSource);
        TransactionTemplate transaction = new TransactionTemplate(new DataSourceTransactionManager(dataSource));
        assertThatThrownBy(() -> transaction.execute(status -> lock.execute("USER", 103L, () -> true)))
                .hasMessage(PortfolioAiReferenceMessage.BUSY);
        assertThat(isFree(dataSource, "pf-ai-ref:USER:103")).isTrue();
    }

    /** 独立连接读取服务器锁状态，不能只依赖 Java 模拟结果。 */
    private boolean isFree(DriverManagerDataSource source, String name) {
        try (Connection connection = source.getConnection();
             PreparedStatement statement = connection.prepareStatement("SELECT IS_FREE_LOCK(?)")) {
            statement.setString(1, name);
            try (ResultSet result = statement.executeQuery()) { return result.next() && result.getInt(1) == 1; }
        } catch (Exception exception) { throw new IllegalStateException(exception); }
    }
}
