package com.jxc.wefolio.service.portfolioai;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.extension.plugins.MybatisPlusInterceptor;
import com.baomidou.mybatisplus.extension.plugins.inner.OptimisticLockerInnerInterceptor;
import com.baomidou.mybatisplus.extension.spring.MybatisSqlSessionFactoryBean;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.jxc.wefolio.common.auth.AuthContext;
import com.jxc.wefolio.common.auth.AuthContextHolder;
import com.jxc.wefolio.config.PortfolioAiProperties;
import com.jxc.wefolio.dict.AiTaskStatusDict;
import com.jxc.wefolio.dict.PortfolioOwnerTypeDict;
import com.jxc.wefolio.dict.UserStatusDict;
import com.jxc.wefolio.entity.AiGenerationTaskEntity;
import com.jxc.wefolio.exception.BusinessException;
import com.jxc.wefolio.mapper.AiGenerationTaskEntityMapper;
import com.jxc.wefolio.mapper.UserEntityMapper;
import com.jxc.wefolio.message.PortfolioAiMessage;
import com.jxc.wefolio.message.PortfolioAiTaskMessage;
import org.apache.ibatis.session.SqlSessionFactory;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mybatis.spring.SqlSessionTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.test.context.junit.jupiter.SpringJUnitConfig;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.EnableTransactionManagement;
import javax.sql.DataSource;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.stream.IntStream;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

/** 真正 Spring 事务与 MyBatis/H2 行锁验证跨请求人容量、本人额度、幂等及查询超时收口。 */
@SpringJUnitConfig(PortfolioAiAdmissionIntegrationTest.Config.class)
class PortfolioAiAdmissionIntegrationTest {
    /** Runtime 与 Job 共用的任务总期限失败分类。 */
    private static final String TIMEOUT_ERROR_CODE = "TIMEOUT";
    /** 经过事务代理的实际提交服务。 */
    @Autowired private PortfolioAiTaskTransactionService service;
    /** 查询入口保留真实过期收口及任务状态映射。 */
    @Autowired private PortfolioAiApplicationService application;
    /** 临时数据库查询用于验证提交结果。 */
    @Autowired private JdbcTemplate jdbc;
    /** 本次独立服务级队列配置。 */
    @Autowired private PortfolioAiProperties properties;
    /** 真实实体 Mapper，用于检查预算字段默认值及普通更新隔离。 */
    @Autowired private AiGenerationTaskEntityMapper taskMapper;

    /** 每个测试使用空的本地任务表，不读写真实环境。 */
    @BeforeEach void clearTasks() {
        jdbc.update("DELETE FROM wf_ai_generation_task"); properties.setMaxActiveTasks(2);
        AuthContextHolder.set(new AuthContext(101L, "test-token"));
    }

    /** 查询测试使用的维护者身份不能泄漏到相邻测试。 */
    @AfterEach void clearIdentity() { AuthContextHolder.clear(); }

    /** 超过原三分钟但仍在十分钟内的任务保持运行，也继续占用本人活动额度。 */
    @ParameterizedTest
    @ValueSource(ints = {181, 540})
    void longerRunningTaskRemainsActiveBeforeExtendedDeadline(int ageSeconds) {
        AiGenerationTaskEntity task = runningTask(ageSeconds);

        Map<String, Object> response = application.query(task.getId());

        assertThat(response.get(PortfolioAiConstants.KEY_STATUS)).isEqualTo(AiTaskStatusDict.RUNNING.getCode());
        assertThat(response.get(PortfolioAiConstants.KEY_ERROR_CODE)).isNull();
        AiGenerationTaskEntity retained = taskMapper.selectById(task.getId());
        assertThat(retained.getStatus()).isEqualTo(AiTaskStatusDict.RUNNING.getCode());
        assertThat(retained.getVersion()).isEqualTo(2);
        assertThat(retained.getCompletedAt()).isNull();
        assertThatThrownBy(() -> service.submit(101L, "second-active-task", frozen(101L)))
                .isInstanceOf(BusinessException.class).hasMessage(PortfolioAiMessage.ACTIVE_TASK);
    }

    /** 到达十分钟后查询仍正常收口失败、作废旧执行代次并释放本人活动额度。 */
    @ParameterizedTest
    @ValueSource(ints = {600, 601})
    void extendedDeadlineStillExpiresTaskAndReleasesRequesterSlot(int ageSeconds) {
        AiGenerationTaskEntity task = runningTask(ageSeconds);
        LocalDateTime beforeQuery = PortfolioAiTime.now();

        Map<String, Object> response = application.query(task.getId());

        assertThat(response.get(PortfolioAiConstants.KEY_STATUS)).isEqualTo(AiTaskStatusDict.FAILED.getCode());
        assertThat(response.get(PortfolioAiConstants.KEY_ERROR_CODE)).isEqualTo(TIMEOUT_ERROR_CODE);
        assertThat(response.get(PortfolioAiConstants.KEY_ERROR_MESSAGE)).isEqualTo(PortfolioAiTaskMessage.TASK_FAILED_MESSAGE);
        AiGenerationTaskEntity expired = taskMapper.selectById(task.getId());
        assertThat(expired.getErrorCode()).isEqualTo(TIMEOUT_ERROR_CODE);
        assertThat(expired.getVersion()).isEqualTo(3);
        // H2 时间戳保留微秒，允许纳秒时刻写入后的精度舍入。
        assertThat(expired.getCompletedAt()).isBetween(beforeQuery.minusNanos(1000), PortfolioAiTime.now());
        assertThat(service.submit(101L, "after-timeout-task", frozen(101L)).getStatus())
                .isEqualTo(AiTaskStatusDict.PENDING.getCode());
    }

    /** 使用真实提交事务创建记录，只调整已执行时间及代次以构造期限场景。 */
    private AiGenerationTaskEntity runningTask(int ageSeconds) {
        AiGenerationTaskEntity task = service.submit(101L, "long-running-task", frozen(101L));
        jdbc.update("UPDATE wf_ai_generation_task SET status=?,created_at=?,version=2 WHERE id=?",
                AiTaskStatusDict.RUNNING.getCode(), PortfolioAiTime.now().minusSeconds(ageSeconds), task.getId());
        return task;
    }

    /** 两个线程同时提交同一人不同键，实际只创建一个活动任务。 */
    @Test void requesterLimitSerializesConcurrentDifferentKeys() throws Exception {
        List<Boolean> accepted = concurrently(List.of(101L, 101L));
        assertThat(accepted).containsExactlyInAnyOrder(true, false);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM wf_ai_generation_task", Integer.class)).isEqualTo(1);
    }

    /** 不同人并发在最后一个容量槽位竞争，不能突破服务级硬上限。 */
    @Test void globalQueueCapacityIsAtomicAcrossRequesters() throws Exception {
        List<Boolean> accepted = concurrently(List.of(101L, 102L, 103L));
        assertThat(accepted.stream().filter(Boolean::booleanValue).count()).isEqualTo(2);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM wf_ai_generation_task", Integer.class)).isEqualTo(2);
    }

    /** 同键同参不插第二条，也不因原任务占用活动额度被拒绝。 */
    @Test void replayPreservesFrozenRequestAndIdentity() {
        ObjectNode request = frozen(101L);
        var first = service.submit(101L, "replay-key", request);
        var second = service.submit(101L, "replay-key", request);
        assertThat(second.getId()).isEqualTo(first.getId());
        assertThat(second.getRequestJson()).isEqualTo(first.getRequestJson());
        assertThat(first.getMediaBudgetUnknownCount()).isZero();
        assertThat(second.getMediaBudgetUnknownCount()).isZero();
        assertThat(second.getMediaBudgetJson()).isNull();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM wf_ai_generation_task", Integer.class)).isEqualTo(1);
    }

    /** 旧插入语句不携带媒体字段仍取得零默认值，普通更新不能覆盖 Job 已写入的新预算。 */
    @Test void legacyInsertAndGenericUpdatePreserveMediaBudget() {
        jdbc.update("""
                INSERT INTO wf_ai_generation_task(owner_type,owner_id,requested_by,idempotency_key,request_json,status,
                  attempt_count,next_run_at,created_at,updated_at,deleted,version)
                VALUES(?,101,101,'legacy-media-fields',?,?,0,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP,0,0)
                """, PortfolioOwnerTypeDict.USER.getCode(), frozen(101L).toString(), AiTaskStatusDict.PENDING.getCode());
        AiGenerationTaskEntity original = service.find(101L, "legacy-media-fields");
        assertThat(original.getMediaBudgetUnknownCount()).isZero();
        assertThat(original.getMediaBudgetJson()).isNull();
        jdbc.update("UPDATE wf_ai_generation_task SET media_budget_json=?,media_budget_unknown_count=1 WHERE id=?",
                "{\"version\":1,\"calls\":[{\"reservedCents\":83}]}", original.getId());
        AiGenerationTaskEntity stale = taskMapper.selectById(original.getId());
        String refreshedBudget = "{\"version\":1,\"calls\":[{\"reservedCents\":83},{\"reservedCents\":83}]}";
        jdbc.update("UPDATE wf_ai_generation_task SET media_budget_json=?,media_budget_unknown_count=2 WHERE id=?",
                refreshedBudget, original.getId());
        stale.setStatus(AiTaskStatusDict.FAILED.getCode());
        assertThat(taskMapper.updateById(stale)).isEqualTo(1);
        AiGenerationTaskEntity updated = taskMapper.selectById(original.getId());
        assertThat(updated.getStatus()).isEqualTo(AiTaskStatusDict.FAILED.getCode());
        assertThat(updated.getMediaBudgetJson()).isEqualTo(refreshedBudget);
        assertThat(updated.getMediaBudgetUnknownCount()).isEqualTo(2);
        assertThat(updated.getVersion()).isEqualTo(original.getVersion() + 1);
    }

    /** 异参重放失败后原任务保持不变，事务未产生额外行。 */
    @Test void replayWithDifferentBusinessInputRollsBack() {
        ObjectNode original = frozen(101L); service.submit(101L, "conflict-key", original);
        ObjectNode changed = original.deepCopy(); ((ObjectNode) changed.path("input")).put("prompt", "另一要求");
        assertThatThrownBy(() -> service.submit(101L, "conflict-key", changed)).isInstanceOf(BusinessException.class);
        assertThat(jdbc.queryForObject("SELECT request_json FROM wf_ai_generation_task", String.class)).isEqualTo(original.toString());
    }

    /** 公共互斥行可以是已停用且逻辑删除的用户，不能据此误报当前请求人不可用。 */
    @Test void deletedInactiveGuardStillSerializesAdmission() {
        jdbc.update("INSERT INTO wf_user(id,status,deleted) VALUES(1,?,1)", UserStatusDict.DISABLED.getCode());
        try {
            service.submit(101L, "guard-state-independent", frozen(101L));
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM wf_ai_generation_task", Integer.class)).isEqualTo(1);
        } finally {
            jdbc.update("DELETE FROM wf_user WHERE id=1");
        }
    }

    /** 同时释放线程，执行真实代理与数据库锁。 */
    private List<Boolean> concurrently(List<Long> requesters) throws Exception {
        CountDownLatch ready = new CountDownLatch(requesters.size()), start = new CountDownLatch(1);
        try (var executor = Executors.newFixedThreadPool(requesters.size())) {
            List<Future<Boolean>> futures = IntStream.range(0, requesters.size()).mapToObj(index -> executor.submit(() -> {
                ready.countDown(); start.await();
                try { service.submit(requesters.get(index), "parallel-" + index, frozen(requesters.get(index))); return true; }
                catch (BusinessException exception) { return false; }
            })).toList();
            ready.await(); start.countDown();
            ArrayList<Boolean> values = new ArrayList<>();
            for (Future<Boolean> future : futures) { values.add(future.get()); }
            return values;
        }
    }

    /** 测试仅冻结最小业务输入，来源授权边界由独立测试覆盖。 */
    private ObjectNode frozen(Long requester) {
        return StrictAiJson.parseObject("{\"input\":{\"ownerType\":\"USER\",\"ownerId\":\"" + requester
                + "\",\"portfolioId\":null,\"prompt\":\"演示请求\"},\"facts\":{},\"modelConfig\":{}}", 4096, 8);
    }

    /** 只为该并发测试建立最小事务上下文，避免启动任何远端基础设施。 */
    @Configuration
    @EnableTransactionManagement
    static class Config {
        /** 内存库与二十一列任务表，新增媒体字段沿用可空/零默认值。 */
        @Bean DataSource dataSource() {
            JdbcDataSource source = new JdbcDataSource();
            source.setURL("jdbc:h2:mem:advanced_ai_admission;MODE=MySQL;DB_CLOSE_DELAY=-1;LOCK_TIMEOUT=5000");
            JdbcTemplate jdbc = new JdbcTemplate(source);
            jdbc.execute("CREATE TABLE wf_user(id BIGINT PRIMARY KEY,status VARCHAR(32),deleted BIGINT DEFAULT 0)");
            jdbc.execute("INSERT INTO wf_user(id,status) VALUES(101,'ACTIVE'),(102,'ACTIVE'),(103,'ACTIVE')");
            jdbc.execute("""
                    CREATE TABLE wf_ai_generation_task(
                      id BIGINT AUTO_INCREMENT PRIMARY KEY,owner_type VARCHAR(32),owner_id BIGINT,portfolio_id BIGINT,
                      requested_by BIGINT,idempotency_key VARCHAR(64),request_json CLOB,result_json CLOB,status VARCHAR(32),
                      media_budget_json CLOB,media_budget_unknown_count INT NOT NULL DEFAULT 0,
                      attempt_count INT,next_run_at TIMESTAMP,started_at TIMESTAMP,completed_at TIMESTAMP,
                      error_code VARCHAR(64),error_message VARCHAR(500),created_at TIMESTAMP,updated_at TIMESTAMP,
                      deleted BIGINT DEFAULT 0,version INT DEFAULT 0,UNIQUE(requested_by,idempotency_key,deleted))
                    """);
            return source;
        }
        /** 真实 JDBC 事务管理器，服务注解通过代理生效。 */
        @Bean PlatformTransactionManager transactionManager(DataSource source) { return new DataSourceTransactionManager(source); }
        /** 注册实际两个 Mapper、生产乐观锁拦截器和 Lambda 映射，保留版本 CAS。 */
        @Bean SqlSessionFactory sqlSessionFactory(DataSource source) throws Exception {
            MybatisConfiguration configuration = new MybatisConfiguration(); configuration.setMapUnderscoreToCamelCase(true);
            configuration.addMapper(UserEntityMapper.class); configuration.addMapper(AiGenerationTaskEntityMapper.class);
            MybatisSqlSessionFactoryBean factory = new MybatisSqlSessionFactoryBean(); factory.setDataSource(source); factory.setConfiguration(configuration);
            MybatisPlusInterceptor interceptor = new MybatisPlusInterceptor();
            interceptor.addInnerInterceptor(new OptimisticLockerInnerInterceptor());
            factory.setPlugins(interceptor);
            return factory.getObject();
        }
        /** 事务内连接由 MyBatis Spring 共享。 */
        @Bean SqlSessionTemplate sqlSessionTemplate(SqlSessionFactory factory) { return new SqlSessionTemplate(factory); }
        /** 当前请求人行锁和全局入队行锁。 */
        @Bean UserEntityMapper userMapper(SqlSessionTemplate template) { return template.getMapper(UserEntityMapper.class); }
        /** 实际任务插入和幂等查询。 */
        @Bean AiGenerationTaskEntityMapper taskMapper(SqlSessionTemplate template) { return template.getMapper(AiGenerationTaskEntityMapper.class); }
        /** 授权查询独立模拟，不替换数据库并发逻辑。 */
        @Bean PortfolioAiFactsService facts() { return mock(PortfolioAiFactsService.class); }
        /** 本地配置不加载密钥。 */
        @Bean PortfolioAiProperties properties() { return new PortfolioAiProperties(); }
        /** 由容器生成提交服务事务代理。 */
        @Bean PortfolioAiTaskTransactionService service(UserEntityMapper users, AiGenerationTaskEntityMapper tasks,
                PortfolioAiProperties properties, PortfolioAiFactsService facts) { return new PortfolioAiTaskTransactionService(users, tasks, properties, facts); }
        /** 查询保持真实服务及任务 Mapper，候选渲染和远端媒体能力在活动态测试中不使用。 */
        @Bean PortfolioAiApplicationService application(PortfolioAiProperties properties, AiGenerationTaskEntityMapper tasks,
                PortfolioAiTaskTransactionService transactions, PortfolioAiFactsService facts) {
            return new PortfolioAiApplicationService(properties, tasks, transactions, facts,
                    mock(PortfolioAiConfigService.class), mock(PortfolioAiMediaPlanService.class), mock(PortfolioAiShareImageService.class));
        }
        /** 查询断言使用相同临时数据源。 */
        @Bean JdbcTemplate jdbc(DataSource source) { return new JdbcTemplate(source); }
    }
}
