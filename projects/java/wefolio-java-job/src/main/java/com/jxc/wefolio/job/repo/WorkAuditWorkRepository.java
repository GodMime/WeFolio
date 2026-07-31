package com.jxc.wefolio.job.repo;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.jxc.wefolio.job.dict.MediaTypeDict;
import com.jxc.wefolio.job.dict.WorkAuditStatusDict;
import com.jxc.wefolio.job.entity.WorkAuditWorkEntity;
import com.jxc.wefolio.job.mapper.WorkAuditWorkMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 作品审核用作品仓储。
 */
@Repository
@RequiredArgsConstructor
public class WorkAuditWorkRepository {

    /** 逻辑未删除值 */
    private static final long NOT_DELETED = 0L;

    /** LIMIT 子句前缀 */
    private static final String LIMIT_SQL_PREFIX = "LIMIT ";

    /** 乐观锁版本递增 SQL */
    private static final String VERSION_INCREMENT_SQL = "version = version + 1";

    private final WorkAuditWorkMapper workMapper;

    /**
     * 查询待审核视频作品。
     *
     * @param limit 查询数量上限
     * @return 待审核视频作品列表
     */
    public List<WorkAuditWorkEntity> findPendingVideos(int limit) {
        return findPendingWorks(MediaTypeDict.VIDEO.getCode(), limit);
    }

    /**
     * 查询待审核图片作品。
     *
     * @param limit 查询数量上限
     * @return 待审核图片作品列表
     */
    public List<WorkAuditWorkEntity> findPendingImages(int limit) {
        return findPendingWorks(MediaTypeDict.IMAGE.getCode(), limit);
    }

    /**
     * 查询待审核动图作品。
     *
     * @param limit 查询数量上限
     * @return 待审核动图作品列表
     */
    public List<WorkAuditWorkEntity> findPendingAnimations(int limit) {
        return findPendingWorks(MediaTypeDict.ANIMATION.getCode(), limit);
    }

    /**
     * 统计未审核视频作品数量。
     *
     * @return 未审核视频作品数量
     */
    public long countPendingVideos() {
        return countPendingWorks(MediaTypeDict.VIDEO.getCode());
    }

    /**
     * 统计未审核图片作品数量。
     *
     * @return 未审核图片作品数量
     */
    public long countPendingImages() {
        return countPendingWorks(MediaTypeDict.IMAGE.getCode());
    }

    /**
     * 统计未审核动图作品数量。
     *
     * @return 未审核动图作品数量
     */
    public long countPendingAnimations() {
        return countPendingWorks(MediaTypeDict.ANIMATION.getCode());
    }

    /**
     * 将作品从未审核 claim 为审核中。
     *
     * @param workId 作品 ID
     * @return 是否 claim 成功
     */
    public boolean claimPendingWork(Long workId) {
        LocalDateTime now = LocalDateTime.now();
        int updated = workMapper.update(null, Wrappers.<WorkAuditWorkEntity>lambdaUpdate()
                .set(WorkAuditWorkEntity::getAuditStatus, WorkAuditStatusDict.AUDITING.getCode())
                .set(WorkAuditWorkEntity::getAuditReasonCode, null)
                .set(WorkAuditWorkEntity::getAuditReasonCodes, null)
                .set(WorkAuditWorkEntity::getAuditRejectReason, null)
                .set(WorkAuditWorkEntity::getUpdatedAt, now)
                .setSql(VERSION_INCREMENT_SQL)
                .eq(WorkAuditWorkEntity::getId, workId)
                .eq(WorkAuditWorkEntity::getAuditStatus, WorkAuditStatusDict.PENDING.getCode())
                .eq(WorkAuditWorkEntity::getDeleted, NOT_DELETED));
        return updated == 1;
    }

    /**
     * 更新作品审核状态，默认清空拒绝原因。
     *
     * @param workId 作品 ID
     * @param auditStatus 目标审核状态
     * @return 是否更新成功
     * @deprecated 审核结果落库应使用 {@link #updateAuditStatusAndRejectReason(Long, WorkAuditStatusDict, String)}
     */
    @Deprecated(since = "0.0.1", forRemoval = false)
    public boolean updateAuditStatus(Long workId, WorkAuditStatusDict auditStatus) {
        return updateAuditStatusAndRejectReason(workId, auditStatus, null);
    }

    /**
     * 更新作品审核状态和拒绝原因。
     *
     * @param workId 作品 ID
     * @param auditStatus 目标审核状态
     * @param auditRejectReason 审核拒绝原因，审核通过时为空
     * @return 是否更新成功
     */
    public boolean updateAuditStatusAndRejectReason(Long workId, WorkAuditStatusDict auditStatus,
                                                    String auditRejectReason) {
        return updateAuditStatusAndReasons(workId, auditStatus, null, null, auditRejectReason);
    }

    /**
     * 同时更新作品审核状态、稳定风险类型和内部原因摘要。
     *
     * @param workId 作品 ID
     * @param auditStatus 目标审核状态
     * @param auditReasonCode 稳定风险类型，审核通过或处理中时为空
     * @param auditReasonCodes 当前轮次全部稳定风险类型 JSON 数组，审核通过或处理中时为空
     * @param auditRejectReason 内部审核原因摘要，审核通过或处理中时为空
     * @return 是否更新成功
     */
    public boolean updateAuditStatusAndReasons(Long workId, WorkAuditStatusDict auditStatus,
                                               String auditReasonCode, String auditReasonCodes,
                                               String auditRejectReason) {
        LocalDateTime now = LocalDateTime.now();
        int updated = workMapper.update(null, Wrappers.<WorkAuditWorkEntity>lambdaUpdate()
                .set(WorkAuditWorkEntity::getAuditStatus, auditStatus.getCode())
                .set(WorkAuditWorkEntity::getAuditReasonCode, auditReasonCode)
                .set(WorkAuditWorkEntity::getAuditReasonCodes, auditReasonCodes)
                .set(WorkAuditWorkEntity::getAuditRejectReason, auditRejectReason)
                .set(WorkAuditWorkEntity::getUpdatedAt, now)
                .setSql(VERSION_INCREMENT_SQL)
                .eq(WorkAuditWorkEntity::getId, workId)
                .eq(WorkAuditWorkEntity::getDeleted, NOT_DELETED));
        return updated == 1;
    }

    /**
     * 仅在作品仍属于指定审核轮次时更新审核结果，避免旧任务覆盖重新审核结果。
     *
     * @param workId 作品 ID
     * @param auditRound 审核轮次
     * @param auditStatus 目标审核状态
     * @param auditReasonCode 稳定风险类型
     * @param auditReasonCodes 全部稳定风险类型 JSON
     * @param auditRejectReason 内部审核原因摘要
     * @return 是否更新成功
     */
    public boolean updateAuditStatusAndReasonsForRound(
            Long workId, Integer auditRound, WorkAuditStatusDict auditStatus,
            String auditReasonCode, String auditReasonCodes, String auditRejectReason) {
        LocalDateTime now = LocalDateTime.now();
        int updated = workMapper.update(null, Wrappers.<WorkAuditWorkEntity>lambdaUpdate()
                .set(WorkAuditWorkEntity::getAuditStatus, auditStatus.getCode())
                .set(WorkAuditWorkEntity::getAuditReasonCode, auditReasonCode)
                .set(WorkAuditWorkEntity::getAuditReasonCodes, auditReasonCodes)
                .set(WorkAuditWorkEntity::getAuditRejectReason, auditRejectReason)
                .set(WorkAuditWorkEntity::getUpdatedAt, now)
                .setSql(VERSION_INCREMENT_SQL)
                .eq(WorkAuditWorkEntity::getId, workId)
                .eq(WorkAuditWorkEntity::getAuditRound, auditRound)
                .eq(WorkAuditWorkEntity::getDeleted, NOT_DELETED));
        return updated == 1;
    }

    private List<WorkAuditWorkEntity> findPendingWorks(String mediaType, int limit) {
        return workMapper.selectList(Wrappers.<WorkAuditWorkEntity>lambdaQuery()
                .eq(WorkAuditWorkEntity::getAuditStatus, WorkAuditStatusDict.PENDING.getCode())
                .eq(WorkAuditWorkEntity::getMediaType, mediaType)
                .eq(WorkAuditWorkEntity::getDeleted, NOT_DELETED)
                .orderByAsc(WorkAuditWorkEntity::getId)
                // limit 已归一化为非负整数，拼接 LIMIT 子句不会引入 SQL 注入风险。
                .last(LIMIT_SQL_PREFIX + normalizedLimit(limit)));
    }

    private long countPendingWorks(String mediaType) {
        return workMapper.selectCount(Wrappers.<WorkAuditWorkEntity>lambdaQuery()
                .eq(WorkAuditWorkEntity::getAuditStatus, WorkAuditStatusDict.PENDING.getCode())
                .eq(WorkAuditWorkEntity::getMediaType, mediaType)
                .eq(WorkAuditWorkEntity::getDeleted, NOT_DELETED));
    }

    private int normalizedLimit(int limit) {
        return Math.max(limit, 0);
    }
}
