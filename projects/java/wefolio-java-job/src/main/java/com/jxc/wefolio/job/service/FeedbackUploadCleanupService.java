package com.jxc.wefolio.job.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.jxc.wefolio.job.config.FeedbackUploadCleanupProperties;
import com.jxc.wefolio.job.entity.FeedbackUploadTaskEntity;
import com.jxc.wefolio.job.mapper.FeedbackUploadTaskMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;

/**
 * 过期反馈附件上传任务清理服务。
 */
@Slf4j
@Service
public class FeedbackUploadCleanupService {

    /** 反馈上传任务 DATETIME 的业务时区 */
    private static final ZoneId SHANGHAI_ZONE = ZoneId.of("Asia/Shanghai");

    /** 待确认状态 */
    private static final String PENDING_STATUS = FeedbackUploadTaskMapper.PENDING_STATUS;

    /** 已过期状态 */
    private static final String EXPIRED_STATUS = "EXPIRED";

    /** 查询条数限制 SQL 前缀 */
    private static final String LIMIT_SQL_PREFIX = "LIMIT ";

    /** 版本号自增 SQL */
    private static final String VERSION_INCREMENT_SQL = "version = version + 1";

    /** 单条清理操作固定标识 */
    private static final String CLEANUP_ONE_OPERATION = "LOCK_DELETE_UPDATE";

    /** 反馈上传任务 Mapper */
    private final FeedbackUploadTaskMapper mapper;

    /** COS 删除适配服务 */
    private final FeedbackUploadCleanupCosService cosService;

    /** 清理任务配置 */
    private final FeedbackUploadCleanupProperties properties;

    /** 固定上海业务时区的可替换时钟 */
    private final Clock clock;

    /** 单条任务独立事务模板 */
    private final TransactionTemplate transactionTemplate;

    /**
     * 创建生产服务并使用固定上海业务时区生成时钟。
     *
     * @param mapper 上传任务 Mapper
     * @param cosService COS 删除服务
     * @param properties 清理配置
     * @param transactionManager Spring 事务管理器
     */
    @Autowired
    public FeedbackUploadCleanupService(
            FeedbackUploadTaskMapper mapper,
            FeedbackUploadCleanupCosService cosService,
            FeedbackUploadCleanupProperties properties,
            PlatformTransactionManager transactionManager
    ) {
        this(mapper, cosService, properties, Clock.system(SHANGHAI_ZONE),
                transactionManager);
    }

    /**
     * 测试使用固定时钟的构造器。
     *
     * @param mapper 上传任务 Mapper
     * @param cosService COS 删除服务
     * @param properties 清理配置
     * @param clock 可替换时钟
     * @param transactionManager Spring 事务管理器
     */
    FeedbackUploadCleanupService(
            FeedbackUploadTaskMapper mapper,
            FeedbackUploadCleanupCosService cosService,
            FeedbackUploadCleanupProperties properties,
            Clock clock,
            PlatformTransactionManager transactionManager
    ) {
        this.mapper = mapper;
        this.cosService = cosService;
        this.properties = properties;
        this.clock = clock.withZone(SHANGHAI_ZONE);
        this.transactionTemplate = new TransactionTemplate(transactionManager);
    }

    /**
     * 清理一批已经过期且仍未确认的上传任务。
     *
     * @return 本轮清理结果
     */
    public CleanupResult cleanupExpiredUploads() {
        properties.validate();
        int batchSize = properties.getBatchSize();
        int maxBatches = properties.getMaxBatches();
        LocalDateTime now = LocalDateTime.now(clock);
        CandidateCursor cursor = null;
        int selectedCount = 0;
        int expiredCount = 0;
        int failedCount = 0;
        int concurrentlyChangedCount = 0;
        int batchCount = 0;
        int lastBatchSize = 0;
        while (batchCount < maxBatches) {
            List<FeedbackUploadTaskEntity> tasks = findCandidates(now, cursor, batchSize);
            if (tasks.isEmpty()) {
                break;
            }
            batchCount++;
            lastBatchSize = tasks.size();
            selectedCount += tasks.size();
            FeedbackUploadTaskEntity lastTask = tasks.getLast();
            cursor = new CandidateCursor(lastTask.getExpiresAt(), lastTask.getId());
            for (FeedbackUploadTaskEntity task : tasks) {
                try {
                    CleanupOneResult oneResult = transactionTemplate.execute(
                            status -> cleanupOne(task.getId(), now));
                    if (oneResult == CleanupOneResult.EXPIRED) {
                        expiredCount++;
                    } else {
                        concurrentlyChangedCount++;
                    }
                } catch (RuntimeException exception) {
                    failedCount++;
                    log.warn("反馈附件单条清理失败: taskId={}, operation={}, exceptionType={}",
                            task.getId(), CLEANUP_ONE_OPERATION,
                            exception.getClass().getSimpleName());
                }
            }
        }
        boolean limitReached = batchCount == maxBatches
                && lastBatchSize == batchSize
                && !findCandidates(now, cursor, 1).isEmpty();
        CleanupResult result = new CleanupResult(
                selectedCount, expiredCount, failedCount, concurrentlyChangedCount,
                batchCount, limitReached);
        log.info("反馈附件清理完成: selectedCount={}, expiredCount={}, failedCount={}, "
                        + "concurrentlyChangedCount={}, batchCount={}, limitReached={}",
                result.selectedCount(), result.expiredCount(), result.failedCount(),
                result.concurrentlyChangedCount(), result.batchCount(), result.limitReached());
        return result;
    }

    /**
     * 按复合游标读取下一批过期候选。
     *
     * @param now 本轮固定过期判断时间
     * @param cursor 上一批最后一条任务游标，首批为空
     * @param limit 查询条数上限
     * @return 严格按过期时间和 ID 升序排列的候选
     */
    private List<FeedbackUploadTaskEntity> findCandidates(
            LocalDateTime now,
            CandidateCursor cursor,
            int limit
    ) {
        var query = Wrappers.<FeedbackUploadTaskEntity>lambdaQuery()
                .eq(FeedbackUploadTaskEntity::getStatus, PENDING_STATUS)
                .eq(FeedbackUploadTaskEntity::getDeleted, 0L)
                .le(FeedbackUploadTaskEntity::getExpiresAt, now);
        if (cursor != null) {
            query.and(cursorQuery -> cursorQuery
                    .gt(FeedbackUploadTaskEntity::getExpiresAt, cursor.expiresAt())
                    .or()
                    .eq(FeedbackUploadTaskEntity::getExpiresAt, cursor.expiresAt())
                    .gt(FeedbackUploadTaskEntity::getId, cursor.id()));
        }
        return mapper.selectList(query
                .orderByAsc(FeedbackUploadTaskEntity::getExpiresAt)
                .orderByAsc(FeedbackUploadTaskEntity::getId)
                .last(LIMIT_SQL_PREFIX + limit));
    }

    /**
     * 在独立事务内锁定复核并清理单条任务。
     *
     * <p>COS 删除发生在数据库行锁持有期间，确保确认流程无法并发将同一对象变成正式附件。</p>
     *
     * @param taskId 候选上传任务 ID
     * @param now 本轮固定过期判断时间
     * @return 单条清理结果
     */
    private CleanupOneResult cleanupOne(Long taskId, LocalDateTime now) {
        FeedbackUploadTaskEntity lockedTask = mapper.selectPendingExpiredForUpdate(taskId, now);
        if (lockedTask == null) {
            return CleanupOneResult.CONCURRENTLY_CHANGED;
        }
        cosService.delete(lockedTask.getId(), lockedTask.getObjectKey());
        int affected = mapper.update(null,
                Wrappers.<FeedbackUploadTaskEntity>lambdaUpdate()
                        .set(FeedbackUploadTaskEntity::getStatus, EXPIRED_STATUS)
                        .set(FeedbackUploadTaskEntity::getUpdatedAt, now)
                        .setSql(VERSION_INCREMENT_SQL)
                        .eq(FeedbackUploadTaskEntity::getId, lockedTask.getId())
                        .eq(FeedbackUploadTaskEntity::getStatus, PENDING_STATUS)
                        .eq(FeedbackUploadTaskEntity::getDeleted, 0L));
        return affected == 1 ? CleanupOneResult.EXPIRED : CleanupOneResult.CONCURRENTLY_CHANGED;
    }

    /** 单条上传任务清理结果。 */
    private enum CleanupOneResult {
        EXPIRED,
        CONCURRENTLY_CHANGED
    }

    /**
     * 候选任务复合游标。
     *
     * @param expiresAt 当前页最后一条任务的过期时间
     * @param id 当前页最后一条任务 ID
     */
    private record CandidateCursor(LocalDateTime expiresAt, Long id) {
    }

    /**
     * 单轮反馈附件清理结果。
     *
     * @param selectedCount 选中的过期任务数
     * @param expiredCount 成功标记过期数
     * @param failedCount 单条清理失败数
     * @param concurrentlyChangedCount 并发状态变化数
     * @param batchCount 实际处理的非空批次数
     * @param limitReached 是否因达到批次上限而仍有候选未处理
     */
    public record CleanupResult(
            int selectedCount,
            int expiredCount,
            int failedCount,
            int concurrentlyChangedCount,
            int batchCount,
            boolean limitReached
    ) {
    }
}
