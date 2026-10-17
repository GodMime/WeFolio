package com.jxc.wefolio.job.repo;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import com.jxc.wefolio.job.dict.WorkAuditTaskStatusDict;
import com.jxc.wefolio.job.dict.WorkAuditStatusDict;
import com.jxc.wefolio.service.portfolioai.PortfolioAiMediaContract;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Objects;
import java.time.LocalDateTime;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** 视频帧回收保护：先证明审核任务不可再发布，再检查当前作品的持久清单引用。 */
@Repository
@RequiredArgsConstructor
public class VideoFrameOrphanRepository {
    /** 清单对象身份字段，不读取签名链接。 */
    private static final String OBJECT_PREFIX = "objectPrefix";
    private static final String FRAMES = "frames";
    private static final String OBJECT_KEY = "objectKey";
    private static final String SCHEMA_VERSION = "schemaVersion";
    private static final String FRAME_COUNT = "frameCount";
    /** 完整清单目录应属于当前作品，未知目录或空帧结构不等价于可安全删除。 */
    private static final Pattern MANIFEST_PREFIX = Pattern.compile("[A-Za-z0-9_-]+/others/video-frames/([1-9][0-9]*)/"
            + "[a-f0-9]{64}/[A-Za-z0-9_-]{1,96}/[A-Za-z0-9_-]{1,96}/");
    /** 使用原始表读取，包括逻辑删除任务，不能把仍活跃的软删任务视为不存在。 */
    private static final String FIND_TASK = "SELECT work_id, task_status, media_object_key, snapshot_source_version, "
            + "snapshot_policy_version FROM wf_work_audit_task WHERE id = ?";
    private static final String FIND_MANIFEST = "SELECT video_frames_json FROM wf_work WHERE id = ?";
    /** 作品删除、换轮次、换源或人工审核后，过期旧任务只终结自身，永不更新当前作品。 */
    private static final String RETIRE_STALE_TASK = """
            UPDATE wf_work_audit_task SET task_status = ?, last_error_message = ?, finished_at = ?,
              locked_by = NULL, locked_until = NULL, updated_at = ?, version = version + 1
            WHERE id = ? AND work_id = ? AND snapshot_source_version = ? AND snapshot_policy_version = ?
              AND task_status IN (?, ?, ?, ?, ?)
              AND (locked_until < ? OR (locked_until IS NULL AND task_status IN (?, ?, ?)))
              AND NOT EXISTS (SELECT 1 FROM wf_work work
                WHERE work.id = wf_work_audit_task.work_id AND work.deleted = 0
                  AND work.audit_round = wf_work_audit_task.audit_round AND work.audit_status = ?
                  AND work.manual_audit_no IS NULL AND work.media_object_key = wf_work_audit_task.media_object_key
                  AND (work.media_sha256 = wf_work_audit_task.media_sha256
                    OR (work.media_sha256 IS NULL AND wf_work_audit_task.media_sha256 IS NULL)))
            """;
    private static final String STALE_TASK_REASON = "FRAME_CLEANUP: 旧审核任务已无可发布作品，回收过期无引用帧";
    /** 每次查询使用当前数据库状态；数据库异常向上抛出以保留对象和扫描游标。 */
    private final JdbcTemplate jdbc;

    /**
     * 终态事务会同时发布任务和作品清单，因此必须先读终态再读清单。
     * 不跨远端请求持有数据库锁；已终态任务不能再次领取或发布原目录。
     * 已确认缺失的任务无法执行发布 CAS；数据库查询未知或身份不一致时保守保留。
     */
    public boolean canDeleteAuditFrame(long taskId, long workId, String sourceVersion,
            String policyVersion, String owner, String prefix) {
        List<AuditIdentity> tasks = jdbc.query(FIND_TASK, (row, index) -> new AuditIdentity(row.getLong(1),
                row.getString(2), row.getString(3), row.getString(4), row.getString(5)), taskId);
        if (tasks.isEmpty()) { return isUnreferenced(workId, prefix); }
        if (tasks.size() != 1) { return false; }
        AuditIdentity task = tasks.getFirst();
        if (task.workId() != workId || !Objects.equals(task.sourceVersion(), sourceVersion)
                || !Objects.equals(task.policyVersion(), policyVersion) || task.sourceKey() == null
                || !task.sourceKey().startsWith(owner + "/work/video/")) { return false; }
        if (!isTerminal(task.status())) {
            // 使用审核任务写入租约的同一本机时钟口径，年龄筛选已独立采用云端 Instant。
            LocalDateTime now = LocalDateTime.now();
            int retired = jdbc.update(RETIRE_STALE_TASK, WorkAuditTaskStatusDict.FAILED.getCode(), STALE_TASK_REASON, now, now,
                    taskId, workId, sourceVersion, policyVersion, WorkAuditTaskStatusDict.PENDING.getCode(),
                    WorkAuditTaskStatusDict.SUBMITTING.getCode(), WorkAuditTaskStatusDict.SUBMITTED.getCode(),
                    WorkAuditTaskStatusDict.RUNNING.getCode(), WorkAuditTaskStatusDict.QUERYING.getCode(), now,
                    WorkAuditTaskStatusDict.PENDING.getCode(), WorkAuditTaskStatusDict.SUBMITTED.getCode(),
                    WorkAuditTaskStatusDict.RUNNING.getCode(),
                    WorkAuditStatusDict.AUDITING.getCode());
            if (retired != 1) { return false; }
        }
        return isUnreferenced(workId, prefix);
    }

    /** 已终态任务不能被查询处理者再次发布。 */
    private boolean isTerminal(String status) {
        return WorkAuditTaskStatusDict.SUCCESS.getCode().equals(status)
                || WorkAuditTaskStatusDict.FAILED.getCode().equals(status);
    }

    /** 历史单件期限至多五分钟，年龄兜底持有同一执行锁后只需核实当前作品引用。 */
    public boolean isUnreferenced(long workId, String prefix) {
        List<String> manifests = jdbc.query(FIND_MANIFEST, (row, index) -> row.getString(1), workId);
        if (manifests.isEmpty()) { return true; }
        if (manifests.size() != 1) { return false; }
        String json = manifests.getFirst();
        if (json == null || json.isBlank()) { return true; }
        try {
            JSONObject manifest = JSON.parseObject(json);
            if (manifest == null || !(manifest.get(OBJECT_PREFIX) instanceof String currentPrefix)
                    || !(manifest.get(FRAMES) instanceof JSONArray frames)) { return false; }
            if (prefix.equals(currentPrefix)) { return false; }
            for (Object value : frames) {
                if (!(value instanceof JSONObject frame)
                        || !(frame.get(OBJECT_KEY) instanceof String key)) { return false; }
                if (key.startsWith(prefix)) { return false; }
                if (!key.startsWith(currentPrefix) || key.substring(currentPrefix.length()).contains("/")) { return false; }
            }
            Matcher current = MANIFEST_PREFIX.matcher(currentPrefix);
            Integer count = manifest.getInteger(FRAME_COUNT);
            return PortfolioAiMediaContract.FRAME_SCHEMA.equals(manifest.getString(SCHEMA_VERSION))
                    && count != null && PortfolioAiMediaContract.isSupportedFrameCount(count) && frames.size() == count
                    && current.matches() && Long.parseLong(current.group(1)) == workId;
        } catch (RuntimeException exception) { return false; }
    }

    /** 只读取回收所需的冻结任务身份。 */
    private record AuditIdentity(long workId, String status, String sourceKey,
            String sourceVersion, String policyVersion) { }
}
