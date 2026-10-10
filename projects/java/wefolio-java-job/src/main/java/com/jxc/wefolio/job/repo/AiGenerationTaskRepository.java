package com.jxc.wefolio.job.repo;

import com.jxc.wefolio.job.entity.AiGenerationTaskEntity;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;

/** AI 任务的短 SQL/CAS 仓储；不在数据库事务中等待模型。 */
@Repository
@RequiredArgsConstructor
public class AiGenerationTaskRepository {
    /** 数据库操作入口。 */
    private final JdbcTemplate jdbc;
    /** 在同一次写入中使用数据库当前时间校验总期限，锁等待也不能延长期限。 */
    private static final String LIVE_DEADLINE = " AND TIMESTAMPADD(SECOND, ?, created_at) > CURRENT_TIMESTAMP(3)";
    /** 完整任务读取，物理清理显式包含逻辑删除行。 */
    private static final RowMapper<AiGenerationTaskEntity> ROW = (rs, row) -> {
        AiGenerationTaskEntity task = new AiGenerationTaskEntity();
        task.setId(rs.getLong("id")); task.setOwnerType(rs.getString("owner_type")); task.setOwnerId(rs.getLong("owner_id"));
        task.setPortfolioId(rs.getObject("portfolio_id", Long.class)); task.setRequestedBy(rs.getLong("requested_by"));
        task.setIdempotencyKey(rs.getString("idempotency_key")); task.setRequestJson(rs.getString("request_json"));
        task.setResultJson(rs.getString("result_json")); task.setStatus(rs.getString("status"));
        task.setAttemptCount(rs.getInt("attempt_count")); task.setVersion(rs.getInt("version")); task.setDeleted(rs.getLong("deleted"));
        task.setCreatedAt(rs.getObject("created_at", LocalDateTime.class)); task.setUpdatedAt(rs.getObject("updated_at", LocalDateTime.class));
        task.setNextRunAt(rs.getObject("next_run_at", LocalDateTime.class)); task.setStartedAt(rs.getObject("started_at", LocalDateTime.class));
        task.setCompletedAt(rs.getObject("completed_at", LocalDateTime.class)); task.setErrorCode(rs.getString("error_code"));
        task.setErrorMessage(rs.getString("error_message")); return task;
    };

    /** 读取一批需超时或中断恢复的活动记录。 */
    public List<AiGenerationTaskEntity> findRecoverable(LocalDateTime now, int timeout, int recovery, int limit) {
        return jdbc.query("""
                SELECT * FROM wf_ai_generation_task WHERE deleted=0 AND status IN ('PENDING','RUNNING')
                AND (created_at <= ? OR (status='RUNNING' AND started_at <= ?)) ORDER BY id LIMIT ?
                """, ROW, now.minusSeconds(timeout), now.minusSeconds(recovery), limit);
    }

    /** 读取可领取队列；最终领取仍以 version 防并发。 */
    public List<AiGenerationTaskEntity> findPending(LocalDateTime now, int limit) {
        return jdbc.query("SELECT * FROM wf_ai_generation_task WHERE deleted=0 AND status='PENDING' AND next_run_at<=? ORDER BY next_run_at,id LIMIT ?", ROW, now, limit);
    }

    /** CAS 领取并立即消耗尝试预算，数据库期限为最终准绳。 */
    public boolean claim(AiGenerationTaskEntity task, LocalDateTime now, int attempts, int timeout) {
        int changed = jdbc.update("""
                UPDATE wf_ai_generation_task SET status='RUNNING',attempt_count=attempt_count+1,
                version=version+1,started_at=?,updated_at=? WHERE id=? AND version=? AND status='PENDING'
                AND deleted=0 AND attempt_count<? AND next_run_at<=?
                """ + LIVE_DEADLINE, now, now, task.getId(), task.getVersion(), attempts, now, timeout);
        if (changed == 1) {
            task.setStatus("RUNNING"); task.setVersion(task.getVersion() + 1);
            task.setAttemptCount(task.getAttemptCount() + 1); task.setStartedAt(now); return true;
        }
        return false;
    }

    /** 仅本轮仍有效且未过期限时才能写合法结果；取消和旧 worker 都无法覆盖。 */
    public boolean succeed(AiGenerationTaskEntity task, String result, LocalDateTime now, int timeout) {
        return jdbc.update("""
                UPDATE wf_ai_generation_task SET status='SUCCEEDED',result_json=?,completed_at=?,updated_at=?,
                error_code=NULL,error_message=NULL,version=version+1 WHERE id=? AND status='RUNNING'
                AND version=? AND deleted=0
                """ + LIVE_DEADLINE, result, now, now, task.getId(), task.getVersion(), timeout) == 1;
    }

    /** 在有效执行代次和总期限内按统一预算回到排队状态。 */
    public boolean retry(AiGenerationTaskEntity task, String code, String message, LocalDateTime now, int timeout) {
        return jdbc.update("""
                UPDATE wf_ai_generation_task SET status='PENDING',result_json=NULL,error_code=?,error_message=?,
                next_run_at=?,updated_at=?,version=version+1 WHERE id=? AND status='RUNNING' AND version=? AND deleted=0
                """ + LIVE_DEADLINE, code, message, now.plusSeconds(task.getAttemptCount() == 1 ? 5 : 10), now,
                task.getId(), task.getVersion(), timeout) == 1;
    }

    /** 按读出的状态和版本终态化，恢复、总超时及执行失败共用同一条件。 */
    public boolean fail(AiGenerationTaskEntity task, String code, String message, LocalDateTime now) {
        return jdbc.update("""
                UPDATE wf_ai_generation_task SET status='FAILED',result_json=NULL,error_code=?,error_message=?,
                completed_at=?,updated_at=?,version=version+1 WHERE id=? AND status=? AND version=? AND deleted=0
                """, code, message, now, now, task.getId(), task.getStatus(), task.getVersion()) == 1;
    }

    /** 过期执行代次恢复为等待，旧 worker 的版本在同一写入中失效。 */
    public boolean recover(AiGenerationTaskEntity task, LocalDateTime now, int timeout) {
        return jdbc.update("""
                UPDATE wf_ai_generation_task SET status='PENDING',next_run_at=?,updated_at=?,error_code='EXECUTION_INTERRUPTED',
                error_message='执行中断，等待重试',version=version+1 WHERE id=? AND status='RUNNING' AND version=? AND deleted=0
                """ + LIVE_DEADLINE, now.plusSeconds(task.getAttemptCount() == 1 ? 5 : 10), now,
                task.getId(), task.getVersion(), timeout) == 1;
    }

    /** 每小时小批物理清理所有到期终态，含已逻辑删除记录，不归档隐私原文。 */
    public int deleteExpired(LocalDateTime cutoff, int limit) {
        List<Long> ids = jdbc.queryForList("""
                SELECT id FROM wf_ai_generation_task WHERE status IN ('SUCCEEDED','FAILED','CANCELLED')
                AND completed_at<=? ORDER BY id LIMIT ?
                """, Long.class, cutoff, limit);
        int deleted = 0;
        for (Long id : ids) {
            deleted += jdbc.update("DELETE FROM wf_ai_generation_task WHERE id=? AND status IN ('SUCCEEDED','FAILED','CANCELLED') AND completed_at<=?", id, cutoff);
        }
        return deleted;
    }
}
