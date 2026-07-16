package com.jxc.wefolio.service;

import com.alibaba.fastjson2.JSON;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.jxc.wefolio.dict.PortfolioOwnerTypeDict;
import com.jxc.wefolio.dict.PortfolioTypeDict;
import com.jxc.wefolio.dict.BillingWindowScopeDict;
import com.jxc.wefolio.dict.PointSceneCodeDict;
import com.jxc.wefolio.dict.VisitEventTypeDict;
import com.jxc.wefolio.dict.VisitSourceTypeDict;
import com.jxc.wefolio.dto.PortfolioConfigDto;
import com.jxc.wefolio.dto.VisitorPortfolioEventRequest;
import com.jxc.wefolio.entity.PortfolioEntity;
import com.jxc.wefolio.entity.VisitEventEntity;
import com.jxc.wefolio.entity.VisitRecordEntity;
import com.jxc.wefolio.mapper.VisitEventEntityMapper;
import com.jxc.wefolio.mapper.VisitRecordEntityMapper;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Map;

/**
 * 作品集访问服务 — 负责访客访问汇总、事件明细和访客侧扣费。
 */
@Service
@RequiredArgsConstructor
public class PortfolioVisitService {

    /** 打开个人作品集业务类型 */
    private static final String BUSINESS_TYPE_PORTFOLIO_OPEN = "PORTFOLIO_OPEN";

    /** 图片查看业务类型 */
    private static final String BUSINESS_TYPE_PORTFOLIO_IMAGE = "PORTFOLIO_IMAGE";

    /** 视频播放业务类型 */
    private static final String BUSINESS_TYPE_PORTFOLIO_VIDEO = "PORTFOLIO_VIDEO";

    /** 打开个人作品集备注 */
    private static final String REMARK_PORTFOLIO_OPEN = "访客打开个人作品集";

    /** 查看图片备注 */
    private static final String REMARK_IMAGE_VIEW = "访客查看作品集图片";

    /** 播放视频备注 */
    private static final String REMARK_VIDEO_PLAY = "访客播放作品集视频";

    /** 打开计费幂等前缀，需给业务 ID 预留数据库长度 */
    private static final String OPEN_IDEMPOTENCY_PREFIX = "PF_OPEN:";

    /** 业务 ID 分隔符 */
    private static final String BUSINESS_ID_SEPARATOR = ":";

    /** MySQL 单条限制片段 */
    private static final String SQL_SINGLE_LIMIT_CLAUSE = "LIMIT 1";

    /** 作品集标题快照兜底 */
    private static final String DEFAULT_PORTFOLIO_TITLE_SNAPSHOT = "个人作品集";

    /** 分享编码快照兜底 */
    private static final String DEFAULT_PORTFOLIO_SHARE_CODE_SNAPSHOT = "";

    /** 访问汇总 Mapper */
    private final VisitRecordEntityMapper visitRecordEntityMapper;

    /** 访问事件 Mapper */
    private final VisitEventEntityMapper visitEventEntityMapper;

    /** 积分滚动扣费窗口服务 */
    private final PointBillingWindowService pointBillingWindowService;

    /**
     * 记录作品集打开。
     *
     * @param portfolio 作品集
     * @param visitorId 全局访客 ID
     * @param visitorKey 访客摘要
     * @param sourceType 来源
     * @param idempotencyKey 事件幂等键
     * @return 访问汇总
     */
    @Transactional(rollbackFor = Exception.class)
    public VisitRecordEntity recordOpen(
            PortfolioEntity portfolio,
            Long visitorId,
            String visitorKey,
            String sourceType,
            String idempotencyKey
    ) {
        LocalDateTime now = LocalDateTime.now();
        VisitRecordEntity record = findRecord(portfolio.getId(), visitorId, visitorKey);
        if (record == null) {
            record = new VisitRecordEntity();
            record.setVisitorId(visitorId);
            record.setVisitorKey(visitorKey);
            record.setPortfolioId(portfolio.getId());
            fillPortfolioSnapshot(record, portfolio);
            record.setLastPortfolioRevision(portfolio.getPublishedRevision());
            record.setPortfolioType(PortfolioTypeDict.PERSONAL.getCode());
            record.setOwnerType(portfolio.getOwnerType());
            record.setOwnerId(portfolio.getOwnerId());
            record.setSourceType(defaultSource(sourceType));
            record.setVisitCount(1);
            record.setViewWorkCount(0);
            record.setPlayVideoCount(0);
            record.setScheduleQueryCount(0);
            record.setQrActionCount(0);
            record.setContactSubmitCount(0);
            record.setTotalDurationSeconds(0);
            record.setFirstVisitedAt(now);
            record.setLastVisitedAt(now);
            visitRecordEntityMapper.insert(record);
        } else {
            if (visitorId != null) {
                record.setVisitorId(visitorId);
            }
            record.setVisitCount(safeInt(record.getVisitCount()) + 1);
            fillPortfolioSnapshot(record, portfolio);
            record.setLastPortfolioRevision(portfolio.getPublishedRevision());
            record.setLastVisitedAt(now);
            visitRecordEntityMapper.updateById(record);
        }
        consumePortfolioOpen(portfolio, visitorId, idempotencyKey);
        insertEvent(record, portfolio, VisitEventTypeDict.PORTFOLIO_OPENED.getCode(), null, null, null,
                idempotencyKey, null, now);
        return record;
    }

    /**
     * 填充被访问作品集快照，保证作品集删除后访问历史仍可展示上下文。
     *
     * @param record 访问汇总
     * @param portfolio 作品集
     */
    private void fillPortfolioSnapshot(VisitRecordEntity record, PortfolioEntity portfolio) {
        record.setPortfolioTitleSnapshot(resolvePortfolioTitleSnapshot(portfolio));
        record.setPortfolioShareCodeSnapshot(resolvePortfolioShareCodeSnapshot(portfolio));
    }

    /**
     * 解析作品集标题快照。
     *
     * @param portfolio 作品集
     * @return 标题快照
     */
    private String resolvePortfolioTitleSnapshot(PortfolioEntity portfolio) {
        if (portfolio == null || portfolio.getPublishedConfigJson() == null
                || portfolio.getPublishedConfigJson().isBlank()) {
            return DEFAULT_PORTFOLIO_TITLE_SNAPSHOT;
        }
        try {
            PortfolioConfigDto config = JSON.parseObject(portfolio.getPublishedConfigJson(), PortfolioConfigDto.class);
            if (config != null && config.getShare() != null && config.getShare().getTitle() != null
                    && !config.getShare().getTitle().isBlank()) {
                return config.getShare().getTitle().strip();
            }
            return DEFAULT_PORTFOLIO_TITLE_SNAPSHOT;
        } catch (Exception e) {
            return DEFAULT_PORTFOLIO_TITLE_SNAPSHOT;
        }
    }

    /**
     * 解析作品集分享编码快照。
     *
     * @param portfolio 作品集
     * @return 分享编码快照
     */
    private String resolvePortfolioShareCodeSnapshot(PortfolioEntity portfolio) {
        if (portfolio == null || portfolio.getShareCode() == null || portfolio.getShareCode().isBlank()) {
            return DEFAULT_PORTFOLIO_SHARE_CODE_SNAPSHOT;
        }
        return portfolio.getShareCode().strip();
    }

    /**
     * 记录普通访客事件。
     *
     * @param portfolio 作品集
     * @param visitorId 全局访客 ID
     * @param request 事件请求
     */
    @Transactional(rollbackFor = Exception.class)
    public void recordEvent(
            PortfolioEntity portfolio,
            Long visitorId,
            VisitorPortfolioEventRequest request
    ) {
        if (hasRecordedEvent(request.getIdempotencyKey())) {
            return;
        }
        VisitRecordEntity record = requireRecord(portfolio.getId(), request.getVisitorKey());
        String eventType = request.getEventType();
        LocalDateTime now = LocalDateTime.now();
        boolean inserted = insertEventIfAbsent(record, portfolio, eventType, request.getWorkId(), request.getQueriedDate(),
                request.getDurationSeconds(), request.getIdempotencyKey(), request.getMetadata(), now);
        if (!inserted) {
            return;
        }
        if (VisitEventTypeDict.WORK_VIEWED.getCode().equals(eventType)) {
            record.setViewWorkCount(safeInt(record.getViewWorkCount()) + 1);
            pointBillingWindowService.consumeIfEligible(
                    portfolio.getOwnerId(),
                    visitorId,
                    PointSceneCodeDict.VIEW_PORTFOLIO_IMAGES.getCode(),
                    BillingWindowScopeDict.WORK.getCode(),
                    request.getWorkId(),
                    BUSINESS_TYPE_PORTFOLIO_IMAGE,
                    buildWorkBillingBusinessId(portfolio.getId(), request.getWorkId(), request.getVisitorKey()),
                    request.getIdempotencyKey(),
                    REMARK_IMAGE_VIEW
            );
        } else if (VisitEventTypeDict.VIDEO_PLAYED.getCode().equals(eventType)) {
            record.setPlayVideoCount(safeInt(record.getPlayVideoCount()) + 1);
            pointBillingWindowService.consumeIfEligible(
                    portfolio.getOwnerId(),
                    visitorId,
                    PointSceneCodeDict.VIEW_PORTFOLIO_VIDEO.getCode(),
                    BillingWindowScopeDict.WORK.getCode(),
                    request.getWorkId(),
                    BUSINESS_TYPE_PORTFOLIO_VIDEO,
                    buildWorkBillingBusinessId(portfolio.getId(), request.getWorkId(), request.getVisitorKey()),
                    request.getIdempotencyKey(),
                    REMARK_VIDEO_PLAY
            );
        } else if (VisitEventTypeDict.QR_CODE_INTERACTED.getCode().equals(eventType)) {
            record.setQrActionCount(safeInt(record.getQrActionCount()) + 1);
        } else if (VisitEventTypeDict.CONTACT_FORM_EXPOSED.getCode().equals(eventType)) {
            record.setLastVisitedAt(now);
        }
        visitRecordEntityMapper.updateById(record);
    }

    /**
     * 判断事件幂等键是否已入库，避免客户端重试导致重复计数或重复扣费。
     *
     * @param idempotencyKey 事件幂等键
     * @return 是否已有事件
     */
    private boolean hasRecordedEvent(String idempotencyKey) {
        if (idempotencyKey == null || idempotencyKey.isBlank()) {
            return false;
        }
        VisitEventEntity existing = visitEventEntityMapper.selectOne(
                Wrappers.lambdaQuery(VisitEventEntity.class)
                        .eq(VisitEventEntity::getIdempotencyKey, idempotencyKey)
                        .last(SQL_SINGLE_LIMIT_CLAUSE)
        );
        return existing != null;
    }

    /**
     * 构建作品媒体扣费流水业务 ID。
     *
     * @param portfolioId 作品集 ID
     * @param workId 作品 ID
     * @param visitorKey 访客摘要
     * @return 积分业务 ID
     */
    private String buildWorkBillingBusinessId(Long portfolioId, Long workId, String visitorKey) {
        return portfolioId + BUSINESS_ID_SEPARATOR + workId + BUSINESS_ID_SEPARATOR + visitorKey;
    }

    /**
     * 记录档期查询事件。
     *
     * @param portfolio 作品集
     * @param visitorKey 访客摘要
     * @param queriedDate 查询日期
     * @param idempotencyKey 幂等键
     */
    @Transactional(rollbackFor = Exception.class)
    public ScheduleQueryRecordResult recordScheduleQuery(
            PortfolioEntity portfolio,
            String visitorKey,
            LocalDate queriedDate,
            String idempotencyKey
    ) {
        return recordScheduleQuery(portfolio, visitorKey, queriedDate, null, idempotencyKey);
    }

    /**
     * 记录档期查询事件。
     *
     * @param portfolio 作品集
     * @param visitorKey 访客摘要
     * @param queriedDate 查询日期
     * @param metadata 查询档位等扩展元数据
     * @param idempotencyKey 幂等键
     * @return 本次查档事件写入结果
     */
    @Transactional(rollbackFor = Exception.class)
    public ScheduleQueryRecordResult recordScheduleQuery(
            PortfolioEntity portfolio,
            String visitorKey,
            LocalDate queriedDate,
            Map<String, Object> metadata,
            String idempotencyKey
    ) {
        if (hasRecordedEvent(idempotencyKey)) {
            return ScheduleQueryRecordResult.skipped(null);
        }
        VisitRecordEntity record = findRecord(portfolio.getId(), visitorKey);
        if (record == null) {
            return ScheduleQueryRecordResult.skipped(null);
        }
        LocalDateTime now = LocalDateTime.now();
        boolean inserted = insertEventIfAbsent(record, portfolio, VisitEventTypeDict.SCHEDULE_QUERIED.getCode(),
                null, queriedDate, null, idempotencyKey, metadata, now);
        if (!inserted) {
            return ScheduleQueryRecordResult.concurrentConflict(record, now);
        }
        record.setScheduleQueryCount(safeInt(record.getScheduleQueryCount()) + 1);
        visitRecordEntityMapper.updateById(record);
        return ScheduleQueryRecordResult.recorded(record, now);
    }

    /**
     * 记录联系线索提交事件。
     *
     * @param portfolio 作品集
     * @param visitorKey 访客摘要
     * @param leadId 线索 ID
     * @param idempotencyKey 幂等键
     */
    @Transactional(rollbackFor = Exception.class)
    public void recordContactLeadSubmitted(PortfolioEntity portfolio, String visitorKey, Long leadId, String idempotencyKey) {
        VisitRecordEntity record = findRecord(portfolio.getId(), visitorKey);
        if (record != null) {
            record.setContactSubmitCount(safeInt(record.getContactSubmitCount()) + 1);
            visitRecordEntityMapper.updateById(record);
            insertEvent(record, portfolio, VisitEventTypeDict.CONTACT_LEAD_SUBMITTED.getCode(), null, null,
                    null, idempotencyKey, Map.of("leadId", leadId), LocalDateTime.now());
        }
    }

    /**
     * 查询访问汇总。
     *
     * @param portfolioId 作品集 ID
     * @param visitorKey 访客摘要
     * @return 访问汇总
     */
    private VisitRecordEntity findRecord(Long portfolioId, String visitorKey) {
        return findRecord(portfolioId, null, visitorKey);
    }

    /**
     * 查询访问汇总。
     *
     * @param portfolioId 作品集 ID
     * @param visitorId 全局访客 ID
     * @param visitorKey 访客摘要
     * @return 访问汇总
     */
    private VisitRecordEntity findRecord(Long portfolioId, Long visitorId, String visitorKey) {
        if (visitorId != null) {
            VisitRecordEntity record = visitRecordEntityMapper.selectOne(
                    Wrappers.lambdaQuery(VisitRecordEntity.class)
                            .eq(VisitRecordEntity::getPortfolioId, portfolioId)
                            .eq(VisitRecordEntity::getVisitorId, visitorId)
                            .last(SQL_SINGLE_LIMIT_CLAUSE)
            );
            if (record != null) {
                return record;
            }
        }
        if (visitorKey == null) {
            return null;
        }
        return visitRecordEntityMapper.selectOne(
                Wrappers.lambdaQuery(VisitRecordEntity.class)
                        .eq(VisitRecordEntity::getPortfolioId, portfolioId)
                        .eq(VisitRecordEntity::getVisitorKey, visitorKey)
                        .last(SQL_SINGLE_LIMIT_CLAUSE)
        );
    }

    /**
     * 要求访问汇总存在。
     *
     * @param portfolioId 作品集 ID
     * @param visitorKey 访客摘要
     * @return 访问汇总
     */
    private VisitRecordEntity requireRecord(Long portfolioId, String visitorKey) {
        VisitRecordEntity record = findRecord(portfolioId, visitorKey);
        if (record == null) {
            record = new VisitRecordEntity();
            record.setVisitorKey(visitorKey);
            record.setPortfolioId(portfolioId);
            record.setVisitCount(0);
            record.setViewWorkCount(0);
            record.setPlayVideoCount(0);
            record.setScheduleQueryCount(0);
            record.setQrActionCount(0);
            record.setContactSubmitCount(0);
        }
        return record;
    }

    /**
     * 消费打开个人作品集积分。
     *
     * @param portfolio 作品集
     * @param visitorId 全局访客 ID
     * @param idempotencyKey 打开事件幂等键
     */
    private void consumePortfolioOpen(PortfolioEntity portfolio, Long visitorId, String idempotencyKey) {
        String businessId = portfolio.getId() + BUSINESS_ID_SEPARATOR + visitorId;
        String pointIdempotencyKey = idempotencyKey == null || idempotencyKey.isBlank()
                ? null
                : OPEN_IDEMPOTENCY_PREFIX + idempotencyKey.strip();
        pointBillingWindowService.consumeIfEligible(
                portfolio.getOwnerId(),
                visitorId,
                PointSceneCodeDict.VISIT_PERSONAL_PORTFOLIO.getCode(),
                BillingWindowScopeDict.PORTFOLIO.getCode(),
                portfolio.getId(),
                BUSINESS_TYPE_PORTFOLIO_OPEN,
                businessId,
                pointIdempotencyKey,
                REMARK_PORTFOLIO_OPEN
        );
    }

    /**
     * 写入事件。
     *
     * @param record 访问汇总
     * @param portfolio 作品集
     * @param eventType 事件类型
     * @param workId 作品 ID
     * @param queriedDate 查询日期
     * @param durationSeconds 时长
     * @param idempotencyKey 幂等键
     * @param metadata 元数据
     * @param occurredAt 发生时间
     */
    private void insertEvent(
            VisitRecordEntity record,
            PortfolioEntity portfolio,
            String eventType,
            Long workId,
            LocalDate queriedDate,
            Integer durationSeconds,
            String idempotencyKey,
            Map<String, Object> metadata,
            LocalDateTime occurredAt
    ) {
        VisitEventEntity event = new VisitEventEntity();
        event.setVisitRecordId(record.getId());
        event.setPortfolioId(portfolio.getId());
        event.setPortfolioRevision(portfolio.getPublishedRevision());
        event.setVisitorKey(record.getVisitorKey());
        event.setEventType(eventType);
        event.setWorkId(workId);
        event.setOwnerType(portfolio.getOwnerType());
        event.setOwnerId(portfolio.getOwnerId());
        event.setQueriedDate(queriedDate);
        event.setDurationSeconds(durationSeconds);
        event.setIdempotencyKey(idempotencyKey);
        event.setMetadata(metadata == null ? null : JSON.toJSONString(metadata));
        event.setOccurredAt(occurredAt);
        visitEventEntityMapper.insert(event);
    }

    /**
     * 尝试先写入事件，用数据库唯一索引兜底处理并发幂等请求。
     *
     * @param record 访问汇总
     * @param portfolio 作品集
     * @param eventType 事件类型
     * @param workId 作品 ID
     * @param queriedDate 查询日期
     * @param durationSeconds 时长
     * @param idempotencyKey 幂等键
     * @param metadata 元数据
     * @param occurredAt 发生时间
     * @return 是否成功写入新事件
     */
    private boolean insertEventIfAbsent(
            VisitRecordEntity record,
            PortfolioEntity portfolio,
            String eventType,
            Long workId,
            LocalDate queriedDate,
            Integer durationSeconds,
            String idempotencyKey,
            Map<String, Object> metadata,
            LocalDateTime occurredAt
    ) {
        try {
            insertEvent(record, portfolio, eventType, workId, queriedDate, durationSeconds, idempotencyKey, metadata, occurredAt);
            return true;
        } catch (DuplicateKeyException e) {
            if (idempotencyKey != null && !idempotencyKey.isBlank()) {
                return false;
            }
            throw e;
        }
    }

    /**
     * 来源兜底。
     *
     * @param sourceType 原来源
     * @return 来源编码
     */
    private String defaultSource(String sourceType) {
        return VisitSourceTypeDict.fromCode(sourceType) == null
                ? VisitSourceTypeDict.UNKNOWN.getCode()
                : sourceType;
    }

    /**
     * 安全整数。
     *
     * @param value 原值
     * @return 非空整数
     */
    private int safeInt(Integer value) {
        return value == null ? 0 : value;
    }

    /**
     * 查档事件写入结果。
     */
    @Getter
    public static final class ScheduleQueryRecordResult {

        /** 关联访问汇总，未命中访问记录时为空 */
        private final VisitRecordEntity record;

        /** 是否新写入查档事件 */
        private final boolean recorded;

        /** 是否允许写入查档业务快照 */
        private final boolean snapshotRecordable;

        /** 事件发生时间 */
        private final LocalDateTime occurredAt;

        private ScheduleQueryRecordResult(
                VisitRecordEntity record,
                boolean recorded,
                boolean snapshotRecordable,
                LocalDateTime occurredAt
        ) {
            this.record = record;
            this.recorded = recorded;
            this.snapshotRecordable = snapshotRecordable;
            this.occurredAt = occurredAt;
        }

        /**
         * 构建新写入结果。
         *
         * @param record 访问汇总
         * @param occurredAt 事件发生时间
         * @return 写入结果
         */
        public static ScheduleQueryRecordResult recorded(VisitRecordEntity record, LocalDateTime occurredAt) {
            return new ScheduleQueryRecordResult(record, true, true, occurredAt);
        }

        /**
         * 构建并发幂等冲突结果。
         *
         * @param record 访问汇总
         * @param occurredAt 本次查询发生时间
         * @return 并发冲突结果
         */
        public static ScheduleQueryRecordResult concurrentConflict(VisitRecordEntity record, LocalDateTime occurredAt) {
            return new ScheduleQueryRecordResult(record, false, true, occurredAt);
        }

        /**
         * 构建跳过写入结果。
         *
         * @param record 访问汇总
         * @return 跳过结果
         */
        public static ScheduleQueryRecordResult skipped(VisitRecordEntity record) {
            return new ScheduleQueryRecordResult(record, false, false, null);
        }
    }
}
