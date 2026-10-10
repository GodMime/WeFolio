package com.jxc.wefolio.job.repo;

import com.jxc.wefolio.job.entity.AiGenerationTaskEntity;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.time.LocalDateTime;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.Executors;

import static org.assertj.core.api.Assertions.assertThat;

/** 用真实 H2 条件更新验证领取竞态、旧代次、取消、数据库截止及隐私物理清理。 */
class AiGenerationTaskRepositoryTest {
    /** 独立测试库。 */ private JdbcTemplate jdbc;
    /** 被测任务仓储。 */ private AiGenerationTaskRepository repository;

    @BeforeEach
    void prepare() {
        var dataSource = new DriverManagerDataSource("jdbc:h2:mem:ai-" + UUID.randomUUID() + ";MODE=MySQL;DB_CLOSE_DELAY=-1", "sa", "");
        jdbc = new JdbcTemplate(dataSource); repository = new AiGenerationTaskRepository(jdbc);
        jdbc.execute("""
                CREATE TABLE wf_ai_generation_task(id BIGINT PRIMARY KEY AUTO_INCREMENT,owner_type VARCHAR(32),owner_id BIGINT,
                portfolio_id BIGINT,requested_by BIGINT,idempotency_key VARCHAR(64),request_json CLOB,result_json CLOB,status VARCHAR(32),
                attempt_count INT,next_run_at TIMESTAMP(3),started_at TIMESTAMP(3),completed_at TIMESTAMP(3),error_code VARCHAR(64),
                error_message VARCHAR(500),created_at TIMESTAMP(3),updated_at TIMESTAMP(3),deleted BIGINT DEFAULT 0,version INT DEFAULT 0)
                """);
    }

    /** 构造可直接领取的一条任务。 */
    private AiGenerationTaskEntity pending(LocalDateTime created) {
        jdbc.update("""
                INSERT INTO wf_ai_generation_task(owner_type,owner_id,requested_by,idempotency_key,request_json,status,
                attempt_count,next_run_at,created_at,updated_at) VALUES('USER',1,1,'k','{}','PENDING',0,?,?,?)
                """, created, created, created);
        return repository.findPending(LocalDateTime.now().plusSeconds(1), 10).getFirst();
    }

    @Test
    void concurrentClaimsConsumeOnlyOneAttemptAndVersion() throws Exception {
        LocalDateTime now = LocalDateTime.now().truncatedTo(java.time.temporal.ChronoUnit.MILLIS); pending(now.minusSeconds(1));
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

    @Test
    void cancelledAndRecoveredExecutionCannotWriteLateSuccessRetryOrFailure() {
        LocalDateTime now = LocalDateTime.now().truncatedTo(java.time.temporal.ChronoUnit.MILLIS); AiGenerationTaskEntity task = pending(now.minusSeconds(1));
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
        LocalDateTime now = LocalDateTime.now().truncatedTo(java.time.temporal.ChronoUnit.MILLIS); AiGenerationTaskEntity task = pending(now.minusSeconds(1));
        assertThat(repository.claim(task, now, 3, 180)).isTrue();
        jdbc.update("UPDATE wf_ai_generation_task SET created_at=?", now.minusSeconds(181));
        assertThat(repository.succeed(task, "{}", now.minusMinutes(1), 180)).isFalse();
        assertThat(repository.retry(task, "REMOTE_ERROR", "固定摘要", now.minusMinutes(1), 180)).isFalse();
        assertThat(repository.fail(task, "TIMEOUT", "固定摘要", now)).isTrue();
    }

    @Test
    void sharedBudgetUsesFiveThenTenSecondsAndClaimCannotExceedThreeAttempts() {
        LocalDateTime now = LocalDateTime.now().truncatedTo(java.time.temporal.ChronoUnit.MILLIS); AiGenerationTaskEntity task = pending(now.minusSeconds(1));
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
        LocalDateTime now = LocalDateTime.now().truncatedTo(java.time.temporal.ChronoUnit.MILLIS); pending(now);
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
}
