package com.jxc.wefolio.job.repo;

import com.jxc.wefolio.job.entity.AiGenerationTaskEntity;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Set;

/** AI 任务的短 SQL/CAS 仓储；不在数据库事务中等待模型。 */
@Repository
@RequiredArgsConstructor
@Slf4j
public class AiGenerationTaskRepository {
    /** 数据库操作入口。 */
    private final JdbcTemplate jdbc;
    /** 在同一次写入中使用数据库当前时间校验总期限，锁等待也不能延长期限。 */
    private static final String LIVE_DEADLINE = " AND TIMESTAMPADD(SECOND, ?, created_at) > " + PortfolioAiDatabaseTime.NOW_SQL;
    /** 媒体收尾必须在同一 SQL 重验租约，Java 检查之后的进程暂停也不能发布或重试。 */
    private static final String LIVE_MEDIA_LEASE = " AND updated_at>TIMESTAMPADD(SECOND, ?, " + PortfolioAiDatabaseTime.NOW_SQL + ")";
    /** 清理仅允许终态任务；空对象代表隐私原文已经销毁。 */
    private static final Set<String> TERMINAL = Set.of("SUCCEEDED", "FAILED", "CANCELLED");
    private static final String CLEARED_REQUEST = "{}";
    /** 完整任务读取，物理清理显式包含逻辑删除行。 */
    private static final RowMapper<AiGenerationTaskEntity> ROW = (rs, row) -> {
        AiGenerationTaskEntity task = new AiGenerationTaskEntity();
        task.setId(rs.getLong("id")); task.setOwnerType(rs.getString("owner_type")); task.setOwnerId(rs.getLong("owner_id"));
        task.setPortfolioId(rs.getObject("portfolio_id", Long.class)); task.setRequestedBy(rs.getLong("requested_by"));
        task.setIdempotencyKey(rs.getString("idempotency_key")); task.setRequestJson(rs.getString("request_json"));
        task.setResultJson(rs.getString("result_json")); task.setStatus(rs.getString("status"));
        task.setMediaBudgetJson(rs.getString("media_budget_json"));
        task.setMediaBudgetUnknownCount(rs.getInt("media_budget_unknown_count"));
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
                AND (created_at <= ? OR (status='RUNNING' AND started_at <= ? AND updated_at <= ?)) ORDER BY id LIMIT ?
                """, ROW, now.minusSeconds(timeout), now.minusSeconds(recovery), now.minusSeconds(recovery), limit);
    }

    /** 当前媒体代次独立续约，数据库时钟与租约边界防止陈旧 worker 复活，不改业务版本。 */
    public boolean renewMediaLease(AiGenerationTaskEntity task, int recovery, int timeout) {
        return jdbc.update("""
                UPDATE wf_ai_generation_task SET updated_at=%s
                WHERE id=? AND status='RUNNING' AND version=? AND deleted=0
                AND updated_at>TIMESTAMPADD(SECOND, ?, %s)
                """.formatted(PortfolioAiDatabaseTime.NOW_SQL, PortfolioAiDatabaseTime.NOW_SQL) + LIVE_DEADLINE,
                task.getId(), task.getVersion(), -recovery, timeout) == 1;
    }

    /** 媒体陈旧租约直接结束，重验心跳以拒绝扫描后的竞争续约，不回队列或重复付费。 */
    public boolean failInterruptedMedia(AiGenerationTaskEntity task, LocalDateTime now, int recovery, int timeout) {
        return jdbc.update("""
                UPDATE wf_ai_generation_task SET status='FAILED',result_json=NULL,error_code='EXECUTION_INTERRUPTED',
                error_message='媒体执行中断，现有内容已保留，请重新发起',completed_at=?,updated_at=?,version=version+1
                WHERE id=? AND status='RUNNING' AND version=? AND deleted=0
                AND updated_at<=TIMESTAMPADD(SECOND, ?, %s)
                """.formatted(PortfolioAiDatabaseTime.NOW_SQL) + LIVE_DEADLINE,
                now, now, task.getId(), task.getVersion(), -recovery, timeout) == 1;
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

    /** 媒体结果的最后 CAS 同时要求租约有效，陈旧代次不能在回收扫描前抢先发布。 */
    public boolean succeedMedia(AiGenerationTaskEntity task, String result, LocalDateTime now, int timeout, int recovery) {
        return jdbc.update("""
                UPDATE wf_ai_generation_task SET status='SUCCEEDED',result_json=?,completed_at=?,updated_at=?,
                error_code=NULL,error_message=NULL,version=version+1 WHERE id=? AND status='RUNNING'
                AND version=? AND deleted=0
                """ + LIVE_DEADLINE + LIVE_MEDIA_LEASE, result, now, now, task.getId(), task.getVersion(), timeout, -recovery) == 1;
    }

    /** 在有效执行代次和总期限内按统一预算回到排队状态。 */
    public boolean retry(AiGenerationTaskEntity task, String code, String message, LocalDateTime now, int timeout) {
        return jdbc.update("""
                UPDATE wf_ai_generation_task SET status='PENDING',result_json=NULL,error_code=?,error_message=?,
                next_run_at=?,updated_at=?,version=version+1 WHERE id=? AND status='RUNNING' AND version=? AND deleted=0
                """ + LIVE_DEADLINE, code, message, now.plusSeconds(task.getAttemptCount() == 1 ? 5 : 10), now,
                task.getId(), task.getVersion(), timeout) == 1;
    }

    /** 仅正常持权媒体的可重试调用错误可回队列，已过租约或进程中断不能触发新的付费代次。 */
    public boolean retryMedia(AiGenerationTaskEntity task, String code, String message, LocalDateTime now, int timeout, int recovery) {
        return jdbc.update("""
                UPDATE wf_ai_generation_task SET status='PENDING',result_json=NULL,error_code=?,error_message=?,
                next_run_at=?,updated_at=?,version=version+1 WHERE id=? AND status='RUNNING' AND version=? AND deleted=0
                """ + LIVE_DEADLINE + LIVE_MEDIA_LEASE, code, message, now.plusSeconds(task.getAttemptCount() == 1 ? 5 : 10), now,
                task.getId(), task.getVersion(), timeout, -recovery) == 1;
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

    /**
     * 已知费用到期物理删除；未知费用保留最小隐藏记录并销毁请求、结果与错误原文。
     * 与结算共用任务行锁，锁后重验实际 JSON，不能按清理候选时的未知计数误删。
     */
    @Transactional(rollbackFor = Exception.class)
    public int deleteExpired(LocalDateTime cutoff, int limit) {
        List<Long> ids = jdbc.queryForList("""
                SELECT id FROM wf_ai_generation_task WHERE status IN ('SUCCEEDED','FAILED','CANCELLED')
                AND completed_at<=? AND (media_budget_unknown_count=0 OR deleted<>id
                    OR CHAR_LENGTH(request_json)>2 OR result_json IS NOT NULL OR error_message IS NOT NULL)
                ORDER BY id LIMIT ?
                """, Long.class, cutoff, limit);
        int processed = 0;
        for (Long id : ids) {
            List<CleanupRow> rows = jdbc.query("""
                    SELECT status,completed_at,media_budget_json,media_budget_unknown_count,deleted,
                    request_json,result_json,error_message FROM wf_ai_generation_task WHERE id=? FOR UPDATE
                    """, (rs, n) -> new CleanupRow(rs.getString(1), rs.getObject(2, LocalDateTime.class), rs.getString(3),
                            rs.getInt(4), rs.getLong(5), rs.getString(6), rs.getString(7), rs.getString(8)), id);
            if (rows.isEmpty()) { continue; }
            CleanupRow row = rows.getFirst();
            if (!TERMINAL.contains(row.status()) || row.completed() == null || row.completed().isAfter(cutoff)) { continue; }
            int unknown;
            try {
                unknown = PortfolioAiMediaBudget.parse(row.budget()).unknownCount();
                if (unknown != row.unknown()) { throw new IllegalStateException("媒体费用未知计数不一致"); }
            } catch (RuntimeException exception) {
                // 账本损坏时仍销毁隐私，但保守保留原费用 JSON，不能将不明费用视为零。
                unknown = Math.max(1, row.unknown());
                log.error("到期 AI 任务费用无法确认，清隐私并保留费用: taskId={}, exceptionType={}", id, exception.getClass().getSimpleName());
            }
            if (unknown == 0) {
                processed += jdbc.update("DELETE FROM wf_ai_generation_task WHERE id=?", id);
            } else if (row.deleted() != id || !CLEARED_REQUEST.equals(row.request())
                    || row.result() != null || row.error() != null || row.unknown() != unknown) {
                processed += jdbc.update("""
                        UPDATE wf_ai_generation_task SET request_json=?,result_json=NULL,error_message=NULL,
                        media_budget_unknown_count=?,deleted=id,version=version+1,updated_at=%s
                        WHERE id=?
                        """.formatted(PortfolioAiDatabaseTime.NOW_SQL), CLEARED_REQUEST, unknown, id);
            }
        }
        return processed;
    }

    /** 已锁定终态任务的隐私与费用字段。 */
    private record CleanupRow(String status, LocalDateTime completed, String budget, int unknown,
            long deleted, String request, String result, String error) { }
}
