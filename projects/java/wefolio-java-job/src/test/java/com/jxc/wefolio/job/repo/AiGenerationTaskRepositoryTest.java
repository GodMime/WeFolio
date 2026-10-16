package com.jxc.wefolio.job.repo;

import com.jxc.wefolio.job.entity.AiGenerationTaskEntity;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;
import org.springframework.transaction.interceptor.TransactionInterceptor;
import com.jxc.wefolio.job.config.PortfolioAiProperties;

import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.Executors;

import static org.assertj.core.api.Assertions.assertThat;

/** 用真实 H2 条件更新验证领取竞态、旧代次、取消、数据库截止及隐私物理清理。 */
class AiGenerationTaskRepositoryTest {
    /** 独立测试库。 */ private JdbcTemplate jdbc;
    /** 被测任务仓储。 */ private AiGenerationTaskRepository repository;
    /** 清理与结算使用相同的真实数据库事务管理器。 */
    private DataSourceTransactionManager manager;

    @BeforeEach
    void prepare() {
        var dataSource = new DriverManagerDataSource("jdbc:h2:mem:ai-" + UUID.randomUUID() + ";MODE=MySQL;DB_CLOSE_DELAY=-1", "sa", "");
        jdbc = new JdbcTemplate(dataSource);
        jdbc.execute("CREATE ALIAS UTC_TIMESTAMP FOR 'com.jxc.wefolio.job.repo.PortfolioAiSqlClockTestSupport.utcTimestamp'");
        manager = new DataSourceTransactionManager(dataSource);
        var proxy = new ProxyFactory(new AiGenerationTaskRepository(jdbc));
        proxy.addAdvice(new TransactionInterceptor(manager, new AnnotationTransactionAttributeSource()));
        repository = (AiGenerationTaskRepository) proxy.getProxy();
        jdbc.execute("""
                CREATE TABLE wf_ai_generation_task(id BIGINT PRIMARY KEY AUTO_INCREMENT,owner_type VARCHAR(32),owner_id BIGINT,
                portfolio_id BIGINT,requested_by BIGINT,idempotency_key VARCHAR(64),request_json CLOB,result_json CLOB,status VARCHAR(32),
                attempt_count INT,next_run_at TIMESTAMP(3),started_at TIMESTAMP(3),completed_at TIMESTAMP(3),error_code VARCHAR(64),
                error_message VARCHAR(500),created_at TIMESTAMP(3),updated_at TIMESTAMP(3),deleted BIGINT DEFAULT 0,version INT DEFAULT 0,
                reference_attachments_json CLOB,media_budget_json CLOB,media_budget_unknown_count INT DEFAULT 0)
                """);
        jdbc.execute("CREATE TABLE wf_user(id BIGINT PRIMARY KEY,status VARCHAR(32),deleted BIGINT)");
        jdbc.update("INSERT INTO wf_user VALUES(1,'ACTIVE',0)");
    }

    /** 普通 SQL 领取跳过排在前面的附件，不依赖先查小批再内存过滤。 */
    @Test void ordinarySelectionSkipsReferenceHeadOfLineAndRowMapperKeepsSnapshot() {
        var first = pending(businessNow().minusMinutes(2));
        jdbc.update("UPDATE wf_ai_generation_task SET reference_attachments_json=? WHERE id=?", "{\"version\":1,\"items\":[]}", first.getId());
        pending(businessNow().minusMinutes(1));
        assertThat(repository.findPending(businessNow(), 1).getFirst().getReferenceAttachmentsJson()).isNotNull();
        var ordinary = repository.findPendingWithoutReferences(businessNow(), 1);
        assertThat(ordinary).hasSize(1); assertThat(ordinary.getFirst().getId()).isNotEqualTo(first.getId());
    }

    /** 暂停告警只探测当前可领取附件，不误报普通、退避、已结束或逻辑删除任务。 */
    @Test void pendingReferenceProbeOnlySeesReadyActiveAttachments() {
        var now = businessNow();
        var task = pending(now.minusMinutes(1));
        assertThat(repository.hasPendingReferences(now)).isFalse();
        jdbc.update("UPDATE wf_ai_generation_task SET reference_attachments_json='{}' WHERE id=?", task.getId());
        assertThat(repository.hasPendingReferences(now)).isTrue();
        jdbc.update("UPDATE wf_ai_generation_task SET next_run_at=? WHERE id=?", now.plusMinutes(1), task.getId());
        assertThat(repository.hasPendingReferences(now)).isFalse();
        jdbc.update("UPDATE wf_ai_generation_task SET next_run_at=?,status='SUCCEEDED' WHERE id=?", now.minusMinutes(1), task.getId());
        assertThat(repository.hasPendingReferences(now)).isFalse();
        jdbc.update("UPDATE wf_ai_generation_task SET status='PENDING',deleted=id WHERE id=?", task.getId());
        assertThat(repository.hasPendingReferences(now)).isFalse();
    }

    /** 到期未知费用保留最小账本时也销毁参考快照，不能遗留原件身份和说明。 */
    @Test void privacyCleanupClearsReferencesOnRetainedUnknownCostRow() {
        var task = pending(businessNow().minusDays(40));
        jdbc.update("UPDATE wf_ai_generation_task SET status='FAILED',completed_at=?,media_budget_json='broken',media_budget_unknown_count=1,reference_attachments_json=? WHERE id=?",
                businessNow().minusDays(31), "{\"version\":1,\"items\":[]}", task.getId());
        assertThat(repository.deleteExpired(businessNow().minusDays(30), 10)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT reference_attachments_json FROM wf_ai_generation_task WHERE id=?", String.class, task.getId())).isNull();
        assertThat(repository.deleteExpired(businessNow().minusDays(30), 10)).isZero();
    }

    /** 构造可直接领取的一条任务。 */
    private AiGenerationTaskEntity pending(LocalDateTime created) {
        jdbc.update("""
                INSERT INTO wf_ai_generation_task(owner_type,owner_id,requested_by,idempotency_key,request_json,status,
                attempt_count,next_run_at,created_at,updated_at) VALUES('USER',1,1,'k','{}','PENDING',0,?,?,?)
                """, created, created, created);
        return repository.findPending(businessNow().plusSeconds(1), 10).getFirst();
    }

    /** 与实际入队及执行相同的上海时间，测试不得依赖 Maven JVM 默认时区。 */
    private static LocalDateTime businessNow() { return LocalDateTime.now(ZoneId.of("Asia/Shanghai")); }

    /** SQL 时钟与 JDBC 日期读写在 UTC、上海、纽约会话均保持同一个上海 DATETIME。 */
    @Test void databaseClockAndJdbcDateTimesKeepShanghaiAcrossSessionZones() {
        for (String zone : List.of("UTC", "Asia/Shanghai", "America/New_York")) {
            useDatabaseSessionZone(zone);
            LocalDateTime before = businessNow().minusSeconds(1);
            LocalDateTime sqlNow = jdbc.queryForObject("SELECT " + PortfolioAiDatabaseTime.NOW_SQL, LocalDateTime.class);
            assertThat(sqlNow).isBetween(before, businessNow().plusSeconds(1));
            LocalDateTime created = businessNow().minusSeconds(1).truncatedTo(ChronoUnit.MILLIS);
            AiGenerationTaskEntity task = pending(created);
            assertThat(task.getCreatedAt()).isEqualTo(created);
            assertThat(task.getUpdatedAt()).isEqualTo(created);
            assertThat(repository.claim(task, businessNow(), 3, 180)).isTrue();
            assertThat(repository.renewMediaLease(task, 90, 180)).isTrue();
            assertThat(repository.succeedMedia(task, "{}", businessNow(), 180, 90)).isTrue();
        }
    }

    /** 数据库会话使用 UTC 时，上海 DATETIME 的陈旧租约仍不能发布结果或复活。 */
    @Test void utcDatabaseSessionStillRejectsStaleShanghaiLease() {
        useDatabaseSessionZone("UTC");
        LocalDateTime businessNow = LocalDateTime.now(ZoneId.of("Asia/Shanghai"));
        AiGenerationTaskEntity task = pending(businessNow.minusSeconds(100));
        assertThat(repository.claim(task, businessNow.minusSeconds(95), 3, 180)).isTrue();
        assertThat(repository.succeedMedia(task, "{}", businessNow, 180, 90)).isFalse();
        assertThat(repository.retryMedia(task, "REMOTE_ERROR", "网络失败", businessNow, 180, 90)).isFalse();
        assertThat(repository.renewMediaLease(task, 90, 180)).isFalse();
        assertThat(repository.failInterruptedMedia(task, businessNow, 90, 180)).isTrue();
    }

    /** 数据库会话使用 UTC 时，已越过上海业务截止的任务不能因时差继续领取。 */
    @Test void utcDatabaseSessionStillRejectsExpiredShanghaiTask() {
        useDatabaseSessionZone("UTC");
        LocalDateTime businessNow = LocalDateTime.now(ZoneId.of("Asia/Shanghai"));
        AiGenerationTaskEntity task = pending(businessNow.minusSeconds(181));
        assertThat(repository.claim(task, businessNow, 3, 180)).isFalse();
    }

    /** UTC 会话中正常任务可以续约和发布，续约字段仍使用上海业务时间。 */
    @Test void utcDatabaseSessionCanRenewAndPublishFreshShanghaiTask() {
        useDatabaseSessionZone("UTC");
        LocalDateTime businessNow = LocalDateTime.now(ZoneId.of("Asia/Shanghai"));
        AiGenerationTaskEntity task = pending(businessNow.minusSeconds(1));
        assertThat(repository.claim(task, businessNow, 3, 180)).isTrue();
        assertThat(repository.renewMediaLease(task, 90, 180)).isTrue();
        LocalDateTime heartbeat = jdbc.queryForObject("SELECT updated_at FROM wf_ai_generation_task WHERE id=?",
                LocalDateTime.class, task.getId());
        assertThat(heartbeat).isBetween(businessNow.minusSeconds(1), businessNow.plusSeconds(5));
        assertThat(repository.succeedMedia(task, "{}", businessNow, 180, 90)).isTrue();
    }

    /** DriverManager 每次调用创建连接，因此必须为每个新会话固定测试时区。 */
    private void useDatabaseSessionZone(String zone) {
        var dataSource = (DriverManagerDataSource) jdbc.getDataSource();
        String baseUrl = dataSource.getUrl().split(";INIT=", 2)[0];
        dataSource.setUrl(baseUrl + ";INIT=SET TIME ZONE '" + zone + "'");
    }

    @Test
    void concurrentClaimsConsumeOnlyOneAttemptAndVersion() throws Exception {
        LocalDateTime now = businessNow().truncatedTo(java.time.temporal.ChronoUnit.MILLIS); pending(now.minusSeconds(1));
        AiGenerationTaskEntity first = repository.findPending(now, 1).getFirst();
        AiGenerationTaskEntity second = repository.findPending(now, 1).getFirst();
        try (var pool = Executors.newFixedThreadPool(2)) {
            Callable<Boolean> a = () -> repository.claim(first, now, 3, 180);
            Callable<Boolean> b = () -> repository.claim(second, now, 3, 180);
            var claims = pool.invokeAll(java.util.List.of(a, b));
            assertThat(claims.stream().filter(future -> {
                try { return future.get(); } catch (Exception exception) { throw new IllegalStateException(exception); }
            }).count()).isEqualTo(1);
        }
        assertThat(jdbc.queryForObject("SELECT attempt_count FROM wf_ai_generation_task", Integer.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT version FROM wf_ai_generation_task", Integer.class)).isEqualTo(1);
    }

    /** 正常长媒体由另一实例看到最近心跳，不占恢复额度，也不被误标中断。 */
    @Test
    void longRunningMediaWithRecentHeartbeatDoesNotStarveStaleRecoveryCandidates() {
        LocalDateTime now = businessNow().truncatedTo(java.time.temporal.ChronoUnit.MILLIS);
        AiGenerationTaskEntity active = pending(now.minusSeconds(100));
        assertThat(repository.claim(active, now.minusSeconds(95), 3, 180)).isTrue();
        jdbc.update("UPDATE wf_ai_generation_task SET updated_at=? WHERE id=?", now.minusSeconds(20), active.getId());
        var otherInstance = new AiGenerationTaskRepository(jdbc);
        assertThat(otherInstance.renewMediaLease(active, 90, 180)).isTrue();
        assertThat(repository.failInterruptedMedia(active, now, 90, 180)).isFalse();
        AiGenerationTaskEntity stale = pending(now.minusSeconds(100));
        assertThat(repository.claim(stale, now.minusSeconds(95), 3, 180)).isTrue();
        assertThat(repository.findRecoverable(now, 180, 90, 1)).extracting(AiGenerationTaskEntity::getId)
                .containsExactly(stale.getId());
        assertThat(jdbc.queryForObject("SELECT version FROM wf_ai_generation_task WHERE id=?", Integer.class, active.getId())).isEqualTo(1);
    }

    /** 续约在扫描之后竞争完成时，旧快照的回收 SQL 必须重验心跳并拒绝失败。 */
    @Test
    void staleRecoverySnapshotCannotOverwriteConcurrentHeartbeat() {
        LocalDateTime now = businessNow().truncatedTo(java.time.temporal.ChronoUnit.MILLIS);
        AiGenerationTaskEntity task = pending(now.minusSeconds(100));
        assertThat(repository.claim(task, now.minusSeconds(85), 3, 180)).isTrue();
        // 已超过旧窗口但未满当前九十秒，不能提前回收。
        assertThat(repository.findRecoverable(now, 180, 90, 1)).isEmpty();
        var staleSnapshot = repository.findRecoverable(now.plusSeconds(10), 180, 90, 1).getFirst();
        var otherInstance = new AiGenerationTaskRepository(jdbc);
        assertThat(otherInstance.renewMediaLease(task, 90, 180)).isTrue();
        assertThat(repository.failInterruptedMedia(staleSnapshot, now, 90, 180)).isFalse();
        assertThat(jdbc.queryForObject("SELECT status FROM wf_ai_generation_task", String.class)).isEqualTo("RUNNING");
    }

    /** 陈旧租约不可复活，提前失败保留预算、次数；旧 worker 的晚到结果与重试均失效。 */
    @Test
    void deadMediaLeaseFailsBeforeTotalDeadlineAndPreservesUnknownBudget() {
        LocalDateTime now = businessNow().truncatedTo(java.time.temporal.ChronoUnit.MILLIS);
        AiGenerationTaskEntity task = pending(now.minusSeconds(100));
        assertThat(repository.claim(task, now.minusSeconds(95), 3, 180)).isTrue();
        jdbc.update("UPDATE wf_ai_generation_task SET media_budget_json=?,media_budget_unknown_count=1", "未知费用保留测试");
        assertThat(repository.renewMediaLease(task, 90, 180)).isFalse();
        assertThat(repository.failInterruptedMedia(task, now, 90, 180)).isTrue();
        assertThat(repository.succeed(task, "{}", now, 180)).isFalse();
        assertThat(repository.retry(task, "REMOTE_ERROR", "网络失败", now, 180)).isFalse();
        assertThat(repository.renewMediaLease(task, 90, 180)).isFalse();
        assertThat(jdbc.queryForObject("SELECT status FROM wf_ai_generation_task", String.class)).isEqualTo("FAILED");
        assertThat(jdbc.queryForObject("SELECT error_code FROM wf_ai_generation_task", String.class)).isEqualTo("EXECUTION_INTERRUPTED");
        assertThat(jdbc.queryForObject("SELECT attempt_count FROM wf_ai_generation_task", Integer.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT media_budget_unknown_count FROM wf_ai_generation_task", Integer.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT media_budget_json FROM wf_ai_generation_task", String.class)).isEqualTo("未知费用保留测试");
    }

    /** 取消与总截止都阻止心跳；截止已经跨过时不再写执行中断，让总超时路径收尾。 */
    @Test
    void heartbeatCannotReviveCancelledOrAbsolutelyExpiredTask() {
        LocalDateTime now = businessNow().truncatedTo(java.time.temporal.ChronoUnit.MILLIS);
        AiGenerationTaskEntity task = pending(now.minusSeconds(1));
        assertThat(repository.claim(task, now, 3, 180)).isTrue();
        jdbc.update("UPDATE wf_ai_generation_task SET status='CANCELLED',version=version+1");
        assertThat(repository.renewMediaLease(task, 90, 180)).isFalse();
        assertThat(repository.failInterruptedMedia(task, now, 90, 180)).isFalse();
        jdbc.update("UPDATE wf_ai_generation_task SET status='RUNNING',version=1,created_at=?,updated_at=?",
                now.minusSeconds(181), now.minusSeconds(95));
        assertThat(repository.renewMediaLease(task, 90, 180)).isFalse();
        assertThat(repository.failInterruptedMedia(task, now, 90, 180)).isFalse();
        assertThat(repository.fail(task, "TIMEOUT", "任务超时", now)).isTrue();
    }

    /** Java 最后一次持权检查后发生长暂停，收尾 SQL 仍拒绝陈旧媒体的成功与自动重试。 */
    @Test
    void mediaFinalCasRejectsLeaseThatExpiresAfterLastJavaCheck() {
        LocalDateTime now = businessNow().truncatedTo(java.time.temporal.ChronoUnit.MILLIS);
        AiGenerationTaskEntity task = pending(now.minusSeconds(100));
        assertThat(repository.claim(task, now, 3, 180)).isTrue();
        assertThat(repository.renewMediaLease(task, 90, 180)).isTrue();
        jdbc.update("UPDATE wf_ai_generation_task SET updated_at=?", now.minusSeconds(95));
        assertThat(repository.succeedMedia(task, "{}", now, 180, 90)).isFalse();
        assertThat(repository.retryMedia(task, "REMOTE_ERROR", "网络失败", now, 180, 90)).isFalse();
        assertThat(jdbc.queryForObject("SELECT status FROM wf_ai_generation_task", String.class)).isEqualTo("RUNNING");
        assertThat(jdbc.queryForObject("SELECT attempt_count FROM wf_ai_generation_task", Integer.class)).isEqualTo(1);
        assertThat(repository.failInterruptedMedia(task, now, 90, 180)).isTrue();
        assertThat(repository.succeedMedia(task, "{}", now, 180, 90)).isFalse();
    }

    /** 正常媒体租约的合法结果和可重试错误仍沿原业务期限及次数规则完成。 */
    @Test
    void freshMediaLeaseCanSucceedOrRetryWithinOriginalBudget() {
        LocalDateTime now = businessNow().truncatedTo(java.time.temporal.ChronoUnit.MILLIS);
        AiGenerationTaskEntity succeeded = pending(now.minusSeconds(1));
        assertThat(repository.claim(succeeded, now, 3, 180)).isTrue();
        assertThat(repository.succeedMedia(succeeded, "{}", now, 180, 90)).isTrue();
        AiGenerationTaskEntity retried = pending(now.minusSeconds(1));
        assertThat(repository.claim(retried, now, 3, 180)).isTrue();
        assertThat(repository.retryMedia(retried, "REMOTE_BUSY", "模型繁忙", now, 180, 90)).isTrue();
        assertThat(repository.findPending(now.plusSeconds(5), 10)).extracting(AiGenerationTaskEntity::getId).containsExactly(retried.getId());
    }

    @Test
    void cancelledAndRecoveredExecutionCannotWriteLateSuccessRetryOrFailure() {
        LocalDateTime now = businessNow().truncatedTo(java.time.temporal.ChronoUnit.MILLIS); AiGenerationTaskEntity task = pending(now.minusSeconds(1));
        assertThat(repository.claim(task, now, 3, 180)).isTrue();
        jdbc.update("UPDATE wf_ai_generation_task SET status='CANCELLED',version=version+1,completed_at=?", now);
        assertThat(repository.succeed(task, "{}", now, 180)).isFalse();
        assertThat(repository.retry(task, "INVALID_OUTPUT", "固定摘要", now, 180)).isFalse();
        assertThat(repository.fail(task, "ERROR", "固定摘要", now)).isFalse();
        assertThat(jdbc.queryForObject("SELECT status FROM wf_ai_generation_task", String.class)).isEqualTo("CANCELLED");
        jdbc.update("UPDATE wf_ai_generation_task SET status='RUNNING',version=1,completed_at=NULL");
        assertThat(repository.recover(task, now, 180)).isTrue();
        assertThat(repository.succeed(task, "{}", now, 180)).isFalse();
        assertThat(jdbc.queryForObject("SELECT attempt_count FROM wf_ai_generation_task", Integer.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT version FROM wf_ai_generation_task", Integer.class)).isEqualTo(2);
    }

    @Test
    void successAndRetryUseDatabaseDeadlineEvenIfWorkerSuppliesAnOldNow() {
        LocalDateTime now = businessNow().truncatedTo(java.time.temporal.ChronoUnit.MILLIS); AiGenerationTaskEntity task = pending(now.minusSeconds(1));
        assertThat(repository.claim(task, now, 3, 180)).isTrue();
        jdbc.update("UPDATE wf_ai_generation_task SET created_at=?", now.minusSeconds(181));
        assertThat(repository.succeed(task, "{}", now.minusMinutes(1), 180)).isFalse();
        assertThat(repository.retry(task, "REMOTE_ERROR", "固定摘要", now.minusMinutes(1), 180)).isFalse();
        assertThat(repository.fail(task, "TIMEOUT", "固定摘要", now)).isTrue();
    }

    @Test
    void sharedBudgetUsesFiveThenTenSecondsAndClaimCannotExceedThreeAttempts() {
        LocalDateTime now = businessNow().truncatedTo(java.time.temporal.ChronoUnit.MILLIS); AiGenerationTaskEntity task = pending(now.minusSeconds(1));
        assertThat(repository.claim(task, now, 3, 180)).isTrue();
        assertThat(repository.retry(task, "INVALID_OUTPUT", "固定摘要", now, 180)).isTrue();
        assertThat(repository.findPending(now.plusSeconds(4), 1)).isEmpty();
        task = repository.findPending(now.plusSeconds(5), 1).getFirst();
        assertThat(repository.claim(task, now.plusSeconds(5), 3, 180)).isTrue();
        assertThat(repository.retry(task, "REMOTE_ERROR", "固定摘要", now.plusSeconds(5), 180)).isTrue();
        assertThat(repository.findPending(now.plusSeconds(14), 1)).isEmpty();
        task = repository.findPending(now.plusSeconds(15), 1).getFirst();
        assertThat(repository.claim(task, now.plusSeconds(15), 3, 180)).isTrue();
        assertThat(task.getAttemptCount()).isEqualTo(3);
        jdbc.update("UPDATE wf_ai_generation_task SET status='PENDING',next_run_at=?", now);
        task = repository.findPending(now, 1).getFirst();
        assertThat(repository.claim(task, now, 3, 180)).isFalse();
    }

    @Test
    void cleanupPhysicallyDeletesAllExpiredTerminalKindsIncludingLogicallyDeleted() {
        LocalDateTime now = businessNow().truncatedTo(java.time.temporal.ChronoUnit.MILLIS); pending(now);
        for (String status : java.util.List.of("SUCCEEDED", "FAILED", "CANCELLED")) {
            jdbc.update("""
                    INSERT INTO wf_ai_generation_task(status,completed_at,deleted,request_json,result_json)
                    VALUES(?,?,9,'private prompt','private output')
                    """, status, now.minusDays(31));
        }
        jdbc.update("INSERT INTO wf_ai_generation_task(status,completed_at) VALUES('SUCCEEDED',?)", now.minusDays(29));
        assertThat(repository.deleteExpired(now.minusDays(30), 2)).isEqualTo(2);
        assertThat(repository.deleteExpired(now.minusDays(30), 2)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM wf_ai_generation_task", Integer.class)).isEqualTo(2);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM wf_ai_generation_task WHERE deleted<>0", Integer.class)).isZero();
    }

    /** 未知调用到期只销毁原文并隐藏，后续已知任务不能被旧未知行阻塞清理。 */
    @Test
    void unknownExpiredTaskClearsPrivateTextButKeepsHiddenFeesAndDoesNotStarveKnownCleanup() {
        LocalDateTime now = businessNow().truncatedTo(java.time.temporal.ChronoUnit.MILLIS);
        AiGenerationTaskEntity task = pending(now);
        String fees = makeExpiredUnknown(task, now);
        assertThat(repository.deleteExpired(now.minusDays(30), 1)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT request_json FROM wf_ai_generation_task WHERE id=?", String.class, task.getId())).isEqualTo("{}");
        assertThat(jdbc.queryForObject("SELECT result_json FROM wf_ai_generation_task WHERE id=?", String.class, task.getId())).isNull();
        assertThat(jdbc.queryForObject("SELECT error_message FROM wf_ai_generation_task WHERE id=?", String.class, task.getId())).isNull();
        assertThat(jdbc.queryForObject("SELECT media_budget_json FROM wf_ai_generation_task WHERE id=?", String.class, task.getId())).isEqualTo(fees);
        assertThat(jdbc.queryForObject("SELECT media_budget_unknown_count FROM wf_ai_generation_task WHERE id=?", Integer.class, task.getId())).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT deleted FROM wf_ai_generation_task WHERE id=?", Long.class, task.getId())).isEqualTo(task.getId());
        jdbc.update("INSERT INTO wf_ai_generation_task(status,completed_at,request_json) VALUES('SUCCEEDED',?,'private next task')", now.minusDays(31));
        // 已清未知行不能反复占据第一页，让后续已知任务按原隐私期限物理删除。
        assertThat(repository.deleteExpired(now.minusDays(30), 1)).isEqualTo(1);
        assertThat(repository.deleteExpired(now.minusDays(30), 1)).isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM wf_ai_generation_task", Integer.class)).isEqualTo(1);
        assertThat(repository.findPending(now, 10)).isEmpty();
        assertThat(repository.findRecoverable(now, 180, 90, 10)).isEmpty();
    }

    /** 原文清理后的隐藏费用行仍可结算；已知后按原期限物理删除。 */
    @Test
    void lateSettlementOfHiddenFeeRowAllowsSubsequentPhysicalCleanup() {
        LocalDateTime now = businessNow().truncatedTo(java.time.temporal.ChronoUnit.MILLIS);
        AiGenerationTaskEntity task = pending(now);
        makeExpiredUnknown(task, now);
        repository.deleteExpired(now.minusDays(30), 1);
        mediaFees().settle(task, 1, 1L, 1L, now);
        assertThat(jdbc.queryForObject("SELECT deleted FROM wf_ai_generation_task WHERE id=?", Long.class, task.getId())).isEqualTo(task.getId());
        assertThat(repository.deleteExpired(now.minusDays(30), 1)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM wf_ai_generation_task", Integer.class)).isZero();
    }

    /** 实际事务并发证明未知费用不被清理误删，完整用量先入账才能最终删除。 */
    @Test
    void settlementAndCleanupUseTaskLockToKeepUnknownCallUntilUsageIsStored() throws Exception {
        LocalDateTime now = businessNow().truncatedTo(java.time.temporal.ChronoUnit.MILLIS);
        AiGenerationTaskEntity task = pending(now);
        makeExpiredUnknown(task, now);
        var fees = mediaFees();
        try (var pool = Executors.newFixedThreadPool(2)) {
            var cleaned = pool.submit(() -> repository.deleteExpired(now.minusDays(30), 1));
            var settled = pool.submit(() -> fees.settle(task, 1, 1L, 1L, now));
            cleaned.get(); settled.get();
        }
        int count = jdbc.queryForObject("SELECT COUNT(*) FROM wf_ai_generation_task", Integer.class);
        if (count == 1) {
            assertThat(jdbc.queryForObject("SELECT media_budget_unknown_count FROM wf_ai_generation_task", Integer.class)).isZero();
            var budget = PortfolioAiMediaBudget.parse(jdbc.queryForObject("SELECT media_budget_json FROM wf_ai_generation_task", String.class));
            assertThat(budget.find(1, 1).charged()).isEqualTo(1L);
            assertThat(jdbc.queryForObject("SELECT request_json FROM wf_ai_generation_task", String.class)).isEqualTo("{}");
            assertThat(repository.deleteExpired(now.minusDays(30), 1)).isEqualTo(1);
        }
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM wf_ai_generation_task", Integer.class)).isZero();
    }

    /** 费用 JSON 损坏仍须销毁隐私，但不允许把无法确认的用量视为零。 */
    @Test
    void corruptFeeJsonPreservesUnknownRecordButStillDestroysPrivacy() {
        LocalDateTime now = businessNow().truncatedTo(java.time.temporal.ChronoUnit.MILLIS);
        AiGenerationTaskEntity task = pending(now);
        makeExpiredUnknown(task, now);
        jdbc.update("UPDATE wf_ai_generation_task SET media_budget_json='{broken' WHERE id=?", task.getId());
        assertThat(repository.deleteExpired(now.minusDays(30), 1)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT request_json FROM wf_ai_generation_task", String.class)).isEqualTo("{}");
        assertThat(jdbc.queryForObject("SELECT media_budget_json FROM wf_ai_generation_task", String.class)).isEqualTo("{broken");
        assertThat(jdbc.queryForObject("SELECT media_budget_unknown_count FROM wf_ai_generation_task", Integer.class)).isEqualTo(1);
    }

    /** 构造已发出但尚无 usage 的过期终态调用，保留发起人和实际调用日期。 */
    private String makeExpiredUnknown(AiGenerationTaskEntity task, LocalDateTime now) {
        var budget = PortfolioAiMediaBudget.parse(null);
        budget.reserve(1, 83, now.minusDays(31).toLocalDate());
        task.setAttemptCount(1);
        jdbc.update("""
                UPDATE wf_ai_generation_task SET status='FAILED',attempt_count=1,completed_at=?,
                request_json=?,result_json=?,error_message=?,media_budget_json=?,media_budget_unknown_count=1 WHERE id=?
                """, now.minusDays(31), "{\"private\":\"prompt\"}", "{\"private\":\"model output\"}",
                "private error", budget.json(), task.getId());
        return budget.json();
    }

    /** 用真实事务代理验证费用结算与清理共享的行锁。 */
    private PortfolioAiMediaRepository mediaFees() {
        var proxy = new ProxyFactory(new PortfolioAiMediaRepository(jdbc, new PortfolioAiProperties()));
        proxy.addAdvice(new TransactionInterceptor(manager, new AnnotationTransactionAttributeSource()));
        return (PortfolioAiMediaRepository) proxy.getProxy();
    }
}
