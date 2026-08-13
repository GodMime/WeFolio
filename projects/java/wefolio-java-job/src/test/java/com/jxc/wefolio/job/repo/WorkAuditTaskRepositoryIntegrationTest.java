package com.jxc.wefolio.job.repo;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.MybatisSqlSessionFactoryBuilder;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.jxc.wefolio.job.entity.WorkAuditTaskEntity;
import com.jxc.wefolio.job.mapper.WorkAuditTaskMapper;
import org.apache.ibatis.mapping.Environment;
import org.apache.ibatis.session.SqlSession;
import org.apache.ibatis.session.SqlSessionFactory;
import org.apache.ibatis.transaction.jdbc.JdbcTransactionFactory;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 作品审核任务仓储 SQL 执行级集成测试。
 */
class WorkAuditTaskRepositoryIntegrationTest {

    private static final LocalDateTime NOW = LocalDateTime.of(2026, 8, 13, 12, 0);

    private JdbcTemplate jdbcTemplate;
    private SqlSession sqlSession;
    private WorkAuditTaskRepository repository;

    @BeforeEach
    void setUp() {
        String databaseName = "work_audit_recovery_"
                + UUID.randomUUID().toString().replace("-", "");
        DriverManagerDataSource dataSource = new DriverManagerDataSource(
                "jdbc:h2:mem:" + databaseName + ";MODE=MySQL;DB_CLOSE_DELAY=-1", "sa", "");
        jdbcTemplate = new JdbcTemplate(dataSource);
        createTables();

        // 使用 MP 配置注册 BaseMapper，确保真实执行自动注入的 selectList、selectCount 和 update。
        TableInfoHelper.remove(WorkAuditTaskEntity.class);
        MybatisConfiguration configuration = new MybatisConfiguration();
        configuration.setMapUnderscoreToCamelCase(true);
        configuration.setEnvironment(new Environment(
                "test", new JdbcTransactionFactory(), dataSource));
        configuration.addMapper(WorkAuditTaskMapper.class);
        assertThat(TableInfoHelper.getTableInfo(WorkAuditTaskEntity.class)).isNotNull();
        assertThat(TableInfoHelper.getTableInfo(WorkAuditTaskEntity.class).getConfiguration())
                .isSameAs(configuration);
        SqlSessionFactory sessionFactory = new MybatisSqlSessionFactoryBuilder().build(configuration);
        sqlSession = sessionFactory.openSession(true);
        repository = new WorkAuditTaskRepository(sqlSession.getMapper(WorkAuditTaskMapper.class));
    }

    @AfterEach
    void tearDown() {
        sqlSession.close();
    }

    @Test
    void submissionRecoverySqlShouldExecuteAndRejectMismatchedWorks() {
        insertWork(11L, "AUDITING", 1, 0L);
        insertWork(12L, "AUDITING", 2, 0L);
        insertWork(13L, "PASSED", 1, 0L);
        insertWork(14L, "AUDITING", 1, 14L);
        insertWork(15L, "AUDITING", 1, 0L);
        insertWork(16L, "AUDITING", 1, 0L);
        insertVideoTask(101L, 11L, "SUBMITTING", 1, 0, NOW.minusMinutes(1), 1);
        insertVideoTask(102L, 12L, "SUBMITTING", 1, 0, NOW.minusMinutes(1), 1);
        insertVideoTask(103L, 13L, "SUBMITTING", 1, 0, NOW.minusMinutes(1), 1);
        insertVideoTask(104L, 14L, "SUBMITTING", 1, 0, NOW.minusMinutes(1), 1);
        insertVideoTask(105L, 15L, "SUBMITTING", 3, 0, NOW.minusMinutes(1), 1);
        insertVideoTask(106L, 16L, "SUBMITTING", 1, 0, NOW.plusMinutes(1), 1);

        assertThat(repository.findRetryableExpiredVideoSubmitTasks(20, 3, NOW))
                .extracting(WorkAuditTaskEntity::getId)
                .containsExactly(101L);
        assertThat(repository.findExhaustedExpiredVideoSubmitTasks(20, 3, NOW))
                .extracting(WorkAuditTaskEntity::getId)
                .containsExactly(105L);

        assertThat(repository.claimExpiredVideoSubmit(
                101L, "retry-token", NOW, NOW.plusMinutes(5), 3)).isTrue();
        assertThat(taskValue(101L, "attempt_count", Integer.class)).isEqualTo(2);
        assertThat(taskValue(101L, "locked_by", String.class)).isEqualTo("retry-token");
        assertThat(taskValue(101L, "version", Integer.class)).isEqualTo(1);

        assertThat(repository.claimExhaustedExpiredVideoSubmit(
                105L, "terminal-token", NOW, NOW.plusMinutes(5), 3)).isTrue();
        assertThat(taskValue(105L, "attempt_count", Integer.class)).isEqualTo(3);
        assertThat(taskValue(105L, "locked_by", String.class)).isEqualTo("terminal-token");
        assertThat(taskValue(105L, "version", Integer.class)).isEqualTo(1);

        assertThat(repository.claimExpiredVideoSubmit(
                102L, "invalid-token", NOW, NOW.plusMinutes(5), 3)).isFalse();
        assertThat(repository.claimExpiredVideoSubmit(
                103L, "invalid-token", NOW, NOW.plusMinutes(5), 3)).isFalse();
        assertThat(repository.claimExpiredVideoSubmit(
                104L, "invalid-token", NOW, NOW.plusMinutes(5), 3)).isFalse();
        assertThat(repository.claimExpiredVideoSubmit(
                106L, "invalid-token", NOW, NOW.plusMinutes(5), 3)).isFalse();
    }

    @Test
    void queryRecoveryCandidateCountAndClaimSqlShouldStayEquivalent() {
        for (long workId = 21L; workId <= 28L; workId++) {
            insertWork(workId, "AUDITING", 1, 0L);
        }
        jdbcTemplate.update("UPDATE wf_work SET audit_round = 2 WHERE id = 26");
        jdbcTemplate.update("UPDATE wf_work SET audit_status = 'PASSED' WHERE id = 27");
        jdbcTemplate.update("UPDATE wf_work SET deleted = 28 WHERE id = 28");
        insertVideoTask(201L, 21L, "SUBMITTED", 1, 2, null, 1);
        insertVideoTask(202L, 22L, "RUNNING", 1, 3, NOW.minusMinutes(1), 1);
        insertVideoTask(203L, 23L, "QUERYING", 1, 4, NOW.minusMinutes(1), 1);
        insertVideoTask(204L, 24L, "QUERYING", 1, 4, NOW.plusMinutes(1), 1);
        insertVideoTask(205L, 25L, "QUERYING", 1, 120, NOW.minusMinutes(1), 1);
        insertVideoTask(206L, 26L, "RUNNING", 1, 2, null, 1);
        insertVideoTask(207L, 27L, "QUERYING", 1, 2, NOW.minusMinutes(1), 1);
        insertVideoTask(208L, 28L, "SUBMITTED", 1, 2, null, 1);

        List<Long> queryableIds = repository.findQueryableVideoTasks(20, 120, NOW).stream()
                .map(WorkAuditTaskEntity::getId)
                .toList();
        assertThat(queryableIds).containsExactly(201L, 202L, 203L);
        assertThat(repository.countQueryableVideoTasks(120, NOW)).isEqualTo(queryableIds.size());

        assertThat(repository.claimVideoQuery(
                203L, "query-token", NOW, NOW.plusMinutes(5), 120)).isTrue();
        assertThat(taskValue(203L, "query_count", Integer.class)).isEqualTo(5);
        assertThat(taskValue(203L, "last_query_at", Timestamp.class).toLocalDateTime()).isEqualTo(NOW);
        assertThat(taskValue(203L, "locked_by", String.class)).isEqualTo("query-token");

        assertThat(repository.findExhaustedExpiredVideoQueryTasks(20, 120, NOW))
                .extracting(WorkAuditTaskEntity::getId)
                .containsExactly(205L);
        assertThat(repository.claimExhaustedExpiredVideoQuery(
                205L, "terminal-query-token", NOW, NOW.plusMinutes(5), 120)).isTrue();
        assertThat(taskValue(205L, "query_count", Integer.class)).isEqualTo(120);
        assertThat(taskValue(205L, "locked_by", String.class)).isEqualTo("terminal-query-token");
        assertThat(taskValue(205L, "version", Integer.class)).isEqualTo(1);

        assertThat(repository.claimVideoQuery(
                204L, "invalid-token", NOW, NOW.plusMinutes(5), 120)).isFalse();
        assertThat(repository.claimVideoQuery(
                206L, "invalid-token", NOW, NOW.plusMinutes(5), 120)).isFalse();
        assertThat(repository.claimVideoQuery(
                207L, "invalid-token", NOW, NOW.plusMinutes(5), 120)).isFalse();
        assertThat(repository.claimVideoQuery(
                208L, "invalid-token", NOW, NOW.plusMinutes(5), 120)).isFalse();
    }

    private void createTables() {
        jdbcTemplate.execute("""
                CREATE TABLE wf_work (
                  id BIGINT PRIMARY KEY,
                  audit_status VARCHAR(32) NOT NULL,
                  audit_round INT NOT NULL,
                  deleted BIGINT NOT NULL
                )
                """);
        jdbcTemplate.execute("""
                CREATE TABLE wf_work_audit_task (
                  id BIGINT AUTO_INCREMENT PRIMARY KEY,
                  work_id BIGINT NOT NULL,
                  user_id BIGINT NOT NULL,
                  media_type VARCHAR(16) NOT NULL,
                  media_object_key VARCHAR(512) NOT NULL,
                  media_sha256 VARCHAR(64),
                  audit_round INT NOT NULL,
                  provider VARCHAR(32) NOT NULL,
                  task_status VARCHAR(32) NOT NULL,
                  audit_result VARCHAR(32) NOT NULL,
                  ci_job_id VARCHAR(128),
                  ci_state VARCHAR(64),
                  ci_result INT,
                  ci_label VARCHAR(128),
                  ci_score INT,
                  snapshot_interval_seconds INT,
                  snapshot_count INT,
                  sampled_frame_numbers VARCHAR(1000),
                  attempt_count INT NOT NULL,
                  query_count INT NOT NULL,
                  last_query_at TIMESTAMP,
                  locked_by VARCHAR(128),
                  locked_until TIMESTAMP,
                  started_at TIMESTAMP,
                  submitted_at TIMESTAMP,
                  finished_at TIMESTAMP,
                  last_error_message VARCHAR(1000),
                  request_payload VARCHAR(4000),
                  response_payload VARCHAR(4000),
                  created_at TIMESTAMP NOT NULL,
                  updated_at TIMESTAMP NOT NULL,
                  deleted BIGINT NOT NULL,
                  version INT NOT NULL
                )
                """);
    }

    private void insertWork(Long id, String auditStatus, int auditRound, Long deleted) {
        jdbcTemplate.update(
                "INSERT INTO wf_work(id, audit_status, audit_round, deleted) VALUES (?, ?, ?, ?)",
                id, auditStatus, auditRound, deleted);
    }

    private void insertVideoTask(Long id, Long workId, String taskStatus, int attemptCount,
                                 int queryCount, LocalDateTime lockedUntil, int auditRound) {
        jdbcTemplate.update("""
                        INSERT INTO wf_work_audit_task(
                          id, work_id, user_id, media_type, media_object_key, audit_round,
                          provider, task_status, audit_result, attempt_count, query_count,
                          locked_until, created_at, updated_at, deleted, version
                        ) VALUES (?, ?, 1, 'VIDEO', 'video/test.mp4', ?,
                                  'TENCENT_CI', ?, 'UNKNOWN', ?, ?, ?, ?, ?, 0, 0)
                        """,
                id, workId, auditRound, taskStatus, attemptCount, queryCount,
                lockedUntil, NOW.minusHours(1), NOW.minusHours(1));
    }

    private <T> T taskValue(Long taskId, String column, Class<T> valueType) {
        return jdbcTemplate.queryForObject(
                "SELECT " + column + " FROM wf_work_audit_task WHERE id = ?", valueType, taskId);
    }
}
