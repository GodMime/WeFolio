package com.jxc.wefolio.service.teamportfolio;

import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;

/** 真实数据库提交及回滚下验证引用互斥释放时机，远端 COS 仅用屏障模拟。 */
class PortfolioReferenceTransactionIntegrationTest {
    /** 提交后的对象清理尚未结束时，其他引用写入仍不能进入。 */
    @Test
    void mutexMustRemainHeldDuringAfterCommitAndRollbackMustSkipCleanup() throws Exception {
        DriverManagerDataSource source = new DriverManagerDataSource(
                "jdbc:h2:mem:reference_commit_boundary;DB_CLOSE_DELAY=-1", "sa", "");
        JdbcTemplate jdbc = new JdbcTemplate(source);
        jdbc.execute("CREATE TABLE reference_work (id BIGINT PRIMARY KEY, deleted INT)");
        jdbc.update("INSERT INTO reference_work VALUES (1,0)");
        TransactionTemplate transaction = new TransactionTemplate(new DataSourceTransactionManager(source));
        LocalPortfolioReferenceMutex mutex = new LocalPortfolioReferenceMutex();
        CountDownLatch cleanupEntered = new CountDownLatch(1);
        CountDownLatch releaseCleanup = new CountDownLatch(1);
        CountDownLatch writerEntered = new CountDownLatch(1);
        try (var workers = Executors.newFixedThreadPool(2)) {
            var deleting = workers.submit(() -> transaction.execute(status -> mutex.execute(() -> {
                jdbc.update("UPDATE reference_work SET deleted=1 WHERE id=1");
                TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                    /** 模拟已经提交但对象清理仍执行的窗口。 */
                    @Override public void afterCommit() {
                        cleanupEntered.countDown();
                        await(releaseCleanup);
                    }
                });
                return null;
            })));
            assertThat(cleanupEntered.await(2, TimeUnit.SECONDS)).isTrue();
            assertThat(jdbc.queryForObject("SELECT deleted FROM reference_work WHERE id=1", Integer.class)).isEqualTo(1);
            var writing = workers.submit(() -> transaction.execute(status -> mutex.execute(() -> {
                writerEntered.countDown();
                return jdbc.queryForObject("SELECT deleted FROM reference_work WHERE id=1", Integer.class);
            })));
            assertThat(writerEntered.await(100, TimeUnit.MILLISECONDS)).isFalse();
            releaseCleanup.countDown();
            deleting.get(2, TimeUnit.SECONDS);
            assertThat(writing.get(2, TimeUnit.SECONDS)).isEqualTo(1);
            AtomicBoolean deletedObject = new AtomicBoolean();
            transaction.execute(status -> mutex.execute(() -> {
                jdbc.update("UPDATE reference_work SET deleted=0 WHERE id=1");
                TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                    /** 回滚时不得执行对象删除。 */
                    @Override public void afterCommit() { deletedObject.set(true); }
                });
                status.setRollbackOnly();
                return null;
            }));
            assertThat(deletedObject).isFalse();
            assertThat(jdbc.queryForObject("SELECT deleted FROM reference_work WHERE id=1", Integer.class)).isEqualTo(1);
        } finally {
            releaseCleanup.countDown();
            jdbc.execute("DROP ALL OBJECTS");
        }
    }

    /** 等待清理屏障并保留中断。 */
    private static void await(CountDownLatch latch) {
        try {
            if (!latch.await(5, TimeUnit.SECONDS)) throw new IllegalStateException("清理屏障超时");
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("清理屏障中断", exception);
        }
    }
}
