package com.jxc.wefolio.job.service;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.MybatisSqlSessionFactoryBuilder;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.jxc.wefolio.job.config.FeedbackUploadCleanupProperties;
import com.jxc.wefolio.job.entity.FeedbackUploadTaskEntity;
import com.jxc.wefolio.job.mapper.FeedbackUploadTaskMapper;
import org.apache.ibatis.mapping.Environment;
import org.apache.ibatis.session.SqlSessionFactory;
import org.mybatis.spring.SqlSessionTemplate;
import org.mybatis.spring.transaction.SpringManagedTransactionFactory;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.withSettings;
import static org.mockito.AdditionalAnswers.delegatesTo;

/**
 * 反馈附件过期清理服务执行级测试。
 */
@ExtendWith(OutputCaptureExtension.class)
class FeedbackUploadCleanupServiceTest {

    /** 反馈业务统一使用的上海时区。 */
    private static final ZoneId SHANGHAI = ZoneId.of("Asia/Shanghai");

    /** 测试固定时间。 */
    private static final LocalDateTime NOW = LocalDateTime.of(2026, 8, 25, 12, 0);

    /** 测试数据库访问入口。 */
    private JdbcTemplate jdbcTemplate;

    /** 反馈上传任务数据访问对象。 */
    private FeedbackUploadTaskMapper mapper;

    /** COS 清理服务替身。 */
    private FeedbackUploadCleanupCosService cosService;

    /** 清理任务配置。 */
    private FeedbackUploadCleanupProperties properties;

    /** 用于验证行锁语义的事务管理器。 */
    private DataSourceTransactionManager transactionManager;

    /** 为每个用例创建独立数据库和被测依赖。 */
    @BeforeEach
    void setUp() {
        String databaseName = "feedback_upload_cleanup_"
                + UUID.randomUUID().toString().replace("-", "");
        DriverManagerDataSource dataSource = new DriverManagerDataSource(
                "jdbc:h2:mem:" + databaseName + ";MODE=MySQL;DB_CLOSE_DELAY=-1", "sa", "");
        jdbcTemplate = new JdbcTemplate(dataSource);
        createTable();

        TableInfoHelper.remove(FeedbackUploadTaskEntity.class);
        MybatisConfiguration configuration = new MybatisConfiguration();
        configuration.setMapUnderscoreToCamelCase(true);
        configuration.setEnvironment(new Environment(
                "test", new SpringManagedTransactionFactory(), dataSource));
        configuration.addMapper(FeedbackUploadTaskMapper.class);
        SqlSessionFactory sessionFactory = new MybatisSqlSessionFactoryBuilder().build(configuration);
        mapper = new SqlSessionTemplate(sessionFactory).getMapper(FeedbackUploadTaskMapper.class);
        transactionManager = new DataSourceTransactionManager(dataSource);
        cosService = mock(FeedbackUploadCleanupCosService.class);
        properties = new FeedbackUploadCleanupProperties();
    }

    /** 关闭当前用例的内存数据库。 */
    @AfterEach
    void tearDown() {
        jdbcTemplate.execute("SHUTDOWN");
    }

    /** 验证仅按过期顺序清理批量上限内的待上传任务。 */
    @Test
    void shouldOnlyDeleteExpiredPendingTasksInExpiryOrderUpToBatchLimit() {
        properties.setBatchSize(2);
        insert(1L, "PENDING", NOW.minusMinutes(2), 0L);
        insert(2L, "PENDING", NOW, 0L);
        insert(3L, "PENDING", NOW.plusMinutes(1), 0L);
        insert(4L, "CONFIRMED", NOW.minusMinutes(3), 0L);
        insert(5L, "PENDING", NOW.minusMinutes(4), 5L);

        FeedbackUploadCleanupService.CleanupResult result = service().cleanupExpiredUploads();

        InOrder deletionOrder = inOrder(cosService);
        deletionOrder.verify(cosService).delete(1L, objectKey(1L));
        deletionOrder.verify(cosService).delete(2L, objectKey(2L));
        verify(cosService, never()).delete(3L, objectKey(3L));
        verify(cosService, never()).delete(4L, objectKey(4L));
        verify(cosService, never()).delete(5L, objectKey(5L));
        assertThat(status(1L)).isEqualTo("EXPIRED");
        assertThat(status(2L)).isEqualTo("EXPIRED");
        assertThat(status(3L)).isEqualTo("PENDING");
        assertThat(status(4L)).isEqualTo("CONFIRMED");
        assertThat(status(5L)).isEqualTo("PENDING");
        assertThat(result).isEqualTo(
                new FeedbackUploadCleanupService.CleanupResult(2, 2, 0, 0, 1, false));
    }

    /** 验证调度时区配置不改变上海业务时间的过期边界。 */
    @Test
    void schedulingZoneShouldNotChangeShanghaiExpiryBoundary() {
        properties.setZone("UTC");
        insert(1L, "PENDING", NOW.minusMinutes(1), 0L);
        insert(2L, "PENDING", NOW.plusMinutes(1), 0L);
        Clock utcClock = Clock.fixed(
                NOW.atZone(SHANGHAI).toInstant(), ZoneId.of("UTC"));

        FeedbackUploadCleanupService.CleanupResult result =
                service(mapper, utcClock).cleanupExpiredUploads();

        verify(cosService).delete(1L, objectKey(1L));
        verify(cosService, never()).delete(2L, objectKey(2L));
        assertThat(status(1L)).isEqualTo("EXPIRED");
        assertThat(status(2L)).isEqualTo("PENDING");
        assertThat(result).isEqualTo(
                new FeedbackUploadCleanupService.CleanupResult(1, 1, 0, 0, 1, false));
    }

    /** 验证生产构造器始终使用上海时钟。 */
    @Test
    void productionConstructorShouldAlwaysUseShanghaiClock() {
        properties.setZone("UTC");

        FeedbackUploadCleanupService service = new FeedbackUploadCleanupService(
                mapper, cosService, properties, transactionManager);

        Clock productionClock = (Clock) ReflectionTestUtils.getField(service, "clock");
        assertThat(productionClock).isNotNull();
        assertThat(productionClock.getZone()).isEqualTo(SHANGHAI);
    }

    /** 验证单个 COS 删除失败不会中断后续任务。 */
    @Test
    void deleteFailureShouldKeepPendingAndContinueWithLaterTasks() {
        properties.setBatchSize(10);
        insert(1L, "PENDING", NOW.minusMinutes(2), 0L);
        insert(2L, "PENDING", NOW.minusMinutes(1), 0L);
        doThrow(new IllegalStateException("remote failed"))
                .when(cosService).delete(1L, objectKey(1L));

        FeedbackUploadCleanupService.CleanupResult result = service().cleanupExpiredUploads();

        verify(cosService).delete(2L, objectKey(2L));
        assertThat(status(1L)).isEqualTo("PENDING");
        assertThat(status(2L)).isEqualTo("EXPIRED");
        assertThat(result).isEqualTo(
                new FeedbackUploadCleanupService.CleanupResult(2, 1, 1, 0, 1, false));
    }

    /** 验证确认事务持有行锁时清理不会误删刚确认的对象。 */
    @Test
    void confirmationHoldingRowLockShouldPreventCosDeletionAfterCandidateWasRead() throws Exception {
        properties.setBatchSize(10);
        insert(1L, "PENDING", NOW.minusMinutes(1), 0L);
        CountDownLatch candidatesRead = new CountDownLatch(1);
        CountDownLatch allowCandidatesReturn = new CountDownLatch(1);
        CountDownLatch confirmationLocked = new CountDownLatch(1);
        CountDownLatch releaseConfirmation = new CountDownLatch(1);
        CountDownLatch cosDeleteCalled = new CountDownLatch(1);
        FeedbackUploadTaskMapper coordinatedMapper = mock(
                FeedbackUploadTaskMapper.class, withSettings().defaultAnswer(delegatesTo(mapper)));
        doAnswer(invocation -> {
            List<FeedbackUploadTaskEntity> candidates = mapper.selectList(invocation.getArgument(0));
            candidatesRead.countDown();
            assertThat(allowCandidatesReturn.await(1, TimeUnit.SECONDS)).isTrue();
            return candidates;
        }).when(coordinatedMapper).selectList(org.mockito.ArgumentMatchers.any());
        doAnswer(invocation -> {
            cosDeleteCalled.countDown();
            return null;
        }).when(cosService).delete(1L, objectKey(1L));
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<FeedbackUploadCleanupService.CleanupResult> cleanupFuture =
                    executor.submit(() -> service(coordinatedMapper).cleanupExpiredUploads());
            assertThat(candidatesRead.await(1, TimeUnit.SECONDS)).isTrue();
            Future<?> confirmationFuture = executor.submit(() -> new TransactionTemplate(transactionManager)
                    .executeWithoutResult(status -> {
                        jdbcTemplate.queryForObject(
                                "SELECT id FROM wf_feedback_upload_task WHERE id = 1 FOR UPDATE",
                                Long.class);
                        jdbcTemplate.update(
                                "UPDATE wf_feedback_upload_task SET status = 'CONFIRMED' WHERE id = 1");
                        confirmationLocked.countDown();
                        await(releaseConfirmation);
                    }));
            assertThat(confirmationLocked.await(1, TimeUnit.SECONDS)).isTrue();
            allowCandidatesReturn.countDown();
            boolean deleteCalledWhileConfirmationHeld =
                    cosDeleteCalled.await(300, TimeUnit.MILLISECONDS);
            releaseConfirmation.countDown();
            confirmationFuture.get(1, TimeUnit.SECONDS);
            FeedbackUploadCleanupService.CleanupResult result =
                    cleanupFuture.get(1, TimeUnit.SECONDS);

            assertThat(deleteCalledWhileConfirmationHeld).isFalse();
            verify(cosService, never()).delete(1L, objectKey(1L));
            assertThat(status(1L)).isEqualTo("CONFIRMED");
            assertThat(result).isEqualTo(
                    new FeedbackUploadCleanupService.CleanupResult(1, 0, 0, 1, 1, false));
        } finally {
            allowCandidatesReturn.countDown();
            releaseConfirmation.countDown();
            executor.shutdownNow();
        }
    }

    /** 验证非法批量大小在查询和删除前失败。 */
    @Test
    void invalidBatchSizeShouldFailBeforeQueryingOrDeleting() {
        properties.setBatchSize(0);

        assertThatThrownBy(() -> service().cleanupExpiredUploads())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("batch-size");

        verify(cosService, never()).delete(org.mockito.ArgumentMatchers.anyLong(),
                org.mockito.ArgumentMatchers.anyString());
    }

    /** 验证前一批失败后键集游标仍推进并处理下一批。 */
    @Test
    void firstBatchFailuresShouldAdvanceKeysetCursorAndProcessNextBatch() {
        properties.setBatchSize(2);
        properties.setMaxBatches(2);
        LocalDateTime sameExpiry = NOW.minusMinutes(1);
        insert(1L, "PENDING", sameExpiry, 0L);
        insert(2L, "PENDING", sameExpiry, 0L);
        insert(3L, "PENDING", sameExpiry, 0L);
        insert(4L, "PENDING", sameExpiry, 0L);
        doThrow(new IllegalStateException("first failed"))
                .when(cosService).delete(1L, objectKey(1L));
        doThrow(new IllegalStateException("second failed"))
                .when(cosService).delete(2L, objectKey(2L));

        FeedbackUploadCleanupService.CleanupResult result = service().cleanupExpiredUploads();

        InOrder deletionOrder = inOrder(cosService);
        deletionOrder.verify(cosService).delete(1L, objectKey(1L));
        deletionOrder.verify(cosService).delete(2L, objectKey(2L));
        deletionOrder.verify(cosService).delete(3L, objectKey(3L));
        deletionOrder.verify(cosService).delete(4L, objectKey(4L));
        assertThat(status(1L)).isEqualTo("PENDING");
        assertThat(status(2L)).isEqualTo("PENDING");
        assertThat(status(3L)).isEqualTo("EXPIRED");
        assertThat(status(4L)).isEqualTo("EXPIRED");
        assertThat(result).isEqualTo(
                new FeedbackUploadCleanupService.CleanupResult(4, 2, 2, 0, 2, false));
    }

    /** 验证达到最大批次数且仍有候选任务时报告受限。 */
    @Test
    void maxBatchLimitShouldStopWithRemainingCandidateAndReportLimitReached() {
        properties.setBatchSize(2);
        properties.setMaxBatches(1);
        LocalDateTime sameExpiry = NOW.minusMinutes(1);
        insert(1L, "PENDING", sameExpiry, 0L);
        insert(2L, "PENDING", sameExpiry, 0L);
        insert(3L, "PENDING", sameExpiry, 0L);

        FeedbackUploadCleanupService.CleanupResult result = service().cleanupExpiredUploads();

        verify(cosService).delete(1L, objectKey(1L));
        verify(cosService).delete(2L, objectKey(2L));
        verify(cosService, never()).delete(3L, objectKey(3L));
        assertThat(status(3L)).isEqualTo("PENDING");
        assertThat(result).isEqualTo(
                new FeedbackUploadCleanupService.CleanupResult(2, 2, 0, 0, 1, true));
    }

    /** 验证最后一批刚好满载但无后续候选时不误报受限。 */
    @Test
    void fullFinalBatchWithoutNextCandidateShouldNotReportLimitReached() {
        properties.setBatchSize(2);
        properties.setMaxBatches(1);
        insert(1L, "PENDING", NOW.minusMinutes(2), 0L);
        insert(2L, "PENDING", NOW.minusMinutes(1), 0L);

        FeedbackUploadCleanupService.CleanupResult result = service().cleanupExpiredUploads();

        assertThat(result).isEqualTo(
                new FeedbackUploadCleanupService.CleanupResult(2, 2, 0, 0, 1, false));
    }

    /** 验证非法最大批次数在查询和删除前失败。 */
    @Test
    void invalidMaxBatchesShouldFailBeforeQueryingOrDeleting() {
        properties.setMaxBatches(0);

        assertThatThrownBy(() -> service().cleanupExpiredUploads())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("max-batches");

        verify(cosService, never()).delete(org.mockito.ArgumentMatchers.anyLong(),
                org.mockito.ArgumentMatchers.anyString());
    }

    /** 验证单任务失败日志只包含安全诊断字段。 */
    @Test
    void singleTaskFailureLogShouldContainOnlySafeDiagnosticFields(CapturedOutput output) {
        properties.setBatchSize(10);
        insert(1L, "PENDING", NOW.minusMinutes(1), 0L);
        FeedbackUploadTaskMapper failingMapper = mock(
                FeedbackUploadTaskMapper.class, withSettings().defaultAnswer(delegatesTo(mapper)));
        doThrow(new IllegalStateException(
                "database-secret " + objectKey(1L)))
                .when(failingMapper).selectPendingExpiredForUpdate(1L, NOW);

        FeedbackUploadCleanupService.CleanupResult result =
                service(failingMapper).cleanupExpiredUploads();

        assertThat(result.failedCount()).isEqualTo(1);
        assertThat(output)
                .contains("taskId=1")
                .contains("operation=LOCK_DELETE_UPDATE")
                .contains("exceptionType=IllegalStateException")
                .doesNotContain("database-secret")
                .doesNotContain(objectKey(1L));
    }

    /** 使用默认 Mapper 和固定时钟构造被测服务。 */
    private FeedbackUploadCleanupService service() {
        return service(mapper);
    }

    /** 使用指定 Mapper 和固定时钟构造被测服务。 */
    private FeedbackUploadCleanupService service(FeedbackUploadTaskMapper cleanupMapper) {
        Clock clock = Clock.fixed(
                NOW.atZone(SHANGHAI).toInstant(), SHANGHAI);
        return service(cleanupMapper, clock);
    }

    /** 使用指定 Mapper 与时钟构造被测服务。 */
    private FeedbackUploadCleanupService service(
            FeedbackUploadTaskMapper cleanupMapper,
            Clock clock
    ) {
        return new FeedbackUploadCleanupService(
                cleanupMapper, cosService, properties, clock, transactionManager);
    }

    /** 在并发用例中等待信号，并统一处理超时与中断。 */
    private void await(CountDownLatch latch) {
        try {
            if (!latch.await(1, TimeUnit.SECONDS)) {
                throw new IllegalStateException("测试同步等待超时");
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("测试同步等待被中断", exception);
        }
    }

    /** 创建反馈上传任务测试表。 */
    private void createTable() {
        jdbcTemplate.execute("""
                CREATE TABLE wf_feedback_upload_task (
                  id BIGINT PRIMARY KEY,
                  user_id BIGINT NOT NULL,
                  client_id VARCHAR(64) NOT NULL,
                  object_key VARCHAR(512) NOT NULL,
                  media_type VARCHAR(32) NOT NULL,
                  mime_type VARCHAR(128) NOT NULL,
                  file_size BIGINT NOT NULL,
                  duration_ms BIGINT NOT NULL,
                  status VARCHAR(32) NOT NULL,
                  expires_at TIMESTAMP NOT NULL,
                  feedback_id BIGINT,
                  round_no INT,
                  created_at TIMESTAMP NOT NULL,
                  updated_at TIMESTAMP NOT NULL,
                  deleted BIGINT NOT NULL,
                  version INT NOT NULL
                )
                """);
    }

    /** 插入指定状态和过期时间的上传任务。 */
    private void insert(long id, String status, LocalDateTime expiresAt, long deleted) {
        jdbcTemplate.update("""
                        INSERT INTO wf_feedback_upload_task (
                          id, user_id, client_id, object_key, media_type, mime_type,
                          file_size, duration_ms, status, expires_at, feedback_id, round_no,
                          created_at, updated_at, deleted, version
                        ) VALUES (?, 10, ?, ?, 'IMAGE', 'image/jpeg',
                                  1024, 0, ?, ?, NULL, NULL, ?, ?, ?, 0)
                        """,
                id, "client-" + id, objectKey(id), status, expiresAt,
                NOW.minusHours(1), NOW.minusHours(1), deleted);
    }

    /** 查询指定任务的当前状态。 */
    private String status(long id) {
        return jdbcTemplate.queryForObject(
                "SELECT status FROM wf_feedback_upload_task WHERE id = ?", String.class, id);
    }

    /** 生成稳定的测试对象键。 */
    private String objectKey(long id) {
        return "WFA3B1E7A2/others/feedback-" + id + ".jpg";
    }
}
