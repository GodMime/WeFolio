package com.jxc.wefolio.job.repo;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.jxc.wefolio.job.dict.AuditResultDict;
import com.jxc.wefolio.job.dict.MediaTypeDict;
import com.jxc.wefolio.job.dict.WorkAuditTaskStatusDict;
import com.jxc.wefolio.job.entity.WorkAuditTaskEntity;
import com.jxc.wefolio.job.mapper.WorkAuditTaskMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 作品审核任务仓储。
 */
@Repository
@RequiredArgsConstructor
public class WorkAuditTaskRepository {

    /** 逻辑未删除值 */
    private static final long NOT_DELETED = 0L;

    /** LIMIT 子句前缀 */
    private static final String LIMIT_SQL_PREFIX = "LIMIT ";

    /** 单行 LIMIT 子句 */
    private static final String LIMIT_ONE_SQL = "LIMIT 1";

    /** 主动查询次数递增 SQL */
    private static final String QUERY_COUNT_INCREMENT_SQL = "query_count = query_count + 1";

    /** 乐观锁版本递增 SQL */
    private static final String VERSION_INCREMENT_SQL = "version = version + 1";

    /** 逻辑删除赋值 SQL */
    private static final String LOGIC_DELETE_SQL = "deleted = id";

    /** 可进入审核终态的处理中状态 */
    private static final List<String> FINISHABLE_TASK_STATUSES = List.of(
            WorkAuditTaskStatusDict.SUBMITTING.getCode(),
            WorkAuditTaskStatusDict.QUERYING.getCode()
    );

    private final WorkAuditTaskMapper taskMapper;

    /**
     * 新增审核任务。
     *
     * @param task 审核任务
     * @return 新增后的任务
     */
    public WorkAuditTaskEntity insertTask(WorkAuditTaskEntity task) {
        taskMapper.insert(task);
        return task;
    }

    /**
     * 查询可主动查询结果的视频任务。
     *
     * @param limit 查询数量上限
     * @param maxAttempts 最大查询次数
     * @return 可查询的视频任务
     */
    public List<WorkAuditTaskEntity> findQueryableVideoTasks(int limit, int maxAttempts) {
        return taskMapper.selectList(Wrappers.<WorkAuditTaskEntity>lambdaQuery()
                .eq(WorkAuditTaskEntity::getMediaType, MediaTypeDict.VIDEO.getCode())
                .in(WorkAuditTaskEntity::getTaskStatus,
                        WorkAuditTaskStatusDict.SUBMITTED.getCode(),
                        WorkAuditTaskStatusDict.RUNNING.getCode())
                .lt(WorkAuditTaskEntity::getQueryCount, maxAttempts)
                .eq(WorkAuditTaskEntity::getDeleted, NOT_DELETED)
                .orderByAsc(WorkAuditTaskEntity::getId)
                // limit 已归一化为非负整数，拼接 LIMIT 子句不会引入 SQL 注入风险。
                .last(LIMIT_SQL_PREFIX + normalizedLimit(limit)));
    }

    /**
     * 统计可主动查询结果的视频审核任务数量。
     *
     * @param maxAttempts 最大查询次数
     * @return 可查询的视频审核任务数量
     */
    public long countQueryableVideoTasks(int maxAttempts) {
        return taskMapper.selectCount(Wrappers.<WorkAuditTaskEntity>lambdaQuery()
                .eq(WorkAuditTaskEntity::getMediaType, MediaTypeDict.VIDEO.getCode())
                .in(WorkAuditTaskEntity::getTaskStatus,
                        WorkAuditTaskStatusDict.SUBMITTED.getCode(),
                        WorkAuditTaskStatusDict.RUNNING.getCode())
                .lt(WorkAuditTaskEntity::getQueryCount, maxAttempts)
                .eq(WorkAuditTaskEntity::getDeleted, NOT_DELETED));
    }

    /**
     * 将视频任务 claim 为查询中，并递增主动查询次数。
     *
     * @param taskId 任务 ID
     * @param lockOwner 锁持有者
     * @param lockedUntil 锁过期时间
     * @param maxAttempts 最大查询次数
     * @return 是否 claim 成功
     */
    public boolean claimVideoQuery(Long taskId, String lockOwner, LocalDateTime lockedUntil, int maxAttempts) {
        LocalDateTime now = LocalDateTime.now();
        int updated = taskMapper.update(null, Wrappers.<WorkAuditTaskEntity>lambdaUpdate()
                .set(WorkAuditTaskEntity::getTaskStatus, WorkAuditTaskStatusDict.QUERYING.getCode())
                .set(WorkAuditTaskEntity::getLockedBy, lockOwner)
                .set(WorkAuditTaskEntity::getLockedUntil, lockedUntil)
                .set(WorkAuditTaskEntity::getLastQueryAt, now)
                .set(WorkAuditTaskEntity::getUpdatedAt, now)
                .setSql(QUERY_COUNT_INCREMENT_SQL)
                .setSql(VERSION_INCREMENT_SQL)
                .eq(WorkAuditTaskEntity::getId, taskId)
                .eq(WorkAuditTaskEntity::getMediaType, MediaTypeDict.VIDEO.getCode())
                .in(WorkAuditTaskEntity::getTaskStatus,
                        WorkAuditTaskStatusDict.SUBMITTED.getCode(),
                        WorkAuditTaskStatusDict.RUNNING.getCode())
                .lt(WorkAuditTaskEntity::getQueryCount, maxAttempts)
                .eq(WorkAuditTaskEntity::getDeleted, NOT_DELETED)
                .and(wrapper -> wrapper
                        .isNull(WorkAuditTaskEntity::getLockedUntil)
                        .or()
                        .lt(WorkAuditTaskEntity::getLockedUntil, now)));
        return updated == 1;
    }

    /**
     * 视频提交成功后写入腾讯云任务 ID。
     *
     * @param taskId 任务 ID
     * @param ciJobId 腾讯云任务 ID
     * @param responsePayload 响应摘要
     * @return 是否更新成功
     */
    public boolean markVideoSubmitted(Long taskId, String ciJobId, String responsePayload) {
        LocalDateTime now = LocalDateTime.now();
        int updated = taskMapper.update(null, Wrappers.<WorkAuditTaskEntity>lambdaUpdate()
                .set(WorkAuditTaskEntity::getTaskStatus, WorkAuditTaskStatusDict.SUBMITTED.getCode())
                .set(WorkAuditTaskEntity::getCiJobId, ciJobId)
                .set(WorkAuditTaskEntity::getSubmittedAt, now)
                .set(WorkAuditTaskEntity::getResponsePayload, responsePayload)
                .set(WorkAuditTaskEntity::getLockedBy, null)
                .set(WorkAuditTaskEntity::getLockedUntil, null)
                .set(WorkAuditTaskEntity::getUpdatedAt, now)
                .setSql(VERSION_INCREMENT_SQL)
                .eq(WorkAuditTaskEntity::getId, taskId)
                .eq(WorkAuditTaskEntity::getTaskStatus, WorkAuditTaskStatusDict.SUBMITTING.getCode())
                .eq(WorkAuditTaskEntity::getDeleted, NOT_DELETED));
        return updated == 1;
    }

    /**
     * 将视频查询结果标记为仍在处理中。
     *
     * @param taskId 任务 ID
     * @param ciState 腾讯云状态
     * @param responsePayload 响应摘要
     * @return 是否更新成功
     */
    public boolean markVideoRunning(Long taskId, String ciState, String responsePayload) {
        LocalDateTime now = LocalDateTime.now();
        int updated = taskMapper.update(null, Wrappers.<WorkAuditTaskEntity>lambdaUpdate()
                .set(WorkAuditTaskEntity::getTaskStatus, WorkAuditTaskStatusDict.RUNNING.getCode())
                .set(WorkAuditTaskEntity::getCiState, ciState)
                .set(WorkAuditTaskEntity::getResponsePayload, responsePayload)
                .set(WorkAuditTaskEntity::getLockedBy, null)
                .set(WorkAuditTaskEntity::getLockedUntil, null)
                .set(WorkAuditTaskEntity::getUpdatedAt, now)
                .setSql(VERSION_INCREMENT_SQL)
                .eq(WorkAuditTaskEntity::getId, taskId)
                .eq(WorkAuditTaskEntity::getTaskStatus, WorkAuditTaskStatusDict.QUERYING.getCode())
                .eq(WorkAuditTaskEntity::getDeleted, NOT_DELETED));
        return updated == 1;
    }

    /**
     * 标记任务审核成功终态。
     *
     * @param taskId 任务 ID
     * @param result 审核结果
     * @param ciState 腾讯云状态
     * @param ciResult 腾讯云结果码
     * @param ciLabel 命中标签
     * @param ciScore 命中分数
     * @param responsePayload 响应摘要
     * @return 是否更新成功
     */
    public boolean markSuccess(Long taskId, AuditResultDict result, String ciState, Integer ciResult,
                               String ciLabel, Integer ciScore, String responsePayload) {
        LocalDateTime now = LocalDateTime.now();
        int updated = taskMapper.update(null, Wrappers.<WorkAuditTaskEntity>lambdaUpdate()
                .set(WorkAuditTaskEntity::getTaskStatus, WorkAuditTaskStatusDict.SUCCESS.getCode())
                .set(WorkAuditTaskEntity::getAuditResult, result.getCode())
                .set(WorkAuditTaskEntity::getCiState, ciState)
                .set(WorkAuditTaskEntity::getCiResult, ciResult)
                .set(WorkAuditTaskEntity::getCiLabel, ciLabel)
                .set(WorkAuditTaskEntity::getCiScore, ciScore)
                .set(WorkAuditTaskEntity::getResponsePayload, responsePayload)
                .set(WorkAuditTaskEntity::getFinishedAt, now)
                .set(WorkAuditTaskEntity::getLockedBy, null)
                .set(WorkAuditTaskEntity::getLockedUntil, null)
                .set(WorkAuditTaskEntity::getUpdatedAt, now)
                .setSql(VERSION_INCREMENT_SQL)
                .eq(WorkAuditTaskEntity::getId, taskId)
                .in(WorkAuditTaskEntity::getTaskStatus, FINISHABLE_TASK_STATUSES)
                .eq(WorkAuditTaskEntity::getDeleted, NOT_DELETED));
        return updated == 1;
    }

    /**
     * 记录查询失败并回到 RUNNING，等待下一轮查询。
     *
     * @param taskId 任务 ID
     * @param errorMessage 错误摘要
     * @param responsePayload 响应摘要
     * @return 是否更新成功
     */
    public boolean markQueryFailureForNextRun(Long taskId, String errorMessage, String responsePayload) {
        LocalDateTime now = LocalDateTime.now();
        int updated = taskMapper.update(null, Wrappers.<WorkAuditTaskEntity>lambdaUpdate()
                .set(WorkAuditTaskEntity::getTaskStatus, WorkAuditTaskStatusDict.RUNNING.getCode())
                .set(WorkAuditTaskEntity::getAuditResult, AuditResultDict.UNKNOWN.getCode())
                .set(WorkAuditTaskEntity::getLastErrorMessage, errorMessage)
                .set(WorkAuditTaskEntity::getResponsePayload, responsePayload)
                .set(WorkAuditTaskEntity::getLockedBy, null)
                .set(WorkAuditTaskEntity::getLockedUntil, null)
                .set(WorkAuditTaskEntity::getUpdatedAt, now)
                .setSql(VERSION_INCREMENT_SQL)
                .eq(WorkAuditTaskEntity::getId, taskId)
                .eq(WorkAuditTaskEntity::getTaskStatus, WorkAuditTaskStatusDict.QUERYING.getCode())
                .eq(WorkAuditTaskEntity::getDeleted, NOT_DELETED));
        return updated == 1;
    }

    /**
     * 标记任务失败终态。
     *
     * @param taskId 任务 ID
     * @param errorMessage 错误摘要
     * @param responsePayload 响应摘要
     * @return 是否更新成功
     */
    public boolean markFailed(Long taskId, String errorMessage, String responsePayload) {
        LocalDateTime now = LocalDateTime.now();
        int updated = taskMapper.update(null, Wrappers.<WorkAuditTaskEntity>lambdaUpdate()
                .set(WorkAuditTaskEntity::getTaskStatus, WorkAuditTaskStatusDict.FAILED.getCode())
                .set(WorkAuditTaskEntity::getAuditResult, AuditResultDict.UNKNOWN.getCode())
                .set(WorkAuditTaskEntity::getLastErrorMessage, errorMessage)
                .set(WorkAuditTaskEntity::getResponsePayload, responsePayload)
                .set(WorkAuditTaskEntity::getFinishedAt, now)
                .set(WorkAuditTaskEntity::getLockedBy, null)
                .set(WorkAuditTaskEntity::getLockedUntil, null)
                .set(WorkAuditTaskEntity::getUpdatedAt, now)
                .setSql(VERSION_INCREMENT_SQL)
                .eq(WorkAuditTaskEntity::getId, taskId)
                .in(WorkAuditTaskEntity::getTaskStatus, FINISHABLE_TASK_STATUSES)
                .eq(WorkAuditTaskEntity::getDeleted, NOT_DELETED));
        return updated == 1;
    }

    /**
     * 查询任务当前主动查询次数。
     *
     * @param taskId 任务 ID
     * @return 当前查询次数，任务不存在时返回 null
     */
    public Integer findQueryCountById(Long taskId) {
        WorkAuditTaskEntity task = taskMapper.selectOne(Wrappers.<WorkAuditTaskEntity>lambdaQuery()
                .select(WorkAuditTaskEntity::getQueryCount)
                .eq(WorkAuditTaskEntity::getId, taskId)
                .eq(WorkAuditTaskEntity::getDeleted, NOT_DELETED)
                .last(LIMIT_ONE_SQL));
        return task == null ? null : task.getQueryCount();
    }

    /**
     * 逻辑删除作品下失败的审核任务，供运维重置时使用。
     *
     * @param workId 作品 ID
     * @return 更新行数
     */
    public int logicDeleteFailedTasksByWorkId(Long workId) {
        LocalDateTime now = LocalDateTime.now();
        return taskMapper.update(null, Wrappers.<WorkAuditTaskEntity>lambdaUpdate()
                .set(WorkAuditTaskEntity::getUpdatedAt, now)
                .setSql(LOGIC_DELETE_SQL)
                .setSql(VERSION_INCREMENT_SQL)
                .eq(WorkAuditTaskEntity::getWorkId, workId)
                .eq(WorkAuditTaskEntity::getTaskStatus, WorkAuditTaskStatusDict.FAILED.getCode())
                .eq(WorkAuditTaskEntity::getDeleted, NOT_DELETED));
    }

    private int normalizedLimit(int limit) {
        return Math.max(limit, 0);
    }
}
