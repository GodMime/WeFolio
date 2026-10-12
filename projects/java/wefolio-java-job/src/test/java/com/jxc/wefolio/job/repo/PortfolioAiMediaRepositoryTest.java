package com.jxc.wefolio.job.repo;

import com.jxc.wefolio.job.config.PortfolioAiProperties;
import com.jxc.wefolio.job.entity.AiGenerationTaskEntity;
import com.jxc.wefolio.job.model.PortfolioAiMediaSummaryIdentity;
import com.jxc.wefolio.job.service.PortfolioAiMediaAccessService;
import com.jxc.wefolio.job.service.PortfolioAiModelClient.CacheStatus;
import com.jxc.wefolio.service.portfolioai.StrictAiJson;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;
import org.springframework.transaction.interceptor.TransactionInterceptor;

import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.Executors;

import static org.assertj.core.api.Assertions.*;

/** H2真实事务验证无新表费用记录、每日并发、版本隔离、独立摘要与过期清理。 */
class PortfolioAiMediaRepositoryTest {
    /** 纯本地隔离数据库。 */
    private JdbcTemplate jdbc;
    /** 带实际 Spring 事务的摘要和费用仓储。 */
    private PortfolioAiMediaRepository repository;
    /** 任务结果 CAS 与隐私清理仓储。 */
    private AiGenerationTaskRepository tasks;
    /** 与数据库微秒精度一致的测试时刻。 */
    private LocalDateTime now;
    /** 每项测试独立的有界预算配置。 */
    private PortfolioAiProperties properties;
    /** 仅在本地表中使用的作品对象键。 */
    private static final String SOURCE_KEY = "OWNER/work/video/a.mp4";
    /** 用于缓存源守卫的独立作品清单。 */
    private static final String MANIFEST = "{\"frames\":[]}";
    /** 已通过封闭协议的媒体短摘要。 */
    private static final String SUMMARY = "{\"summary\":\"婚礼舞台\",\"observations\":[],\"limitations\":[]}";

    /** 构建真实事务代理，不读取环境变量或访问远端服务。 */
    @BeforeEach
    void prepare() {
        var data = new DriverManagerDataSource("jdbc:h2:mem:ai_media_" + UUID.randomUUID()
                + ";MODE=MySQL;DB_CLOSE_DELAY=-1;INIT=SET TIME ZONE 'UTC'", "sa", "");
        jdbc = new JdbcTemplate(data);
        jdbc.execute("CREATE ALIAS UTC_TIMESTAMP FOR 'com.jxc.wefolio.job.repo.PortfolioAiSqlClockTestSupport.utcTimestamp'");
        var manager = new DataSourceTransactionManager(data);
        properties = new PortfolioAiProperties();
        var proxy = new ProxyFactory(new PortfolioAiMediaRepository(jdbc, properties));
        proxy.addAdvice(new TransactionInterceptor(manager, new AnnotationTransactionAttributeSource()));
        repository = (PortfolioAiMediaRepository) proxy.getProxy();
        var taskProxy = new ProxyFactory(new AiGenerationTaskRepository(jdbc));
        taskProxy.addAdvice(new TransactionInterceptor(manager, new AnnotationTransactionAttributeSource()));
        tasks = (AiGenerationTaskRepository) taskProxy.getProxy();
        now = LocalDateTime.now(ZoneId.of("Asia/Shanghai")).truncatedTo(java.time.temporal.ChronoUnit.MICROS);
        jdbc.execute("CREATE TABLE wf_user(id BIGINT PRIMARY KEY,status VARCHAR(32),deleted BIGINT)");
        jdbc.execute("""
                CREATE TABLE wf_ai_generation_task(id BIGINT PRIMARY KEY,requested_by BIGINT,status VARCHAR(32),version INT,
                deleted BIGINT,created_at TIMESTAMP,attempt_count INT DEFAULT 1,media_budget_json CLOB,
                media_budget_unknown_count INT DEFAULT 0,updated_at TIMESTAMP,result_json CLOB,request_json CLOB DEFAULT '{}',
                completed_at TIMESTAMP,error_code VARCHAR(64),error_message VARCHAR(500))
                """);
        jdbc.execute("""
                CREATE TABLE wf_work(id BIGINT PRIMARY KEY,media_object_key VARCHAR(512),media_sha256 VARCHAR(64),
                video_frames_json CLOB,status VARCHAR(32),audit_status VARCHAR(32),deleted BIGINT,version INT,
                updated_at TIMESTAMP,ai_media_summary_json CLOB,ai_media_summary_expires_at TIMESTAMP)
                """);
        jdbc.update("INSERT INTO wf_user VALUES(2,'ACTIVE',0)");
        for (long id = 1; id <= 3; id++) {
            jdbc.update("INSERT INTO wf_ai_generation_task(id,requested_by,status,version,deleted,created_at) VALUES(?,2,'RUNNING',1,0,?)", id, now);
        }
        jdbc.update("INSERT INTO wf_work VALUES(9,?,'sha',?,'ACTIVE','PASSED',0,7,?,NULL,NULL)", SOURCE_KEY, MANIFEST, now.minusDays(1));
    }

    /** 当前执行代次，费用字段专用更新不能改变该版本。 */
    private AiGenerationTaskEntity task(long id) {
        var task = new AiGenerationTaskEntity();
        task.setId(id); task.setRequestedBy(2L); task.setVersion(1); task.setAttemptCount(1);
        return task;
    }
    /** 读取独立费用账本，测试只查询已有任务表。 */
    private PortfolioAiMediaBudget budget(long id) {
        return PortfolioAiMediaBudget.parse(jdbc.queryForObject("SELECT media_budget_json FROM wf_ai_generation_task WHERE id=?", String.class, id));
    }
    /** 当前真实源和帧清单的摘要身份。 */
    private PortfolioAiMediaSummaryIdentity identity(String model) {
        return new PortfolioAiMediaSummaryIdentity("cache-key-" + model,
                PortfolioAiMediaAccessService.fingerprint(SOURCE_KEY, "etag", 1000, "sha"), model,
                "media-summary-v1", "FRAMES", PortfolioAiMediaAccessService.hash(MANIFEST), SOURCE_KEY, "etag", 1000);
    }

    /** 调用数量容量允许五十个摘要和最后生成，费用仍逐次追加并共享默认上限。 */
    @Test
    void defaultCallGateAllowsFiftySummariesAndOneFinalCall() {
        var task = task(1);
        for (int source = 0; source < 50; source++) { repository.reserve(task, 5, now); }
        assertThat(repository.reserve(task, 19, now)).isEqualTo(51);
        assertThat(budget(1).callCount()).isEqualTo(51);
        assertThat(budget(1).unknownCount()).isEqualTo(51);
        assertThat(budget(1).total()).isEqualTo(269);
    }

    /** 首次准入只读当前预算，真正第一次发送仍获得调用序号一。 */
    @Test
    void capacityCheckDoesNotPrechargeOrReserveAnyCall() {
        repository.checkCapacity(task(1), 269, 51, now);
        assertThat(budget(1).callCount()).isZero();
        assertThat(repository.reserve(task(1), 5, now)).isEqualTo(1);
        assertThat(budget(1).total()).isEqualTo(5);
    }

    /** 尚未发送的预留退款同时归还金额和调用次数，稳定占位不会复用旧调用序号。 */
    @Test
    void unsentReservationRefundsBothQuotasWithoutReusingCallNumber() {
        properties.setMediaTaskBudgetCents(83); properties.setMediaDailyBudgetCents(83); properties.setMediaMaxCalls(1);
        var task = task(1); int call = repository.reserve(task, 83, now);
        repository.releaseUnsent(task, call); repository.releaseUnsent(task, call);
        assertThat(budget(1).total()).isZero();
        assertThat(budget(1).unknownCount()).isZero();
        assertThat(budget(1).callCount()).isZero();
        assertThat(budget(1).find(1, call).reserved()).isZero();
        assertThat(repository.reserve(task, 83, now)).isEqualTo(2);
        assertThat(budget(1).callCount()).isEqualTo(1);
        assertThat(budget(1).total()).isEqualTo(83);
    }

    /** 旧代次失权后的退款不改新代次、租约及后续调用序号，也不能吞掉新调用的用量。 */
    @Test
    void oldAttemptCanRefundUnsentReservationWithoutChangingLaterCallIdentity() {
        var old = task(1); int unsent = repository.reserve(old, 83, now);
        LocalDateTime heartbeat = now.plusSeconds(1);
        jdbc.update("UPDATE wf_ai_generation_task SET attempt_count=2,version=2,updated_at=? WHERE id=1", heartbeat);
        var current = task(1); current.setAttemptCount(2); current.setVersion(2);
        int later = repository.reserve(current, 83, now);
        repository.releaseUnsent(old, unsent);
        repository.settle(current, later, 1000L, 100L, now);
        assertThat(budget(1).find(1, unsent).charged()).isZero();
        assertThat(budget(1).find(2, later).charged()).isEqualTo(1);
        assertThat(budget(1).callCount()).isEqualTo(1);
        assertThat(budget(1).unknownCount()).isZero();
        assertThat(jdbc.queryForObject("SELECT version FROM wf_ai_generation_task WHERE id=1", Integer.class)).isEqualTo(2);
        assertThat(jdbc.queryForObject("SELECT updated_at FROM wf_ai_generation_task WHERE id=1", LocalDateTime.class)).isEqualTo(heartbeat);
    }

    /** 已有真实用量即使费用恰好零也消耗调用次数，不能被未发送退款入口改写。 */
    @Test
    void completedZeroUsageStillCountsAsSentAndCannotBeReleased() {
        var task = task(1); int call = repository.reserve(task, 83, now);
        repository.settle(task, call, 0L, 0L, now);
        repository.releaseUnsent(task, call);
        assertThat(budget(1).find(1, call).reserved()).isEqualTo(83);
        assertThat(budget(1).callCount()).isEqualTo(1);
        assertThat(budget(1).unknownCount()).isZero();
    }

    /** 取消或逻辑删除后尚未发送的预留仍可退款，不恢复任务可见性。 */
    @Test
    void cancelledDeletedTaskStillAllowsUnsentRefund() {
        var task = task(1); int call = repository.reserve(task, 83, now);
        jdbc.update("UPDATE wf_ai_generation_task SET status='CANCELLED',version=2,deleted=id WHERE id=1");
        repository.releaseUnsent(task, call);
        assertThat(budget(1).total()).isZero();
        assertThat(budget(1).callCount()).isZero();
        assertThat(jdbc.queryForObject("SELECT status FROM wf_ai_generation_task WHERE id=1", String.class)).isEqualTo("CANCELLED");
        assertThat(jdbc.queryForObject("SELECT deleted FROM wf_ai_generation_task WHERE id=1", Long.class)).isEqualTo(1);
    }

    /** 未知 usage 不退款，后续预留不能超过同一任务预算。 */
    @Test
    void unknownUsageRetainsReservationAndPreventsAnotherMediaCall() {
        properties.setMediaTaskBudgetCents(100);
        var task = task(1); int call = repository.reserve(task, 83, now);
        repository.settle(task, call, null, null, now);
        assertThat(budget(1).find(1, call).charged()).isNull();
        assertThat(budget(1).unknownCount()).isEqualTo(1);
        assertThatThrownBy(() -> repository.reserve(task, 83, now)).hasMessageContaining("预算已用尽");
        assertThat(budget(1).callCount()).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT media_budget_unknown_count FROM wf_ai_generation_task WHERE id=1", Integer.class)).isEqualTo(1);
    }

    /** 全价预留由折扣金额替代后释放任务及日金额额度，真实调用次数与输入总量仍保留。 */
    @Test
    void discountedSettlementReleasesMoneyCapacityButKeepsCallQuotaAndV1Ledger() {
        properties.setMediaTaskBudgetCents(11); properties.setMediaDailyBudgetCents(11); properties.setMediaMaxCalls(1);
        var task = task(1); int call = repository.reserve(task, 11, now);
        assertThat(budget(1).total()).isEqualTo(11);
        assertThatThrownBy(() -> repository.reserve(task(2), 4, now)).hasMessageContaining("预算已用尽");
        repository.settle(task, call, 100_000L, 10_000L, 80_000L, CacheStatus.KNOWN_HIT, now);
        var settled = budget(1).find(1, call);
        assertThat(settled.charged()).isEqualTo(7);
        assertThat(settled.reserved()).isEqualTo(11);
        assertThat(settled.prompt()).isEqualTo(100_000);
        assertThat(settled.completion()).isEqualTo(10_000);
        assertThat(budget(1).unknownCount()).isZero();
        assertThat(budget(1).callCount()).isEqualTo(1);
        assertThatThrownBy(() -> repository.reserve(task, 4, now)).hasMessageContaining("预算已用尽");
        assertThat(repository.reserve(task(2), 4, now)).isEqualTo(1);
        var ledger = StrictAiJson.parseObject(budget(1).json(), 128 * 1024, 8);
        assertThat(ledger.path("version").asText()).isEqualTo("media-budget-v1");
        var fields = new java.util.ArrayList<String>();
        ledger.path("calls").get(0).fieldNames().forEachRemaining(fields::add);
        assertThat(fields).containsExactlyInAnyOrder("attemptNo", "callNo", "budgetDay", "reservedCents",
                "chargedCents", "usageKnown", "promptTokens", "completionTokens");
    }

    /** 失配或未知缓存参数不能绕过客户端归一化制造折扣，合法零缓存同样按全价。 */
    @Test
    void invalidOrUnknownInternalCacheStatisticsConservativelyUseFullPrice() {
        Long[] cached = {-1L, 100_001L, null, 0L, 5L, 50_000L, 50_000L, 50_000L, 50_000L, 0L};
        CacheStatus[] statuses = {CacheStatus.KNOWN_HIT, CacheStatus.KNOWN_HIT, CacheStatus.KNOWN_HIT,
                CacheStatus.KNOWN_HIT, CacheStatus.KNOWN_MISS, CacheStatus.UNKNOWN, CacheStatus.CONFLICT,
                CacheStatus.INVALID, null, CacheStatus.KNOWN_MISS};
        var task = task(1);
        for (int index = 0; index < cached.length; index++) {
            int call = repository.reserve(task, 11, now);
            repository.settle(task, call, 100_000L, 10_000L, cached[index], statuses[index], now);
            assertThat(budget(1).find(1, call).charged()).as("缓存参数场景%s", index).isEqualTo(11);
        }
        assertThat(budget(1).unknownCount()).isZero();
        assertThat(budget(1).callCount()).isEqualTo(cached.length);
    }

    /** 即使有缓存量，缺失或负数核心用量仍 UNKNOWN，不能释放预留。 */
    @Test
    void cacheStatisticsCannotSettleUnknownCoreUsage() {
        var task = task(1); int call = repository.reserve(task, 11, now);
        repository.settle(task, call, null, 10_000L, 80_000L, CacheStatus.CORE_USAGE_UNKNOWN, now);
        repository.settle(task, call, 100_000L, null, 80_000L, CacheStatus.KNOWN_HIT, now);
        repository.settle(task, call, -1L, 10_000L, 0L, CacheStatus.KNOWN_MISS, now);
        repository.settle(task, call, 100_000L, -1L, 80_000L, CacheStatus.KNOWN_HIT, now);
        assertThat(budget(1).find(1, call).charged()).isNull();
        assertThat(budget(1).unknownCount()).isEqualTo(1);
        assertThat(budget(1).total()).isEqualTo(11);
    }

    /** 已结算全价不能追溯降价，已采用折扣也不能被后来更多命中或另一回执重写。 */
    @Test
    void lateCacheInformationCannotRewriteEitherFullPriceOrDiscountedSettlement() {
        var full = task(1); int fullCall = repository.reserve(full, 11, now);
        repository.settle(full, fullCall, 100_000L, 10_000L, now);
        String originalFull = budget(1).json();
        repository.settle(full, fullCall, 100_000L, 10_000L, 80_000L, CacheStatus.KNOWN_HIT, now.plusDays(1));
        assertThat(budget(1).json()).isEqualTo(originalFull);
        assertThat(budget(1).find(1, fullCall).charged()).isEqualTo(11);
        var discounted = task(2); int discountedCall = repository.reserve(discounted, 11, now);
        repository.settle(discounted, discountedCall, 100_000L, 10_000L, 80_000L, CacheStatus.KNOWN_HIT, now);
        String originalDiscount = budget(2).json();
        repository.settle(discounted, discountedCall, 100_000L, 10_000L, 100_000L, CacheStatus.KNOWN_HIT, now.plusDays(1));
        repository.settle(discounted, discountedCall, 100_000L, 10_000L, now.plusDays(1));
        assertThat(budget(2).json()).isEqualTo(originalDiscount);
        assertThat(budget(2).find(1, discountedCall).charged()).isEqualTo(7);
    }

    /** 折扣后仍超过预留时，真实事务提交费用再抛异常，不能因折扣绕过超额保护。 */
    @Test
    void discountedOverrunCommitsActualCostBeforeStopping() {
        var task = task(1); int call = repository.reserve(task, 1, now);
        assertThatThrownBy(() -> repository.settle(task, call, 1_000_000L, 0L, 1_000_000L, CacheStatus.KNOWN_HIT, now))
                .hasMessageContaining("超过预留");
        assertThat(budget(1).find(1, call).charged()).isEqualTo(23);
        assertThat(budget(1).unknownCount()).isZero();
        assertThat(budget(1).callCount()).isEqualTo(1);
    }

    /** 跨日取消后晚到折扣仍归原调用日，且不改版本、心跳及隐藏状态。 */
    @Test
    void lateDiscountedUsageKeepsOriginalDayAndDoesNotReviveCancelledTask() {
        var task = task(1); LocalDateTime sent = now.toLocalDate().atTime(23, 59, 59);
        int call = repository.reserve(task, 11, sent);
        LocalDateTime heartbeat = now.minusSeconds(95);
        jdbc.update("UPDATE wf_ai_generation_task SET status='CANCELLED',version=2,deleted=id,updated_at=? WHERE id=1", heartbeat);
        repository.settle(task, call, 100_000L, 10_000L, 80_000L, CacheStatus.KNOWN_HIT, sent.plusSeconds(2));
        assertThat(budget(1).total(sent.toLocalDate())).isEqualTo(7);
        assertThat(budget(1).total(sent.plusSeconds(2).toLocalDate())).isZero();
        assertThat(jdbc.queryForObject("SELECT status FROM wf_ai_generation_task WHERE id=1", String.class)).isEqualTo("CANCELLED");
        assertThat(jdbc.queryForObject("SELECT version FROM wf_ai_generation_task WHERE id=1", Integer.class)).isEqualTo(2);
        assertThat(jdbc.queryForObject("SELECT deleted FROM wf_ai_generation_task WHERE id=1", Long.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT updated_at FROM wf_ai_generation_task WHERE id=1", LocalDateTime.class)).isEqualTo(heartbeat);
    }

    /** 完整用量结算真实整数分，超额错误必须在费用提交后抛出。 */
    @Test
    void knownUsageSettlesActualRoundedCentsAndOverrunCommitsBeforeThrowing() {
        properties.setMediaTaskBudgetCents(100);
        var task = task(1); int call = repository.reserve(task, 83, now);
        repository.settle(task, call, 6572L, 1713L, now);
        assertThat(budget(1).find(1, call).charged()).isEqualTo(2L);
        int second = repository.reserve(task, 83, now);
        assertThat(second).isEqualTo(2);
        assertThatThrownBy(() -> repository.settle(task, second, 2000000L, 8192L, now)).hasMessageContaining("超过预留");
        assertThat(budget(1).find(1, second).charged()).isEqualTo(163L);
        assertThat(budget(1).unknownCount()).isZero();
        assertThatThrownBy(() -> repository.reserve(task, 1, now)).hasMessageContaining("预算已用尽");
    }

    /** 暂停后晚到的 usage 仍结算，但费用更新绝不能续约陈旧媒体 worker。 */
    @Test
    void lateUsageSettlementKeepsCostWithoutRevivingStaleExecutionLease() {
        var task = task(1); int call = repository.reserve(task, 83, now);
        LocalDateTime stale = now.minusSeconds(95);
        jdbc.update("UPDATE wf_ai_generation_task SET updated_at=? WHERE id=1", stale);
        repository.settle(task, call, 1000L, 100L, now);
        assertThat(budget(1).find(1, call).charged()).isEqualTo(1L);
        assertThat(budget(1).unknownCount()).isZero();
        assertThat(jdbc.queryForObject("SELECT updated_at FROM wf_ai_generation_task WHERE id=1", LocalDateTime.class)).isEqualTo(stale);
        assertThat(tasks.renewMediaLease(task, 90, 180)).isFalse();
        assertThat(tasks.failInterruptedMedia(task, now, 90, 180)).isTrue();
        assertThat(budget(1).find(1, call).charged()).isEqualTo(1L);
    }

    /** 重复或不同的晚到 usage 不改变首次已知费用及原日期。 */
    @Test
    void repeatedSettlementCannotRewriteKnownUsageOrItsDay() {
        var task = task(1); int call = repository.reserve(task, 83, now);
        repository.settle(task, call, 6572L, 1713L, now);
        String original = jdbc.queryForObject("SELECT media_budget_json FROM wf_ai_generation_task WHERE id=1", String.class);
        repository.settle(task, call, 9000000000L, 9000000000L, now.plusDays(1));
        assertThat(jdbc.queryForObject("SELECT media_budget_json FROM wf_ai_generation_task WHERE id=1", String.class)).isEqualTo(original);
        assertThat(budget(1).unknownCount()).isZero();
    }

    /** 预留与结算不变更业务版本，原 worker 仍能按版本写成功。 */
    @Test
    void feeUpdatesDoNotInvalidateWorkerAndOriginalVersionCanSucceed() {
        var task = task(1); int call = repository.reserve(task, 83, now);
        repository.settle(task, call, 1L, 1L, now);
        assertThat(jdbc.queryForObject("SELECT version FROM wf_ai_generation_task WHERE id=1", Integer.class)).isEqualTo(1);
        assertThat(tasks.succeed(task, "{}", now, 180)).isTrue();
        assertThat(budget(1).callCount()).isEqualTo(1);
    }

    /** 两个实例并发预留通过请求人行锁串行，日预算只允许一个通过。 */
    @Test
    void concurrentTaskReservationsShareRequesterDailyLockAcrossInstances() throws Exception {
        properties.setMediaTaskBudgetCents(100);
        properties.setMediaDailyBudgetCents(100);
        try (var pool = Executors.newFixedThreadPool(2)) {
            Callable<Boolean> first = () -> { try { return repository.reserve(task(1), 83, now) > 0; } catch (RuntimeException expected) { return false; } };
            Callable<Boolean> second = () -> { try { return repository.reserve(task(2), 83, now) > 0; } catch (RuntimeException expected) { return false; } };
            var a = pool.submit(first); var b = pool.submit(second);
            assertThat((a.get() ? 1 : 0) + (b.get() ? 1 : 0)).isEqualTo(1);
        }
        assertThat(budget(1).total() + budget(2).total()).isEqualTo(83L);
    }

    /** 已取消、失败或逻辑删除不能抹去已发送费用。 */
    @Test
    void cancelledFailedAndLogicallyDeletedCallsStillConsumeDailyBudget() {
        properties.setMediaTaskBudgetCents(200); properties.setMediaDailyBudgetCents(249);
        repository.reserve(task(1), 83, now);
        repository.reserve(task(2), 83, now);
        jdbc.update("UPDATE wf_ai_generation_task SET status='CANCELLED',version=2,deleted=id WHERE id=1");
        jdbc.update("UPDATE wf_ai_generation_task SET status='FAILED',version=2 WHERE id=2");
        assertThat(repository.reserve(task(3), 83, now)).isEqualTo(1);
        assertThatThrownBy(() -> repository.reserve(task(3), 1, now)).hasMessageContaining("预算已用尽");
        assertThat(budget(1).unknownCount()).isEqualTo(1);
    }

    /** 已取消或隐藏的调用仍可结算，不恢复任务可见性或修改代次。 */
    @Test
    void cancelledOrDeletedCallCanStillSettleWithoutRestoringTaskVisibility() {
        var task = task(1); int call = repository.reserve(task, 83, now);
        jdbc.update("UPDATE wf_ai_generation_task SET status='CANCELLED',version=2,deleted=id WHERE id=1");
        repository.settle(task, call, 1L, 1L, now);
        assertThat(budget(1).unknownCount()).isZero();
        assertThat(jdbc.queryForObject("SELECT status FROM wf_ai_generation_task WHERE id=1", String.class)).isEqualTo("CANCELLED");
        assertThat(jdbc.queryForObject("SELECT version FROM wf_ai_generation_task WHERE id=1", Integer.class)).isEqualTo(2);
        assertThat(jdbc.queryForObject("SELECT deleted FROM wf_ai_generation_task WHERE id=1", Long.class)).isEqualTo(1);
    }

    /** 费用按调用日期计入两个预算日，调用序号跨执行代次累计。 */
    @Test
    void callsKeepTheirActualBudgetDaysAcrossMidnightAndAttempts() {
        properties.setMediaTaskBudgetCents(200); properties.setMediaDailyBudgetCents(200);
        var task = task(1);
        LocalDateTime beforeMidnight = now.toLocalDate().atTime(23, 59, 59);
        repository.reserve(task, 83, beforeMidnight);
        jdbc.update("UPDATE wf_ai_generation_task SET attempt_count=2,version=2 WHERE id=1");
        task.setAttemptCount(2); task.setVersion(2);
        repository.reserve(task, 83, beforeMidnight.plusSeconds(2));
        assertThat(budget(1).total(beforeMidnight.toLocalDate())).isEqualTo(83);
        assertThat(budget(1).total(beforeMidnight.plusSeconds(2).toLocalDate())).isEqualTo(83);
        assertThat(budget(1).find(1, 1)).isNotNull();
        assertThat(budget(1).find(2, 2)).isNotNull();
    }

    /** 旧版本、错误代次和已过绝对期限不能再发出付费请求。 */
    @Test
    void oldWorkerAttemptOwnerOrExpiredTaskCannotReserve() {
        jdbc.update("UPDATE wf_ai_generation_task SET version=2 WHERE id=1");
        assertThatThrownBy(() -> repository.reserve(task(1), 1, now)).hasMessageContaining("失效");
        jdbc.update("UPDATE wf_ai_generation_task SET attempt_count=2 WHERE id=2");
        assertThatThrownBy(() -> repository.reserve(task(2), 1, now)).hasMessageContaining("失效");
        jdbc.update("UPDATE wf_ai_generation_task SET created_at=? WHERE id=3", now.minusSeconds(181));
        assertThatThrownBy(() -> repository.reserve(task(3), 1, now)).hasMessageContaining("失效");
        assertThat(budget(1).callCount() + budget(2).callCount() + budget(3).callCount()).isZero();
    }

    /** 摘要独立保存和过期，幂等刷新不改变作品业务版本、时间和帧。 */
    @Test
    void summaryLivesIndependentlyAndDoesNotChangeVersionUpdatedTimeOrFrames() {
        var identity = identity("glm-5.3-flash");
        repository.cache(9, identity, SUMMARY, now);
        repository.cache(9, identity, SUMMARY, now.plusHours(1));
        assertThat(repository.cached(9, identity, now.plusDays(7))).isEqualTo(SUMMARY);
        assertThat(repository.cached(9, identity("different-model"), now)).isNull();
        assertThat(repository.cached(9, identity, now.plusDays(8))).isNull();
        assertThat(jdbc.queryForObject("SELECT version FROM wf_work WHERE id=9", Integer.class)).isEqualTo(7);
        assertThat(jdbc.queryForObject("SELECT updated_at FROM wf_work WHERE id=9", LocalDateTime.class)).isEqualTo(now.minusDays(1));
        assertThat(jdbc.queryForObject("SELECT video_frames_json FROM wf_work WHERE id=9", String.class)).isEqualTo(MANIFEST);
    }

    /** 视频源 digest 或帧清单变化后拒绝写入旧摘要。 */
    @Test
    void staleSourceDigestOrChangedFramesCannotPublishOldSummary() {
        var identity = identity("glm-5.3-flash");
        jdbc.update("UPDATE wf_work SET media_sha256='replacement' WHERE id=9");
        assertThatThrownBy(() -> repository.cache(9, identity, SUMMARY, now)).hasMessageContaining("失效");
        jdbc.update("UPDATE wf_work SET media_sha256='sha',video_frames_json='{}' WHERE id=9");
        assertThatThrownBy(() -> repository.cache(9, identity, SUMMARY, now)).hasMessageContaining("失效");
        assertThat(jdbc.queryForObject("SELECT ai_media_summary_json FROM wf_work WHERE id=9", String.class)).isNull();
    }

    /** 过期清理只置空摘要字段，不触碰持久帧、作品版本或任务费用。 */
    @Test
    void summaryCleanupClearsOnlyExpiredCacheAndNeverTaskFeesOrPersistentFrames() {
        var identity = identity("glm-5.3-flash");
        repository.cache(9, identity, SUMMARY, now);
        repository.reserve(task(1), 83, now);
        assertThat(repository.cleanup(now.plusDays(6), 100)).isZero();
        assertThat(repository.cleanup(now.plusDays(8), 100)).isEqualTo(1);
        assertThat(repository.cached(9, identity, now.plusDays(8))).isNull();
        assertThat(jdbc.queryForObject("SELECT video_frames_json FROM wf_work WHERE id=9", String.class)).isEqualTo(MANIFEST);
        assertThat(jdbc.queryForObject("SELECT version FROM wf_work WHERE id=9", Integer.class)).isEqualTo(7);
        assertThat(jdbc.queryForObject("SELECT updated_at FROM wf_work WHERE id=9", LocalDateTime.class)).isEqualTo(now.minusDays(1));
        assertThat(budget(1).unknownCount()).isEqualTo(1);
    }

    /** 损坏账本只隔离本任务，历史坏行不能永久阻断同用户新任务。 */
    @Test
    void malformedHistoricalBudgetDoesNotBlockAnotherTaskOrBecomeFreeCalls() {
        properties.setMediaDailyBudgetCents(301);
        jdbc.update("UPDATE wf_ai_generation_task SET media_budget_json='{}',created_at=? WHERE id=1", now.minusDays(2));
        assertThat(repository.reserve(task(2), 1, now)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT media_budget_json FROM wf_ai_generation_task WHERE id=1", String.class)).isEqualTo("{}");
        jdbc.update("UPDATE wf_ai_generation_task SET created_at=? WHERE id=1", now);
        assertThatThrownBy(() -> repository.reserve(task(3), 1, now)).hasMessageContaining("预算已用尽");
        jdbc.update("UPDATE wf_ai_generation_task SET media_budget_json=NULL,media_budget_unknown_count=1 WHERE id=1");
        assertThatThrownBy(() -> repository.reserve(task(1), 1, now)).isInstanceOf(com.jxc.wefolio.job.service.PortfolioAiCallException.class)
                .hasMessageContaining("记录");
    }

    /** 跨午夜坏行只占用其可能付费的两天，不会在下一天继续冻结其它任务。 */
    @Test
    void corruptBudgetKeepsConservativeChargeThroughPossibleMidnightOnly() {
        properties.setMediaDailyBudgetCents(301);
        LocalDateTime created = now.toLocalDate().minusDays(1).atTime(23, 59, 30);
        jdbc.update("UPDATE wf_ai_generation_task SET media_budget_json='{}',created_at=? WHERE id=1", created);
        assertThat(repository.reserve(task(2), 1, now)).isEqualTo(1);
        assertThatThrownBy(() -> repository.reserve(task(3), 1, now)).hasMessageContaining("预算已用尽");
        assertThat(repository.reserve(task(3), 1, now.plusDays(1))).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT media_budget_json FROM wf_ai_generation_task WHERE id=1", String.class)).isEqualTo("{}");
    }

    /** 合法 JSON 内的金额累计溢出也必须成为明确隔离结果，不冒泡算术异常。 */
    @Test
    void overflowingBudgetIsHandledAsAnIsolatedTaskFailure() {
        var corrupt = PortfolioAiMediaBudget.parse(null);
        for (int number = 1; number <= 2; number++) {
            corrupt.reserve(1, 1, now.toLocalDate());
            corrupt.settle(corrupt.find(1, number), Long.MAX_VALUE, 1, 1);
        }
        jdbc.update("UPDATE wf_ai_generation_task SET media_budget_json=?,media_budget_unknown_count=0 WHERE id=1", corrupt.json());
        assertThatThrownBy(() -> repository.reserve(task(1), 1, now)).isInstanceOfSatisfying(com.jxc.wefolio.job.service.PortfolioAiCallException.class,
                exception -> assertThat(exception.getCode()).isEqualTo("MEDIA_BUDGET_INVALID"));
        assertThat(repository.reserve(task(2), 1, now)).isEqualTo(1);
    }

    /** 验证保守媒体输入的83分预留及小额向上取整。 */
    @Test
    void pricesRoundUpToIntegerCents() {
        assertThat(PortfolioAiMediaRepository.costCents(1000000, 8192)).isEqualTo(83);
        assertThat(PortfolioAiMediaRepository.costCents(1, 0)).isEqualTo(1);
        assertThat(PortfolioAiMediaRepository.costCents(0, 0)).isZero();
    }

    /** 混合用量合并后只取整一次；不足一分的优惠不能凭空降低整数分账本。 */
    @Test
    void cachePricesCoverPartialFullZeroHitsAndSingleCombinedRounding() {
        assertThat(PortfolioAiMediaRepository.costCents(100_000, 10_000)).isEqualTo(11);
        assertThat(PortfolioAiMediaRepository.costCents(100_000, 10_000, 80_000L)).isEqualTo(7);
        assertThat(PortfolioAiMediaRepository.costCents(1_000_000, 0, 1_000_000L)).isEqualTo(23);
        assertThat(PortfolioAiMediaRepository.costCents(1_000_000, 0, 0L)).isEqualTo(80);
        assertThat(PortfolioAiMediaRepository.costCents(1, 0, 1L)).isEqualTo(1);
        assertThat(PortfolioAiMediaRepository.costCents(2, 1, 1L)).isEqualTo(1);
        assertThat(PortfolioAiMediaRepository.costCents(0, 0, 0L)).isZero();
        assertThat(PortfolioAiMediaRepository.costCents(100_000, 10_000, null)).isEqualTo(11);
        assertThat(PortfolioAiMediaRepository.costCents(100_000, 10_000, -1L)).isEqualTo(11);
        assertThat(PortfolioAiMediaRepository.costCents(100_000, 10_000, 100_001L)).isEqualTo(11);
    }

    /** Long最大用量的中间乘加超出Long，但最终整数分仍能精确结算。 */
    @Test
    void largeTokenCountsUseExactIntegerArithmeticWithoutIntermediateOverflow() {
        assertThat(PortfolioAiMediaRepository.costCents(Long.MAX_VALUE, Long.MAX_VALUE)).isEqualTo(3_320_413_933_267_720L);
        assertThat(PortfolioAiMediaRepository.costCents(Long.MAX_VALUE, Long.MAX_VALUE, Long.MAX_VALUE)).isEqualTo(2_794_681_727_166_998L);
        assertThat(PortfolioAiMediaRepository.costCents(Long.MAX_VALUE, 0, Long.MAX_VALUE)).isEqualTo(212_137_556_847_660L);
    }
}
