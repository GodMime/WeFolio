package com.jxc.wefolio.job.repo;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.jxc.wefolio.job.dict.AuditResultDict;
import com.jxc.wefolio.job.dict.MediaTypeDict;
import com.jxc.wefolio.job.dict.WorkAuditStatusDict;
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

    /** 审核尝试次数递增 SQL */
    private static final String ATTEMPT_COUNT_INCREMENT_SQL = "attempt_count = attempt_count + 1";

    /** 乐观锁版本递增 SQL */
    private static final String VERSION_INCREMENT_SQL = "version = version + 1";

    /** 逻辑删除赋值 SQL */
    private static final String LOGIC_DELETE_SQL = "deleted = id";

    /** 任务仍对应当前有效审核作品的相关子查询 */
    private static final String ACTIVE_AUDITING_WORK_EXISTS_SQL = """
            EXISTS (
              SELECT 1
              FROM wf_work work
              WHERE work.id = wf_work_audit_task.work_id
                AND work.audit_round = wf_work_audit_task.audit_round
                AND work.audit_status = {0}
                AND work.manual_audit_no IS NULL
                AND work.deleted = {1}
            )
            """;

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
     * 查询可主动查询结果或查询租约已过期的视频任务。
     *
     * @param limit 查询数量上限
     * @param maxAttempts 最大查询次数
     * @param now 本轮统一时间
     * @return 可查询的视频任务
     */
    public List<WorkAuditTaskEntity> findQueryableVideoTasks(
            int limit, int maxAttempts, LocalDateTime now) {
        return taskMapper.selectList(Wrappers.<WorkAuditTaskEntity>lambdaQuery()
                .eq(WorkAuditTaskEntity::getMediaType, MediaTypeDict.VIDEO.getCode())
                .and(status -> status
                        .and(regular -> regular
                                .in(WorkAuditTaskEntity::getTaskStatus,
                                        WorkAuditTaskStatusDict.SUBMITTED.getCode(),
                                        WorkAuditTaskStatusDict.RUNNING.getCode())
                                .and(lease -> lease
                                        .isNull(WorkAuditTaskEntity::getLockedUntil)
                                        .or()
                                        .lt(WorkAuditTaskEntity::getLockedUntil, now)))
                        .or(expired -> expired
                                .eq(WorkAuditTaskEntity::getTaskStatus,
                                        WorkAuditTaskStatusDict.QUERYING.getCode())
                                .lt(WorkAuditTaskEntity::getLockedUntil, now)))
                .lt(WorkAuditTaskEntity::getQueryCount, maxAttempts)
                .eq(WorkAuditTaskEntity::getDeleted, NOT_DELETED)
                .apply(ACTIVE_AUDITING_WORK_EXISTS_SQL,
                        WorkAuditStatusDict.AUDITING.getCode(), NOT_DELETED)
                .orderByAsc(WorkAuditTaskEntity::getId)
                // limit 已归一化为非负整数，拼接 LIMIT 子句不会引入 SQL 注入风险。
                .last(LIMIT_SQL_PREFIX + normalizedLimit(limit)));
    }

    /**
     * 统计可主动查询结果或查询租约已过期的视频审核任务数量。
     *
     * @param maxAttempts 最大查询次数
     * @param now 本轮统一时间
     * @return 可查询的视频审核任务数量
     */
    public long countQueryableVideoTasks(int maxAttempts, LocalDateTime now) {
        return taskMapper.selectCount(Wrappers.<WorkAuditTaskEntity>lambdaQuery()
                .eq(WorkAuditTaskEntity::getMediaType, MediaTypeDict.VIDEO.getCode())
                .and(status -> status
                        .and(regular -> regular
                                .in(WorkAuditTaskEntity::getTaskStatus,
                                        WorkAuditTaskStatusDict.SUBMITTED.getCode(),
                                        WorkAuditTaskStatusDict.RUNNING.getCode())
                                .and(lease -> lease
                                        .isNull(WorkAuditTaskEntity::getLockedUntil)
                                        .or()
                                        .lt(WorkAuditTaskEntity::getLockedUntil, now)))
                        .or(expired -> expired
                                .eq(WorkAuditTaskEntity::getTaskStatus,
                                        WorkAuditTaskStatusDict.QUERYING.getCode())
                                .lt(WorkAuditTaskEntity::getLockedUntil, now)))
                .lt(WorkAuditTaskEntity::getQueryCount, maxAttempts)
                .eq(WorkAuditTaskEntity::getDeleted, NOT_DELETED)
                .apply(ACTIVE_AUDITING_WORK_EXISTS_SQL,
                        WorkAuditStatusDict.AUDITING.getCode(), NOT_DELETED));
    }

    /**
     * 查询仍可重提且提交租约已过期的视频任务。
     *
     * @param limit 查询数量上限
     * @param maxAttempts 最大提交尝试次数
     * @param now 本轮统一时间
     * @return 可恢复提交的视频任务
     */
    public List<WorkAuditTaskEntity> findRetryableExpiredVideoSubmitTasks(
            int limit, int maxAttempts, LocalDateTime now) {
        return taskMapper.selectList(Wrappers.<WorkAuditTaskEntity>lambdaQuery()
                .eq(WorkAuditTaskEntity::getMediaType, MediaTypeDict.VIDEO.getCode())
                .eq(WorkAuditTaskEntity::getTaskStatus, WorkAuditTaskStatusDict.SUBMITTING.getCode())
                .lt(WorkAuditTaskEntity::getLockedUntil, now)
                .lt(WorkAuditTaskEntity::getAttemptCount, maxAttempts)
                .eq(WorkAuditTaskEntity::getDeleted, NOT_DELETED)
                .apply(ACTIVE_AUDITING_WORK_EXISTS_SQL,
                        WorkAuditStatusDict.AUDITING.getCode(), NOT_DELETED)
                .orderByAsc(WorkAuditTaskEntity::getId)
                .last(LIMIT_SQL_PREFIX + normalizedLimit(limit)));
    }

    /**
     * 查询达到提交尝试上限且租约已过期的视频任务。
     *
     * @param limit 查询数量上限
     * @param maxAttempts 最大提交尝试次数
     * @param now 本轮统一时间
     * @return 待失败终态回收的视频任务
     */
    public List<WorkAuditTaskEntity> findExhaustedExpiredVideoSubmitTasks(
            int limit, int maxAttempts, LocalDateTime now) {
        return taskMapper.selectList(Wrappers.<WorkAuditTaskEntity>lambdaQuery()
                .eq(WorkAuditTaskEntity::getMediaType, MediaTypeDict.VIDEO.getCode())
                .eq(WorkAuditTaskEntity::getTaskStatus, WorkAuditTaskStatusDict.SUBMITTING.getCode())
                .lt(WorkAuditTaskEntity::getLockedUntil, now)
                .ge(WorkAuditTaskEntity::getAttemptCount, maxAttempts)
                .eq(WorkAuditTaskEntity::getDeleted, NOT_DELETED)
                .apply(ACTIVE_AUDITING_WORK_EXISTS_SQL,
                        WorkAuditStatusDict.AUDITING.getCode(), NOT_DELETED)
                .orderByAsc(WorkAuditTaskEntity::getId)
                .last(LIMIT_SQL_PREFIX + normalizedLimit(limit)));
    }

    /**
     * 查询达到主动查询上限且查询租约已过期的视频任务。
     *
     * @param limit 查询数量上限
     * @param maxAttempts 最大查询次数
     * @param now 本轮统一时间
     * @return 待失败终态回收的视频任务
     */
    public List<WorkAuditTaskEntity> findExhaustedExpiredVideoQueryTasks(
            int limit, int maxAttempts, LocalDateTime now) {
        return taskMapper.selectList(Wrappers.<WorkAuditTaskEntity>lambdaQuery()
                .eq(WorkAuditTaskEntity::getMediaType, MediaTypeDict.VIDEO.getCode())
                .eq(WorkAuditTaskEntity::getTaskStatus, WorkAuditTaskStatusDict.QUERYING.getCode())
                .lt(WorkAuditTaskEntity::getLockedUntil, now)
                .ge(WorkAuditTaskEntity::getQueryCount, maxAttempts)
                .eq(WorkAuditTaskEntity::getDeleted, NOT_DELETED)
                .apply(ACTIVE_AUDITING_WORK_EXISTS_SQL,
                        WorkAuditStatusDict.AUDITING.getCode(), NOT_DELETED)
                .orderByAsc(WorkAuditTaskEntity::getId)
                .last(LIMIT_SQL_PREFIX + normalizedLimit(limit)));
    }

    /**
     * 查询仍可恢复且租约已过期的图片审核任务。
     *
     * @param limit 查询数量上限
     * @param maxAttempts 最大尝试次数
     * @param now 本轮统一时间
     * @return 可恢复的图片审核任务
     */
    public List<WorkAuditTaskEntity> findRetryableExpiredImageTasks(
            int limit, int maxAttempts, LocalDateTime now) {
        return taskMapper.selectList(Wrappers.<WorkAuditTaskEntity>lambdaQuery()
                .eq(WorkAuditTaskEntity::getMediaType, MediaTypeDict.IMAGE.getCode())
                .eq(WorkAuditTaskEntity::getTaskStatus, WorkAuditTaskStatusDict.SUBMITTING.getCode())
                .lt(WorkAuditTaskEntity::getLockedUntil, now)
                .lt(WorkAuditTaskEntity::getAttemptCount, maxAttempts)
                .eq(WorkAuditTaskEntity::getDeleted, NOT_DELETED)
                .apply(ACTIVE_AUDITING_WORK_EXISTS_SQL,
                        WorkAuditStatusDict.AUDITING.getCode(), NOT_DELETED)
                .orderByAsc(WorkAuditTaskEntity::getId)
                .last(LIMIT_SQL_PREFIX + normalizedLimit(limit)));
    }

    /**
     * 查询达到尝试上限且租约已过期的图片审核任务。
     *
     * @param limit 查询数量上限
     * @param maxAttempts 最大尝试次数
     * @param now 本轮统一时间
     * @return 待失败终态回收的图片审核任务
     */
    public List<WorkAuditTaskEntity> findExhaustedExpiredImageTasks(
            int limit, int maxAttempts, LocalDateTime now) {
        return taskMapper.selectList(Wrappers.<WorkAuditTaskEntity>lambdaQuery()
                .eq(WorkAuditTaskEntity::getMediaType, MediaTypeDict.IMAGE.getCode())
                .eq(WorkAuditTaskEntity::getTaskStatus, WorkAuditTaskStatusDict.SUBMITTING.getCode())
                .lt(WorkAuditTaskEntity::getLockedUntil, now)
                .ge(WorkAuditTaskEntity::getAttemptCount, maxAttempts)
                .eq(WorkAuditTaskEntity::getDeleted, NOT_DELETED)
                .apply(ACTIVE_AUDITING_WORK_EXISTS_SQL,
                        WorkAuditStatusDict.AUDITING.getCode(), NOT_DELETED)
                .orderByAsc(WorkAuditTaskEntity::getId)
                .last(LIMIT_SQL_PREFIX + normalizedLimit(limit)));
    }

    /**
     * 查询待执行或锁已过期的动图审核任务。
     *
     * @param limit 查询数量上限
     * @param maxAttempts 最大尝试次数
     * @param now 当前时间
     * @return 可执行动图审核任务
     */
    public List<WorkAuditTaskEntity> findRunnableAnimationTasks(int limit, int maxAttempts, LocalDateTime now) {
        return taskMapper.selectList(Wrappers.<WorkAuditTaskEntity>lambdaQuery()
                .eq(WorkAuditTaskEntity::getMediaType, MediaTypeDict.ANIMATION.getCode())
                .and(wrapper -> wrapper
                        .eq(WorkAuditTaskEntity::getTaskStatus, WorkAuditTaskStatusDict.PENDING.getCode())
                        .or(expired -> expired
                                .eq(WorkAuditTaskEntity::getTaskStatus,
                                        WorkAuditTaskStatusDict.SUBMITTING.getCode())
                                .lt(WorkAuditTaskEntity::getLockedUntil, now)))
                .lt(WorkAuditTaskEntity::getAttemptCount, maxAttempts)
                .eq(WorkAuditTaskEntity::getDeleted, NOT_DELETED)
                .apply(ACTIVE_AUDITING_WORK_EXISTS_SQL,
                        WorkAuditStatusDict.AUDITING.getCode(), NOT_DELETED)
                .orderByAsc(WorkAuditTaskEntity::getId)
                // limit 已归一化为非负整数，拼接 LIMIT 子句不会引入 SQL 注入风险。
                .last(LIMIT_SQL_PREFIX + normalizedLimit(limit)));
    }

    /**
     * 查询达到尝试上限且租约已过期的动图任务，用于进程崩溃后的终态回收。
     *
     * @param limit 查询数量上限
     * @param maxAttempts 最大尝试次数
     * @param now 当前时间
     * @return 待回收动图任务
     */
    public List<WorkAuditTaskEntity> findExhaustedExpiredAnimationTasks(
            int limit, int maxAttempts, LocalDateTime now) {
        return taskMapper.selectList(Wrappers.<WorkAuditTaskEntity>lambdaQuery()
                .eq(WorkAuditTaskEntity::getMediaType, MediaTypeDict.ANIMATION.getCode())
                .eq(WorkAuditTaskEntity::getTaskStatus, WorkAuditTaskStatusDict.SUBMITTING.getCode())
                .lt(WorkAuditTaskEntity::getLockedUntil, now)
                .ge(WorkAuditTaskEntity::getAttemptCount, maxAttempts)
                .eq(WorkAuditTaskEntity::getDeleted, NOT_DELETED)
                .apply(ACTIVE_AUDITING_WORK_EXISTS_SQL,
                        WorkAuditStatusDict.AUDITING.getCode(), NOT_DELETED)
                .orderByAsc(WorkAuditTaskEntity::getId)
                .last(LIMIT_SQL_PREFIX + normalizedLimit(limit)));
    }

    /**
     * 原子抢占动图审核任务并递增尝试次数。
     *
     * @param taskId 任务 ID
     * @param lockOwner 锁持有者
     * @param lockedUntil 锁过期时间
     * @param maxAttempts 最大尝试次数
     * @return 是否抢占成功
     */
    public boolean claimAnimationTask(Long taskId, String lockOwner,
                                      LocalDateTime lockedUntil, int maxAttempts) {
        LocalDateTime now = LocalDateTime.now();
        int updated = taskMapper.update(null, Wrappers.<WorkAuditTaskEntity>lambdaUpdate()
                .set(WorkAuditTaskEntity::getTaskStatus, WorkAuditTaskStatusDict.SUBMITTING.getCode())
                .set(WorkAuditTaskEntity::getAuditResult, AuditResultDict.UNKNOWN.getCode())
                .set(WorkAuditTaskEntity::getLockedBy, lockOwner)
                .set(WorkAuditTaskEntity::getLockedUntil, lockedUntil)
                .set(WorkAuditTaskEntity::getStartedAt, now)
                .set(WorkAuditTaskEntity::getLastErrorMessage, null)
                .set(WorkAuditTaskEntity::getUpdatedAt, now)
                .setSql(ATTEMPT_COUNT_INCREMENT_SQL)
                .setSql(VERSION_INCREMENT_SQL)
                .eq(WorkAuditTaskEntity::getId, taskId)
                .eq(WorkAuditTaskEntity::getMediaType, MediaTypeDict.ANIMATION.getCode())
                .and(wrapper -> wrapper
                        .eq(WorkAuditTaskEntity::getTaskStatus, WorkAuditTaskStatusDict.PENDING.getCode())
                        .or(expired -> expired
                                .eq(WorkAuditTaskEntity::getTaskStatus,
                                        WorkAuditTaskStatusDict.SUBMITTING.getCode())
                                .lt(WorkAuditTaskEntity::getLockedUntil, now)))
                .lt(WorkAuditTaskEntity::getAttemptCount, maxAttempts)
                .eq(WorkAuditTaskEntity::getDeleted, NOT_DELETED)
                .apply(ACTIVE_AUDITING_WORK_EXISTS_SQL,
                        WorkAuditStatusDict.AUDITING.getCode(), NOT_DELETED));
        return updated == 1;
    }

    /**
     * 抢占已达到尝试上限且租约过期的动图任务，以便安全写入失败终态。
     *
     * @param taskId 任务 ID
     * @param lockOwner 新领取 token
     * @param lockedUntil 新租约截止时间
     * @param maxAttempts 最大尝试次数
     * @return 是否抢占成功
     */
    public boolean claimExhaustedAnimationTask(
            Long taskId, String lockOwner, LocalDateTime lockedUntil, int maxAttempts) {
        LocalDateTime now = LocalDateTime.now();
        int updated = taskMapper.update(null, Wrappers.<WorkAuditTaskEntity>lambdaUpdate()
                .set(WorkAuditTaskEntity::getLockedBy, lockOwner)
                .set(WorkAuditTaskEntity::getLockedUntil, lockedUntil)
                .set(WorkAuditTaskEntity::getUpdatedAt, now)
                .setSql(VERSION_INCREMENT_SQL)
                .eq(WorkAuditTaskEntity::getId, taskId)
                .eq(WorkAuditTaskEntity::getMediaType, MediaTypeDict.ANIMATION.getCode())
                .eq(WorkAuditTaskEntity::getTaskStatus, WorkAuditTaskStatusDict.SUBMITTING.getCode())
                .lt(WorkAuditTaskEntity::getLockedUntil, now)
                .ge(WorkAuditTaskEntity::getAttemptCount, maxAttempts)
                .eq(WorkAuditTaskEntity::getDeleted, NOT_DELETED)
                .apply(ACTIVE_AUDITING_WORK_EXISTS_SQL,
                        WorkAuditStatusDict.AUDITING.getCode(), NOT_DELETED));
        return updated == 1;
    }

    /**
     * 将视频任务 claim 为查询中，并递增主动查询次数。
     *
     * @param taskId 任务 ID
     * @param lockOwner 本次领取 token
     * @param now 本轮统一时间
     * @param lockedUntil 锁过期时间
     * @param maxAttempts 最大查询次数
     * @return 是否 claim 成功
     */
    public boolean claimVideoQuery(Long taskId, String lockOwner, LocalDateTime now,
                                   LocalDateTime lockedUntil, int maxAttempts) {
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
                .and(status -> status
                        .and(regular -> regular
                                .in(WorkAuditTaskEntity::getTaskStatus,
                                        WorkAuditTaskStatusDict.SUBMITTED.getCode(),
                                        WorkAuditTaskStatusDict.RUNNING.getCode())
                                .and(lease -> lease
                                        .isNull(WorkAuditTaskEntity::getLockedUntil)
                                        .or()
                                        .lt(WorkAuditTaskEntity::getLockedUntil, now)))
                        .or(expired -> expired
                                .eq(WorkAuditTaskEntity::getTaskStatus,
                                        WorkAuditTaskStatusDict.QUERYING.getCode())
                                .lt(WorkAuditTaskEntity::getLockedUntil, now)))
                .lt(WorkAuditTaskEntity::getQueryCount, maxAttempts)
                .eq(WorkAuditTaskEntity::getDeleted, NOT_DELETED)
                .apply(ACTIVE_AUDITING_WORK_EXISTS_SQL,
                        WorkAuditStatusDict.AUDITING.getCode(), NOT_DELETED));
        return updated == 1;
    }

    /**
     * 原子重领提交租约已过期且仍可重提的视频任务。
     *
     * @param taskId 任务 ID
     * @param claimToken 本次领取 token
     * @param now 本轮统一时间
     * @param lockedUntil 新租约截止时间
     * @param maxAttempts 最大提交尝试次数
     * @return 是否领取成功
     */
    public boolean claimExpiredVideoSubmit(Long taskId, String claimToken, LocalDateTime now,
                                           LocalDateTime lockedUntil, int maxAttempts) {
        int updated = taskMapper.update(null, Wrappers.<WorkAuditTaskEntity>lambdaUpdate()
                .set(WorkAuditTaskEntity::getLockedBy, claimToken)
                .set(WorkAuditTaskEntity::getLockedUntil, lockedUntil)
                .set(WorkAuditTaskEntity::getStartedAt, now)
                .set(WorkAuditTaskEntity::getLastErrorMessage, null)
                .set(WorkAuditTaskEntity::getUpdatedAt, now)
                .setSql(ATTEMPT_COUNT_INCREMENT_SQL)
                .setSql(VERSION_INCREMENT_SQL)
                .eq(WorkAuditTaskEntity::getId, taskId)
                .eq(WorkAuditTaskEntity::getMediaType, MediaTypeDict.VIDEO.getCode())
                .eq(WorkAuditTaskEntity::getTaskStatus, WorkAuditTaskStatusDict.SUBMITTING.getCode())
                .lt(WorkAuditTaskEntity::getLockedUntil, now)
                .lt(WorkAuditTaskEntity::getAttemptCount, maxAttempts)
                .eq(WorkAuditTaskEntity::getDeleted, NOT_DELETED)
                .apply(ACTIVE_AUDITING_WORK_EXISTS_SQL,
                        WorkAuditStatusDict.AUDITING.getCode(), NOT_DELETED));
        return updated == 1;
    }

    /**
     * 原子领取达到提交尝试上限的过期视频任务，用于失败终态回收。
     *
     * @param taskId 任务 ID
     * @param claimToken 本次领取 token
     * @param now 本轮统一时间
     * @param lockedUntil 新租约截止时间
     * @param maxAttempts 最大提交尝试次数
     * @return 是否领取成功
     */
    public boolean claimExhaustedExpiredVideoSubmit(Long taskId, String claimToken, LocalDateTime now,
                                                    LocalDateTime lockedUntil, int maxAttempts) {
        int updated = taskMapper.update(null, Wrappers.<WorkAuditTaskEntity>lambdaUpdate()
                .set(WorkAuditTaskEntity::getLockedBy, claimToken)
                .set(WorkAuditTaskEntity::getLockedUntil, lockedUntil)
                .set(WorkAuditTaskEntity::getUpdatedAt, now)
                .setSql(VERSION_INCREMENT_SQL)
                .eq(WorkAuditTaskEntity::getId, taskId)
                .eq(WorkAuditTaskEntity::getMediaType, MediaTypeDict.VIDEO.getCode())
                .eq(WorkAuditTaskEntity::getTaskStatus, WorkAuditTaskStatusDict.SUBMITTING.getCode())
                .lt(WorkAuditTaskEntity::getLockedUntil, now)
                .ge(WorkAuditTaskEntity::getAttemptCount, maxAttempts)
                .eq(WorkAuditTaskEntity::getDeleted, NOT_DELETED)
                .apply(ACTIVE_AUDITING_WORK_EXISTS_SQL,
                        WorkAuditStatusDict.AUDITING.getCode(), NOT_DELETED));
        return updated == 1;
    }

    /**
     * 原子领取达到查询上限的过期视频任务，用于失败终态回收。
     *
     * @param taskId 任务 ID
     * @param claimToken 本次领取 token
     * @param now 本轮统一时间
     * @param lockedUntil 新租约截止时间
     * @param maxAttempts 最大查询次数
     * @return 是否领取成功
     */
    public boolean claimExhaustedExpiredVideoQuery(Long taskId, String claimToken, LocalDateTime now,
                                                   LocalDateTime lockedUntil, int maxAttempts) {
        int updated = taskMapper.update(null, Wrappers.<WorkAuditTaskEntity>lambdaUpdate()
                .set(WorkAuditTaskEntity::getLockedBy, claimToken)
                .set(WorkAuditTaskEntity::getLockedUntil, lockedUntil)
                .set(WorkAuditTaskEntity::getUpdatedAt, now)
                .setSql(VERSION_INCREMENT_SQL)
                .eq(WorkAuditTaskEntity::getId, taskId)
                .eq(WorkAuditTaskEntity::getMediaType, MediaTypeDict.VIDEO.getCode())
                .eq(WorkAuditTaskEntity::getTaskStatus, WorkAuditTaskStatusDict.QUERYING.getCode())
                .lt(WorkAuditTaskEntity::getLockedUntil, now)
                .ge(WorkAuditTaskEntity::getQueryCount, maxAttempts)
                .eq(WorkAuditTaskEntity::getDeleted, NOT_DELETED)
                .apply(ACTIVE_AUDITING_WORK_EXISTS_SQL,
                        WorkAuditStatusDict.AUDITING.getCode(), NOT_DELETED));
        return updated == 1;
    }

    /**
     * 原子重领租约已过期且仍可重试的图片审核任务。
     *
     * @param taskId 任务 ID
     * @param claimToken 本次领取 token
     * @param now 本轮统一时间
     * @param lockedUntil 新租约截止时间
     * @param maxAttempts 最大尝试次数
     * @return 是否领取成功
     */
    public boolean claimExpiredImageTask(Long taskId, String claimToken, LocalDateTime now,
                                         LocalDateTime lockedUntil, int maxAttempts) {
        int updated = taskMapper.update(null, Wrappers.<WorkAuditTaskEntity>lambdaUpdate()
                .set(WorkAuditTaskEntity::getLockedBy, claimToken)
                .set(WorkAuditTaskEntity::getLockedUntil, lockedUntil)
                .set(WorkAuditTaskEntity::getStartedAt, now)
                .set(WorkAuditTaskEntity::getLastErrorMessage, null)
                .set(WorkAuditTaskEntity::getUpdatedAt, now)
                .setSql(ATTEMPT_COUNT_INCREMENT_SQL)
                .setSql(VERSION_INCREMENT_SQL)
                .eq(WorkAuditTaskEntity::getId, taskId)
                .eq(WorkAuditTaskEntity::getMediaType, MediaTypeDict.IMAGE.getCode())
                .eq(WorkAuditTaskEntity::getTaskStatus, WorkAuditTaskStatusDict.SUBMITTING.getCode())
                .lt(WorkAuditTaskEntity::getLockedUntil, now)
                .lt(WorkAuditTaskEntity::getAttemptCount, maxAttempts)
                .eq(WorkAuditTaskEntity::getDeleted, NOT_DELETED)
                .apply(ACTIVE_AUDITING_WORK_EXISTS_SQL,
                        WorkAuditStatusDict.AUDITING.getCode(), NOT_DELETED));
        return updated == 1;
    }

    /**
     * 原子领取达到尝试上限的过期图片任务，用于失败终态回收。
     *
     * @param taskId 任务 ID
     * @param claimToken 本次领取 token
     * @param now 本轮统一时间
     * @param lockedUntil 新租约截止时间
     * @param maxAttempts 最大尝试次数
     * @return 是否领取成功
     */
    public boolean claimExhaustedExpiredImageTask(Long taskId, String claimToken, LocalDateTime now,
                                                  LocalDateTime lockedUntil, int maxAttempts) {
        int updated = taskMapper.update(null, Wrappers.<WorkAuditTaskEntity>lambdaUpdate()
                .set(WorkAuditTaskEntity::getLockedBy, claimToken)
                .set(WorkAuditTaskEntity::getLockedUntil, lockedUntil)
                .set(WorkAuditTaskEntity::getUpdatedAt, now)
                .setSql(VERSION_INCREMENT_SQL)
                .eq(WorkAuditTaskEntity::getId, taskId)
                .eq(WorkAuditTaskEntity::getMediaType, MediaTypeDict.IMAGE.getCode())
                .eq(WorkAuditTaskEntity::getTaskStatus, WorkAuditTaskStatusDict.SUBMITTING.getCode())
                .lt(WorkAuditTaskEntity::getLockedUntil, now)
                .ge(WorkAuditTaskEntity::getAttemptCount, maxAttempts)
                .eq(WorkAuditTaskEntity::getDeleted, NOT_DELETED)
                .apply(ACTIVE_AUDITING_WORK_EXISTS_SQL,
                        WorkAuditStatusDict.AUDITING.getCode(), NOT_DELETED));
        return updated == 1;
    }

    /**
     * 视频提交成功后写入腾讯云任务 ID。
     *
     * @param taskId 任务 ID
     * @param lockOwner 本次领取 token
     * @param ciJobId 腾讯云任务 ID
     * @param responsePayload 响应摘要
     * @return 是否更新成功
     */
    public boolean markVideoSubmitted(Long taskId, String lockOwner, String ciJobId, String responsePayload) {
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
                .eq(WorkAuditTaskEntity::getMediaType, MediaTypeDict.VIDEO.getCode())
                .eq(WorkAuditTaskEntity::getTaskStatus, WorkAuditTaskStatusDict.SUBMITTING.getCode())
                .eq(WorkAuditTaskEntity::getLockedBy, lockOwner)
                .eq(WorkAuditTaskEntity::getDeleted, NOT_DELETED));
        return updated == 1;
    }

    /**
     * 将视频查询结果标记为仍在处理中。
     *
     * @param taskId 任务 ID
     * @param lockOwner 本次领取 token
     * @param ciState 腾讯云状态
     * @param responsePayload 响应摘要
     * @return 是否更新成功
     */
    public boolean markVideoRunning(Long taskId, String lockOwner, String ciState, String responsePayload) {
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
                .eq(WorkAuditTaskEntity::getMediaType, MediaTypeDict.VIDEO.getCode())
                .eq(WorkAuditTaskEntity::getTaskStatus, WorkAuditTaskStatusDict.QUERYING.getCode())
                .eq(WorkAuditTaskEntity::getLockedBy, lockOwner)
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
     * 使用本次领取 token 将图片任务写入成功终态。
     *
     * @param taskId 任务 ID
     * @param lockOwner 本次领取 token
     * @param result 审核结果
     * @param ciState 腾讯云状态
     * @param ciResult 腾讯云结果码
     * @param ciLabel 命中标签
     * @param ciScore 命中分数
     * @param responsePayload 响应摘要
     * @return 是否仍持有有效自动审核任务的领取权
     */
    public boolean markImageSuccess(
            Long taskId, String lockOwner, AuditResultDict result, String ciState, Integer ciResult,
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
                .eq(WorkAuditTaskEntity::getMediaType, MediaTypeDict.IMAGE.getCode())
                .eq(WorkAuditTaskEntity::getTaskStatus, WorkAuditTaskStatusDict.SUBMITTING.getCode())
                .eq(WorkAuditTaskEntity::getLockedBy, lockOwner)
                .eq(WorkAuditTaskEntity::getDeleted, NOT_DELETED)
                .apply(ACTIVE_AUDITING_WORK_EXISTS_SQL,
                        WorkAuditStatusDict.AUDITING.getCode(), NOT_DELETED));
        return updated == 1;
    }

    /**
     * 使用本次领取 token 将视频任务写入成功终态。
     *
     * @param taskId 任务 ID
     * @param lockOwner 本次领取 token
     * @param result 审核结果
     * @param ciState 腾讯云状态
     * @param ciResult 腾讯云结果码
     * @param ciLabel 命中标签
     * @param ciScore 命中分数
     * @param responsePayload 响应摘要
     * @return 是否更新成功
     */
    public boolean markVideoSuccess(
            Long taskId, String lockOwner, AuditResultDict result, String ciState, Integer ciResult,
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
                .eq(WorkAuditTaskEntity::getMediaType, MediaTypeDict.VIDEO.getCode())
                .eq(WorkAuditTaskEntity::getTaskStatus, WorkAuditTaskStatusDict.QUERYING.getCode())
                .eq(WorkAuditTaskEntity::getLockedBy, lockOwner)
                .eq(WorkAuditTaskEntity::getDeleted, NOT_DELETED));
        return updated == 1;
    }

    /**
     * 使用本次领取 token 将动图任务写入成功终态，避免过期 worker 覆盖新领取者。
     *
     * @param taskId 任务 ID
     * @param lockOwner 本次领取 token
     * @param result 审核结果
     * @param ciState 腾讯云状态
     * @param ciResult 腾讯云结果码
     * @param ciLabel 命中标签
     * @param ciScore 命中分数
     * @param responsePayload 响应摘要
     * @return 是否更新成功
     */
    public boolean markAnimationSuccess(
            Long taskId, String lockOwner, AuditResultDict result, String ciState, Integer ciResult,
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
                .eq(WorkAuditTaskEntity::getMediaType, MediaTypeDict.ANIMATION.getCode())
                .eq(WorkAuditTaskEntity::getTaskStatus, WorkAuditTaskStatusDict.SUBMITTING.getCode())
                .eq(WorkAuditTaskEntity::getLockedBy, lockOwner)
                .eq(WorkAuditTaskEntity::getDeleted, NOT_DELETED));
        return updated == 1;
    }

    /**
     * 记录查询失败并回到 RUNNING，等待下一轮查询。
     *
     * @param taskId 任务 ID
     * @param lockOwner 本次领取 token
     * @param errorMessage 错误摘要
     * @param responsePayload 响应摘要
     * @return 是否更新成功
     */
    public boolean markVideoQueryFailureForNextRun(
            Long taskId, String lockOwner, String errorMessage, String responsePayload) {
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
                .eq(WorkAuditTaskEntity::getMediaType, MediaTypeDict.VIDEO.getCode())
                .eq(WorkAuditTaskEntity::getTaskStatus, WorkAuditTaskStatusDict.QUERYING.getCode())
                .eq(WorkAuditTaskEntity::getLockedBy, lockOwner)
                .eq(WorkAuditTaskEntity::getDeleted, NOT_DELETED));
        return updated == 1;
    }

    /**
     * 动图临时失败后回到待执行状态，并保留持久化抽样帧。
     *
     * @param taskId 任务 ID
     * @param errorMessage 错误摘要
     * @param responsePayload 响应摘要
     * @return 是否更新成功
     */
    public boolean markAnimationRetryPending(
            Long taskId, String lockOwner, String errorMessage, String responsePayload) {
        LocalDateTime now = LocalDateTime.now();
        int updated = taskMapper.update(null, Wrappers.<WorkAuditTaskEntity>lambdaUpdate()
                .set(WorkAuditTaskEntity::getTaskStatus, WorkAuditTaskStatusDict.PENDING.getCode())
                .set(WorkAuditTaskEntity::getAuditResult, AuditResultDict.UNKNOWN.getCode())
                .set(WorkAuditTaskEntity::getLastErrorMessage, errorMessage)
                .set(WorkAuditTaskEntity::getResponsePayload, responsePayload)
                .set(WorkAuditTaskEntity::getLockedBy, null)
                .set(WorkAuditTaskEntity::getLockedUntil, null)
                .set(WorkAuditTaskEntity::getUpdatedAt, now)
                .setSql(VERSION_INCREMENT_SQL)
                .eq(WorkAuditTaskEntity::getId, taskId)
                .eq(WorkAuditTaskEntity::getMediaType, MediaTypeDict.ANIMATION.getCode())
                .eq(WorkAuditTaskEntity::getTaskStatus, WorkAuditTaskStatusDict.SUBMITTING.getCode())
                .eq(WorkAuditTaskEntity::getLockedBy, lockOwner)
                .eq(WorkAuditTaskEntity::getDeleted, NOT_DELETED));
        return updated == 1;
    }

    /**
     * 使用本次领取 token 将动图任务写入失败终态。
     *
     * @param taskId 任务 ID
     * @param lockOwner 本次领取 token
     * @param errorMessage 错误摘要
     * @param responsePayload 响应摘要
     * @return 是否更新成功
     */
    public boolean markAnimationFailed(
            Long taskId, String lockOwner, String errorMessage, String responsePayload) {
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
                .eq(WorkAuditTaskEntity::getMediaType, MediaTypeDict.ANIMATION.getCode())
                .eq(WorkAuditTaskEntity::getTaskStatus, WorkAuditTaskStatusDict.SUBMITTING.getCode())
                .eq(WorkAuditTaskEntity::getLockedBy, lockOwner)
                .eq(WorkAuditTaskEntity::getDeleted, NOT_DELETED));
        return updated == 1;
    }

    /**
     * 同一事务中将刚恢复为待执行、但作品轮次已变化的动图任务终态化。
     *
     * @param taskId 任务 ID
     * @param errorMessage 错误摘要
     * @param responsePayload 响应摘要
     * @return 是否更新成功
     */
    public boolean markPendingAnimationFailed(
            Long taskId, String errorMessage, String responsePayload) {
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
                .eq(WorkAuditTaskEntity::getMediaType, MediaTypeDict.ANIMATION.getCode())
                .eq(WorkAuditTaskEntity::getTaskStatus, WorkAuditTaskStatusDict.PENDING.getCode())
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
     * 使用本次领取 token 将图片任务写入失败终态。
     *
     * @param taskId 任务 ID
     * @param lockOwner 本次领取 token
     * @param errorMessage 错误摘要
     * @param responsePayload 响应摘要
     * @return 是否仍持有有效自动审核任务的领取权
     */
    public boolean markImageFailed(
            Long taskId, String lockOwner, String errorMessage, String responsePayload) {
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
                .eq(WorkAuditTaskEntity::getMediaType, MediaTypeDict.IMAGE.getCode())
                .eq(WorkAuditTaskEntity::getTaskStatus, WorkAuditTaskStatusDict.SUBMITTING.getCode())
                .eq(WorkAuditTaskEntity::getLockedBy, lockOwner)
                .eq(WorkAuditTaskEntity::getDeleted, NOT_DELETED)
                .apply(ACTIVE_AUDITING_WORK_EXISTS_SQL,
                        WorkAuditStatusDict.AUDITING.getCode(), NOT_DELETED));
        return updated == 1;
    }

    /**
     * 使用本次领取 token 将视频任务写入失败终态。
     *
     * @param taskId 任务 ID
     * @param lockOwner 本次领取 token
     * @param errorMessage 错误摘要
     * @param responsePayload 响应摘要
     * @return 是否更新成功
     */
    public boolean markVideoFailed(
            Long taskId, String lockOwner, String errorMessage, String responsePayload) {
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
                .eq(WorkAuditTaskEntity::getMediaType, MediaTypeDict.VIDEO.getCode())
                .in(WorkAuditTaskEntity::getTaskStatus, FINISHABLE_TASK_STATUSES)
                .eq(WorkAuditTaskEntity::getLockedBy, lockOwner)
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
     * 查询审核任务当前尝试次数。
     *
     * @param taskId 任务 ID
     * @return 当前尝试次数，任务不存在时返回 null
     */
    public Integer findAttemptCountById(Long taskId) {
        WorkAuditTaskEntity task = taskMapper.selectOne(Wrappers.<WorkAuditTaskEntity>lambdaQuery()
                .select(WorkAuditTaskEntity::getAttemptCount)
                .eq(WorkAuditTaskEntity::getId, taskId)
                .eq(WorkAuditTaskEntity::getDeleted, NOT_DELETED)
                .last(LIMIT_ONE_SQL));
        return task == null ? null : task.getAttemptCount();
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
