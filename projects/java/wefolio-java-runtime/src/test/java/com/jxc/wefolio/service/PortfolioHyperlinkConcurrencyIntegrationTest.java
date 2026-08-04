package com.jxc.wefolio.service;

import com.jxc.wefolio.dict.PortfolioConfigScopeDict;
import com.jxc.wefolio.dict.ReferenceTypeDict;
import com.jxc.wefolio.entity.PortfolioReferenceEntity;
import com.jxc.wefolio.exception.PortfolioValidationException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 个人作品集超链接图真实事务并发集成测试。
 */
@ActiveProfiles("test")
@SpringBootTest
class PortfolioHyperlinkConcurrencyIntegrationTest {

    /** 当前用户。 */
    private static final long OWNER_ID = 7L;

    /** 来源作品集 A。 */
    private static final long PORTFOLIO_A = 88L;

    /** 目标作品集 B。 */
    private static final long PORTFOLIO_B = 99L;

    /** 同用户第三个作品集，用于验证升序锁定。 */
    private static final long PORTFOLIO_C = 100L;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @Autowired
    private PortfolioHyperlinkGraphService graphService;

    @BeforeEach
    void setUp() {
        createTables();
        resetPortfolioData();
    }

    @AfterEach
    void tearDown() {
        jdbcTemplate.execute("DROP TABLE IF EXISTS wf_portfolio_reference");
        jdbcTemplate.execute("DROP TABLE IF EXISTS wf_portfolio");
    }

    @Test
    @Timeout(15)
    void crossedSavesShouldSerializeInAscendingOrderAndRejectTheSecondCycle() throws Exception {
        ExecutorService executor = Executors.newFixedThreadPool(2);
        CountDownLatch firstLocked = new CountDownLatch(1);
        CountDownLatch releaseFirst = new CountDownLatch(1);
        CountDownLatch secondAcquired = new CountDownLatch(1);
        try {
            Future<Void> first = executor.submit(() -> inTransaction(() -> {
                PortfolioHyperlinkGraphService.LockedGraph graph = graphService.lockUserGraph(
                        OWNER_ID, PORTFOLIO_A);
                assertThat(graph.portfolios()).extracting("id")
                        .containsExactly(PORTFOLIO_A, PORTFOLIO_B, PORTFOLIO_C);
                graphService.validateReferences(
                        graph,
                        PortfolioConfigScopeDict.DRAFT.getCode(),
                        List.of(link(PORTFOLIO_A, PORTFOLIO_B, "c_a_to_b")),
                        null
                );
                insertLink(PORTFOLIO_A, PORTFOLIO_B, "c_a_to_b");
                firstLocked.countDown();
                await(releaseFirst, Duration.ofSeconds(10), "首个保存等待释放超时");
                return null;
            }));
            assertThat(firstLocked.await(5, TimeUnit.SECONDS)).isTrue();

            Future<Throwable> second = executor.submit(() -> captureFailure(() -> inTransaction(() -> {
                PortfolioHyperlinkGraphService.LockedGraph graph = graphService.lockUserGraph(
                        OWNER_ID, PORTFOLIO_B);
                secondAcquired.countDown();
                graphService.validateReferences(
                        graph,
                        PortfolioConfigScopeDict.DRAFT.getCode(),
                        List.of(link(PORTFOLIO_B, PORTFOLIO_A, "c_b_to_a")),
                        null
                );
                insertLink(PORTFOLIO_B, PORTFOLIO_A, "c_b_to_a");
                return null;
            })));

            assertThat(secondAcquired.await(300, TimeUnit.MILLISECONDS)).isFalse();
            releaseFirst.countDown();
            first.get(5, TimeUnit.SECONDS);
            assertThat(second.get(5, TimeUnit.SECONDS))
                    .isInstanceOf(PortfolioValidationException.class)
                    .hasMessage("作品集之间不能循环跳转");
        } finally {
            releaseFirst.countDown();
            executor.shutdownNow();
        }

        assertThat(linkCount(PORTFOLIO_A, PORTFOLIO_B)).isEqualTo(1L);
        assertThat(linkCount(PORTFOLIO_B, PORTFOLIO_A)).isZero();
    }

    @Test
    @Timeout(15)
    void saveThenDeleteRaceShouldCommitTheLinkAndBlockDeletion() throws Exception {
        ExecutorService executor = Executors.newFixedThreadPool(2);
        CountDownLatch saveLocked = new CountDownLatch(1);
        CountDownLatch releaseSave = new CountDownLatch(1);
        CountDownLatch deleteAcquired = new CountDownLatch(1);
        try {
            Future<Void> save = executor.submit(() -> inTransaction(() -> {
                PortfolioHyperlinkGraphService.LockedGraph graph = graphService.lockUserGraph(
                        OWNER_ID, PORTFOLIO_A);
                graphService.validateReferences(
                        graph,
                        PortfolioConfigScopeDict.DRAFT.getCode(),
                        List.of(link(PORTFOLIO_A, PORTFOLIO_B, "c_a_to_b")),
                        null
                );
                insertLink(PORTFOLIO_A, PORTFOLIO_B, "c_a_to_b");
                saveLocked.countDown();
                await(releaseSave, Duration.ofSeconds(10), "保存等待释放超时");
                return null;
            }));
            assertThat(saveLocked.await(5, TimeUnit.SECONDS)).isTrue();

            Future<Throwable> delete = executor.submit(() -> captureFailure(() -> inTransaction(() -> {
                PortfolioHyperlinkGraphService.LockedGraph graph = graphService.lockUserGraph(
                        OWNER_ID, PORTFOLIO_B);
                deleteAcquired.countDown();
                graphService.assertNoIncomingLinks(graph, PORTFOLIO_B);
                softDelete(PORTFOLIO_B);
                return null;
            })));

            assertThat(deleteAcquired.await(300, TimeUnit.MILLISECONDS)).isFalse();
            releaseSave.countDown();
            save.get(5, TimeUnit.SECONDS);
            assertThat(delete.get(5, TimeUnit.SECONDS))
                    .isInstanceOf(PortfolioValidationException.class)
                    .hasMessageContaining("草稿版本");
        } finally {
            releaseSave.countDown();
            executor.shutdownNow();
        }

        assertThat(portfolioDeleted(PORTFOLIO_B)).isZero();
        assertThat(linkCount(PORTFOLIO_A, PORTFOLIO_B)).isEqualTo(1L);
    }

    @Test
    @Timeout(15)
    void deleteThenSaveRaceShouldCommitDeletionAndRejectTheUnavailableTarget() throws Exception {
        ExecutorService executor = Executors.newFixedThreadPool(2);
        CountDownLatch deleteLocked = new CountDownLatch(1);
        CountDownLatch releaseDelete = new CountDownLatch(1);
        CountDownLatch saveAcquired = new CountDownLatch(1);
        try {
            Future<Void> delete = executor.submit(() -> inTransaction(() -> {
                PortfolioHyperlinkGraphService.LockedGraph graph = graphService.lockUserGraph(
                        OWNER_ID, PORTFOLIO_B);
                graphService.assertNoIncomingLinks(graph, PORTFOLIO_B);
                softDelete(PORTFOLIO_B);
                deleteLocked.countDown();
                await(releaseDelete, Duration.ofSeconds(10), "删除等待释放超时");
                return null;
            }));
            assertThat(deleteLocked.await(5, TimeUnit.SECONDS)).isTrue();

            Future<Throwable> save = executor.submit(() -> captureFailure(() -> inTransaction(() -> {
                PortfolioHyperlinkGraphService.LockedGraph graph = graphService.lockUserGraph(
                        OWNER_ID, PORTFOLIO_A);
                saveAcquired.countDown();
                graphService.validateReferences(
                        graph,
                        PortfolioConfigScopeDict.DRAFT.getCode(),
                        List.of(link(PORTFOLIO_A, PORTFOLIO_B, "c_a_to_b")),
                        null
                );
                insertLink(PORTFOLIO_A, PORTFOLIO_B, "c_a_to_b");
                return null;
            })));

            assertThat(saveAcquired.await(300, TimeUnit.MILLISECONDS)).isFalse();
            releaseDelete.countDown();
            delete.get(5, TimeUnit.SECONDS);
            assertThat(save.get(5, TimeUnit.SECONDS))
                    .isInstanceOf(PortfolioValidationException.class)
                    .hasMessage("请选择已发布的个人作品集");
        } finally {
            releaseDelete.countDown();
            executor.shutdownNow();
        }

        assertThat(portfolioDeleted(PORTFOLIO_B)).isEqualTo(PORTFOLIO_B);
        assertThat(linkCount(PORTFOLIO_A, PORTFOLIO_B)).isZero();
    }

    @Test
    @Timeout(20)
    void lockWaitBeyondFiveSecondsShouldFailWithoutPartialReferenceWrites() throws Exception {
        ExecutorService executor = Executors.newFixedThreadPool(2);
        CountDownLatch holderLocked = new CountDownLatch(1);
        CountDownLatch releaseHolder = new CountDownLatch(1);
        AtomicLong elapsedMillis = new AtomicLong();
        try {
            Future<Void> holder = executor.submit(() -> inTransaction(() -> {
                jdbcTemplate.execute("SET LOCK_TIMEOUT 10000");
                graphService.lockUserGraph(OWNER_ID, PORTFOLIO_A);
                holderLocked.countDown();
                await(releaseHolder, Duration.ofSeconds(15), "持锁事务等待释放超时");
                return null;
            }));
            assertThat(holderLocked.await(5, TimeUnit.SECONDS)).isTrue();

            Future<Throwable> waiter = executor.submit(() -> {
                long startedAt = System.nanoTime();
                Throwable failure = captureFailure(() -> inTransaction(() -> {
                    // H2 行锁等待不响应 JDBC Statement 查询超时；测试会话使用与生产 Mapper 相同的 5 秒上限。
                    jdbcTemplate.execute("SET LOCK_TIMEOUT 5000");
                    graphService.lockUserGraph(OWNER_ID, PORTFOLIO_B);
                    insertLink(PORTFOLIO_A, PORTFOLIO_B, "c_partial");
                    return null;
                }));
                elapsedMillis.set(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedAt));
                return failure;
            });

            Throwable failure = waiter.get(8, TimeUnit.SECONDS);
            assertThat(failure)
                    .isInstanceOf(PortfolioValidationException.class)
                    .hasMessage("作品集正在更新，请稍后重试");
            assertThat(elapsedMillis.get()).isBetween(4_000L, 7_500L);
            releaseHolder.countDown();
            holder.get(5, TimeUnit.SECONDS);
        } finally {
            releaseHolder.countDown();
            executor.shutdownNow();
        }

        assertThat(linkCount(PORTFOLIO_A, PORTFOLIO_B)).isZero();
    }

    /** 创建 H2 测试表。 */
    private void createTables() {
        jdbcTemplate.execute("DROP TABLE IF EXISTS wf_portfolio_reference");
        jdbcTemplate.execute("DROP TABLE IF EXISTS wf_portfolio");
        jdbcTemplate.execute("""
                CREATE TABLE wf_portfolio (
                  id BIGINT PRIMARY KEY,
                  share_code VARCHAR(32),
                  owner_type VARCHAR(16) NOT NULL,
                  owner_id BIGINT NOT NULL,
                  template_type VARCHAR(16) NOT NULL,
                  status VARCHAR(32) NOT NULL,
                  schema_version VARCHAR(64),
                  draft_config_json CLOB,
                  draft_revision INT NOT NULL DEFAULT 0,
                  draft_content_hash VARCHAR(64),
                  draft_saved_by BIGINT,
                  draft_saved_at TIMESTAMP,
                  published_config_json CLOB,
                  published_revision INT NOT NULL DEFAULT 0,
                  published_content_hash VARCHAR(64),
                  published_by BIGINT,
                  published_at TIMESTAMP,
                  publication_status VARCHAR(32),
                  ai_prompt CLOB,
                  source_type VARCHAR(32),
                  content_hash VARCHAR(64),
                  current_revision INT NOT NULL DEFAULT 0,
                  previewed_at TIMESTAMP,
                  last_saved_by BIGINT,
                  last_saved_at TIMESTAMP,
                  deleted_at TIMESTAMP,
                  created_at TIMESTAMP NOT NULL,
                  updated_at TIMESTAMP NOT NULL,
                  deleted BIGINT NOT NULL DEFAULT 0,
                  version INT NOT NULL DEFAULT 0
                )
                """);
        jdbcTemplate.execute("""
                CREATE TABLE wf_portfolio_reference (
                  id BIGINT AUTO_INCREMENT PRIMARY KEY,
                  portfolio_id BIGINT NOT NULL,
                  config_scope VARCHAR(16) NOT NULL,
                  reference_type VARCHAR(32) NOT NULL,
                  reference_id BIGINT NOT NULL,
                  component_key VARCHAR(64) NOT NULL,
                  component_path VARCHAR(255) NOT NULL,
                  sort_order INT NOT NULL DEFAULT 0,
                  snapshot_json CLOB,
                  is_valid INT NOT NULL DEFAULT 1,
                  invalid_reason VARCHAR(255),
                  created_at TIMESTAMP NOT NULL,
                  updated_at TIMESTAMP NOT NULL,
                  deleted BIGINT NOT NULL DEFAULT 0,
                  version INT NOT NULL DEFAULT 0
                )
                """);
    }

    /** 重置三个同用户作品集。 */
    private void resetPortfolioData() {
        jdbcTemplate.update("DELETE FROM wf_portfolio_reference");
        jdbcTemplate.update("DELETE FROM wf_portfolio");
        insertPortfolio(PORTFOLIO_C, "PFC");
        insertPortfolio(PORTFOLIO_A, "PFA");
        insertPortfolio(PORTFOLIO_B, "PFB");
    }

    /** 插入可用已发布作品集。 */
    private void insertPortfolio(long id, String shareCode) {
        String config = """
                {"schemaVersion":"standard-personal-v1","share":{"title":"作品集%s"},"components":[
                  {"componentKey":"c_profile","componentType":"PROFILE","sortOrder":1000,"enabled":true,"config":{}}
                ]}
                """.formatted(id);
        jdbcTemplate.update("""
                INSERT INTO wf_portfolio (
                  id, share_code, owner_type, owner_id, template_type, status, schema_version,
                  draft_config_json, draft_revision, published_config_json, published_revision,
                  publication_status, source_type, current_revision, created_at, updated_at, deleted, version
                ) VALUES (?, ?, 'USER', ?, 'STANDARD', 'ACTIVE', 'standard-personal-v1',
                  ?, 0, ?, 1, 'PUBLISHED', 'MANUAL', 0, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, 0, 0)
                """, id, shareCode, OWNER_ID, config, config);
    }

    /** 构造候选内部引用。 */
    private PortfolioReferenceEntity link(long sourceId, long targetId, String componentKey) {
        PortfolioReferenceEntity reference = new PortfolioReferenceEntity();
        reference.setPortfolioId(sourceId);
        reference.setConfigScope(PortfolioConfigScopeDict.DRAFT.getCode());
        reference.setReferenceType(ReferenceTypeDict.LINKED_PORTFOLIO.getCode());
        reference.setReferenceId(targetId);
        reference.setComponentKey(componentKey);
        reference.setComponentPath("components[0].config.targetPortfolioId");
        reference.setSortOrder(1);
        reference.setIsValid(1);
        return reference;
    }

    /** 写入当前草稿内部引用。 */
    private void insertLink(long sourceId, long targetId, String componentKey) {
        jdbcTemplate.update("""
                INSERT INTO wf_portfolio_reference (
                  portfolio_id, config_scope, reference_type, reference_id,
                  component_key, component_path, sort_order, is_valid,
                  created_at, updated_at, deleted, version
                ) VALUES (?, 'DRAFT', 'LINKED_PORTFOLIO', ?, ?,
                  'components[0].config.targetPortfolioId', 1, 1,
                  CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, 0, 0)
                """, sourceId, targetId, componentKey);
    }

    /** 逻辑删除作品集。 */
    private void softDelete(long portfolioId) {
        jdbcTemplate.update("UPDATE wf_portfolio SET deleted = id, deleted_at = CURRENT_TIMESTAMP WHERE id = ?",
                portfolioId);
    }

    /** 查询引用数量。 */
    private long linkCount(long sourceId, long targetId) {
        Long count = jdbcTemplate.queryForObject("""
                SELECT COUNT(*) FROM wf_portfolio_reference
                WHERE portfolio_id = ? AND reference_id = ? AND deleted = 0
                """, Long.class, sourceId, targetId);
        return count == null ? 0L : count;
    }

    /** 查询逻辑删除值。 */
    private long portfolioDeleted(long portfolioId) {
        Long deleted = jdbcTemplate.queryForObject(
                "SELECT deleted FROM wf_portfolio WHERE id = ?", Long.class, portfolioId);
        return deleted == null ? 0L : deleted;
    }

    /** 在独立事务中执行动作。 */
    private <T> T inTransaction(Supplier<T> action) {
        return new TransactionTemplate(transactionManager).execute(status -> action.get());
    }

    /** 捕获并发分支异常，便于主测试线程断言。 */
    private Throwable captureFailure(Runnable action) {
        try {
            action.run();
            return null;
        } catch (Throwable throwable) {
            return throwable;
        }
    }

    /** 有界等待测试闩锁。 */
    private void await(CountDownLatch latch, Duration timeout, String message) {
        try {
            if (!latch.await(timeout.toMillis(), TimeUnit.MILLISECONDS)) {
                throw new IllegalStateException(message);
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(message, exception);
        }
    }
}
