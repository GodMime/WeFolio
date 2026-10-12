package com.jxc.wefolio.job.repo;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.alibaba.fastjson2.JSON;
import com.jxc.wefolio.job.dict.MediaTypeDict;
import com.jxc.wefolio.job.dict.WorkAuditStatusDict;
import com.jxc.wefolio.job.entity.WorkAuditWorkEntity;
import com.jxc.wefolio.job.entity.WorkAuditTaskEntity;
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

    /** 只在最终短事务内锁定旧清单，不跨远端操作持有锁。 */
    private static final String FOR_UPDATE_SQL = "FOR UPDATE";

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
                .isNull(WorkAuditWorkEntity::getManualAuditNo)
                .eq(WorkAuditWorkEntity::getDeleted, NOT_DELETED));
        return updated == 1;
    }

    /** 首次持久帧领取同时冻结源和审核轮次，避免扫描后源被替换仍创建旧任务。 */
    public boolean claimPendingWorkForSource(WorkAuditTaskEntity task) {
        return workMapper.update(null, Wrappers.<WorkAuditWorkEntity>lambdaUpdate()
                .set(WorkAuditWorkEntity::getAuditStatus, WorkAuditStatusDict.AUDITING.getCode())
                .set(WorkAuditWorkEntity::getAuditReasonCode, null)
                .set(WorkAuditWorkEntity::getAuditReasonCodes, null)
                .set(WorkAuditWorkEntity::getAuditRejectReason, null)
                .set(WorkAuditWorkEntity::getUpdatedAt, LocalDateTime.now())
                .setSql(VERSION_INCREMENT_SQL)
                .eq(WorkAuditWorkEntity::getId, task.getWorkId())
                .eq(WorkAuditWorkEntity::getAuditRound, task.getAuditRound())
                .eq(WorkAuditWorkEntity::getMediaObjectKey, task.getMediaObjectKey())
                .isNull(task.getMediaSha256() == null, WorkAuditWorkEntity::getMediaSha256)
                .eq(task.getMediaSha256() != null, WorkAuditWorkEntity::getMediaSha256, task.getMediaSha256())
                .eq(WorkAuditWorkEntity::getAuditStatus, WorkAuditStatusDict.PENDING.getCode())
                .isNull(WorkAuditWorkEntity::getManualAuditNo)
                .eq(WorkAuditWorkEntity::getDeleted, NOT_DELETED)) == 1;
    }

    /** 新视频最终只更新审核字段和完整清单，保留用户中途编辑的作品字段。 */
    public boolean updatePersistentVideoForRound(WorkAuditTaskEntity task, WorkAuditStatusDict auditStatus,
                                                String reasonCode, String reasonCodes, String reason,
                                                String manifestJson) {
        return workMapper.update(null, Wrappers.<WorkAuditWorkEntity>lambdaUpdate()
                .set(WorkAuditWorkEntity::getAuditStatus, auditStatus.getCode())
                .set(WorkAuditWorkEntity::getAuditReasonCode, reasonCode)
                .set(WorkAuditWorkEntity::getAuditReasonCodes, reasonCodes)
                .set(WorkAuditWorkEntity::getAuditRejectReason, reason)
                .set(manifestJson != null, WorkAuditWorkEntity::getVideoFramesJson, manifestJson)
                .set(WorkAuditWorkEntity::getUpdatedAt, LocalDateTime.now())
                .setSql(VERSION_INCREMENT_SQL)
                .eq(WorkAuditWorkEntity::getId, task.getWorkId())
                .eq(WorkAuditWorkEntity::getAuditRound, task.getAuditRound())
                .eq(WorkAuditWorkEntity::getMediaObjectKey, task.getMediaObjectKey())
                .isNull(task.getMediaSha256() == null, WorkAuditWorkEntity::getMediaSha256)
                .eq(task.getMediaSha256() != null, WorkAuditWorkEntity::getMediaSha256, task.getMediaSha256())
                .eq(WorkAuditWorkEntity::getAuditStatus, WorkAuditStatusDict.AUDITING.getCode())
                .isNull(WorkAuditWorkEntity::getManualAuditNo)
                .eq(WorkAuditWorkEntity::getDeleted, NOT_DELETED)) == 1;
    }

    /** 检查最终保存是否已经引用本次清单，保护结果不确定时的有效对象。 */
    public boolean isVideoManifestPublished(Long workId, String manifestJson) {
        WorkAuditWorkEntity work = workMapper.selectOne(Wrappers.<WorkAuditWorkEntity>lambdaQuery()
                .select(WorkAuditWorkEntity::getVideoFramesJson)
                .eq(WorkAuditWorkEntity::getId, workId)
                .eq(WorkAuditWorkEntity::getDeleted, NOT_DELETED));
        // MySQL JSON 列可能重排键和空白，语义相等的已发布清单必须保护。
        return work != null && work.getVideoFramesJson() != null
                && JSON.parseObject(manifestJson).equals(JSON.parseObject(work.getVideoFramesJson()));
    }

    /** 保存新清单之前读取并锁住当前清单，避免重审持续遗留旧持久对象。 */
    public String lockVideoManifest(Long workId) {
        WorkAuditWorkEntity work = workMapper.selectOne(Wrappers.<WorkAuditWorkEntity>lambdaQuery()
                .select(WorkAuditWorkEntity::getVideoFramesJson)
                .eq(WorkAuditWorkEntity::getId, workId)
                .eq(WorkAuditWorkEntity::getDeleted, NOT_DELETED)
                .last(FOR_UPDATE_SQL));
        return work == null ? null : work.getVideoFramesJson();
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
                .eq(WorkAuditWorkEntity::getAuditStatus, WorkAuditStatusDict.AUDITING.getCode())
                .isNull(WorkAuditWorkEntity::getManualAuditNo)
                .eq(WorkAuditWorkEntity::getDeleted, NOT_DELETED));
        return updated == 1;
    }

    private List<WorkAuditWorkEntity> findPendingWorks(String mediaType, int limit) {
        return workMapper.selectList(Wrappers.<WorkAuditWorkEntity>lambdaQuery()
                .eq(WorkAuditWorkEntity::getAuditStatus, WorkAuditStatusDict.PENDING.getCode())
                .in(WorkAuditWorkEntity::getMediaType, asyncMediaTypes(mediaType))
                .isNull(WorkAuditWorkEntity::getManualAuditNo)
                .eq(WorkAuditWorkEntity::getDeleted, NOT_DELETED)
                .orderByAsc(WorkAuditWorkEntity::getId)
                // limit 已归一化为非负整数，拼接 LIMIT 子句不会引入 SQL 注入风险。
                .last(LIMIT_SQL_PREFIX + normalizedLimit(limit)));
    }

    private long countPendingWorks(String mediaType) {
        return workMapper.selectCount(Wrappers.<WorkAuditWorkEntity>lambdaQuery()
                .eq(WorkAuditWorkEntity::getAuditStatus, WorkAuditStatusDict.PENDING.getCode())
                .in(WorkAuditWorkEntity::getMediaType, asyncMediaTypes(mediaType))
                .isNull(WorkAuditWorkEntity::getManualAuditNo)
                .eq(WorkAuditWorkEntity::getDeleted, NOT_DELETED));
    }

    private int normalizedLimit(int limit) {
        return Math.max(limit, 0);
    }

    /** 原视频异步队列同时承接音频，保留原批次容量、排序与恢复机制。 */
    private List<String> asyncMediaTypes(String mediaType) {
        return MediaTypeDict.VIDEO.getCode().equals(mediaType)
                ? List.of(mediaType, MediaTypeDict.AUDIO.getCode()) : List.of(mediaType);
    }
}
