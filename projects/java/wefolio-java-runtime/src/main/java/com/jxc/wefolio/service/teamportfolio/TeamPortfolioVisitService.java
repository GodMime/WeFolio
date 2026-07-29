package com.jxc.wefolio.service.teamportfolio;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.jxc.wefolio.constant.TeamPortfolioConstants;
import com.jxc.wefolio.dict.FollowStatusDict;
import com.jxc.wefolio.dict.MediaTypeDict;
import com.jxc.wefolio.dict.PortfolioConfigScopeDict;
import com.jxc.wefolio.dict.PortfolioOwnerTypeDict;
import com.jxc.wefolio.dict.PortfolioPublicationStatusDict;
import com.jxc.wefolio.dict.PortfolioStatusDict;
import com.jxc.wefolio.dict.PortfolioTemplateTypeDict;
import com.jxc.wefolio.dict.PortfolioTypeDict;
import com.jxc.wefolio.dict.ReferenceTypeDict;
import com.jxc.wefolio.dict.VisitEventTypeDict;
import com.jxc.wefolio.dict.VisitSourceTypeDict;
import com.jxc.wefolio.dto.teamportfolio.TeamPortfolioConfigDto;
import com.jxc.wefolio.dto.teamportfolio.TeamPortfolioScheduleQueryRequest;
import com.jxc.wefolio.dto.teamportfolio.VisitorTeamPortfolioEventRequest;
import com.jxc.wefolio.entity.PortfolioEntity;
import com.jxc.wefolio.entity.PortfolioReferenceEntity;
import com.jxc.wefolio.entity.VisitEventEntity;
import com.jxc.wefolio.entity.VisitRecordEntity;
import com.jxc.wefolio.entity.WorkEntity;
import com.jxc.wefolio.exception.BusinessException;
import com.jxc.wefolio.mapper.PortfolioReferenceEntityMapper;
import com.jxc.wefolio.mapper.VisitEventEntityMapper;
import com.jxc.wefolio.mapper.VisitRecordEntityMapper;
import com.jxc.wefolio.mapper.WorkEntityMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;

/**
 * 标准团队作品集访问汇总、事件幂等与发布资源归属服务。
 */
@Service
@RequiredArgsConstructor
public class TeamPortfolioVisitService {

    /** 查询单条记录限制。 */
    private static final String QUERY_LIMIT_ONE = "LIMIT 1";

    /** 唯一键竞争后查询当前已提交赢家并加锁。 */
    private static final String QUERY_LIMIT_ONE_FOR_UPDATE = "LIMIT 1 FOR UPDATE";

    /** 团队作品集不可用提示。 */
    private static final String PORTFOLIO_INVALID_MESSAGE = "当前作品集暂未开放访问";

    /** 访客身份无效提示。 */
    private static final String VISITOR_INVALID_MESSAGE = "访客身份无效";

    /** 访问记录无效提示。 */
    private static final String VISIT_RECORD_INVALID_MESSAGE = "团队作品集访问记录无效";

    /** 事件请求无效提示。 */
    private static final String EVENT_INVALID_MESSAGE = "团队作品集访问事件不合法";

    /** 事件幂等键冲突提示。 */
    private static final String IDEMPOTENCY_CONFLICT_MESSAGE = "访问事件幂等键已用于其他业务";

    /** 访问记录写入失败提示。 */
    private static final String RECORD_PERSISTENCE_FAILED_MESSAGE = "团队作品集访问记录保存失败";

    /** 事件写入失败提示。 */
    private static final String EVENT_PERSISTENCE_FAILED_MESSAGE = "团队作品集访问事件保存失败";

    /** 默认团队作品集标题。 */
    private static final String DEFAULT_PORTFOLIO_TITLE = "团队作品集";

    /** 访客键最大长度。 */
    private static final int VISITOR_KEY_MAX_LENGTH = 64;

    /** 幂等键最大 UTF-8 字节数。 */
    private static final int IDEMPOTENCY_KEY_MAX_BYTES = 64;

    /** 组件实例键最大 UTF-8 字节数。 */
    private static final int COMPONENT_KEY_MAX_BYTES = 128;

    /** 编码字段最大 UTF-8 字节数。 */
    private static final int CODE_VALUE_MAX_BYTES = 64;

    /** metadata 键最大 UTF-8 字节数。 */
    private static final int METADATA_KEY_MAX_BYTES = 64;

    /** metadata 值最大 UTF-8 字节数。 */
    private static final int METADATA_VALUE_MAX_BYTES = 256;

    /** metadata 最大条目数。 */
    private static final int METADATA_MAX_ENTRIES = 4;

    /** metadata JSON 最大 UTF-8 字节数。 */
    private static final int METADATA_MAX_BYTES = 2048;

    /** 单次时长最大秒数。 */
    private static final int DURATION_MAX_SECONDS = 86400;

    /** 二维码点击动作。 */
    private static final String QR_ACTION_CLICK = "CLICK";

    /** 二维码长按动作。 */
    private static final String QR_ACTION_LONG_PRESS = "LONG_PRESS";

    /** metadata 组件键。 */
    private static final String METADATA_COMPONENT_KEY = "componentKey";

    /** metadata 成员作品集 ID 键。 */
    private static final String METADATA_MEMBER_PORTFOLIO_ID = "memberPortfolioId";

    /** metadata 线索 ID 键。 */
    private static final String METADATA_LEAD_ID = "leadId";

    /** metadata 媒体类型键。 */
    private static final String METADATA_MEDIA_TYPE = "mediaType";

    /** metadata 二维码动作键。 */
    private static final String METADATA_ACTION = "action";

    /** metadata 访问来源键。 */
    private static final String METADATA_SOURCE_TYPE = "sourceType";

    /** 联系表单组件类型。 */
    private static final String COMPONENT_TYPE_CONTACT_FORM = "CONTACT_FORM";

    /** 档期查询组件类型。 */
    private static final String COMPONENT_TYPE_SCHEDULE_QUERY = "SCHEDULE_QUERY";

    /** 允许由公共事件接口上报的客户端事件类型。 */
    private static final Set<VisitEventTypeDict> CLIENT_EVENT_TYPES = Set.of(
            VisitEventTypeDict.WORK_VIEWED,
            VisitEventTypeDict.VIDEO_PLAYED,
            VisitEventTypeDict.QR_CODE_INTERACTED,
            VisitEventTypeDict.MEMBER_PORTFOLIO_OPENED,
            VisitEventTypeDict.CONTACT_FORM_EXPOSED);

    /** 允许的二维码动作。 */
    private static final Set<String> QR_ACTIONS = Set.of(QR_ACTION_CLICK, QR_ACTION_LONG_PRESS);

    /** 访问汇总数据访问器。 */
    private final VisitRecordEntityMapper visitRecordEntityMapper;

    /** 访问事件数据访问器。 */
    private final VisitEventEntityMapper visitEventEntityMapper;

    /** 作品集发布引用数据访问器。 */
    private final PortfolioReferenceEntityMapper portfolioReferenceEntityMapper;

    /** 可信作品主数据访问器。 */
    private final WorkEntityMapper workEntityMapper;

    /**
     * 记录团队作品集打开行为。
     *
     * @param portfolio 已发布标准团队作品集
     * @param visitorId 已认证访客 ID
     * @param visitorKey 已认证访客稳定键
     * @param sourceType 访问来源
     * @param idempotencyKey 打开事件幂等键
     * @return 团队访问汇总
     */
    @Transactional(rollbackFor = Exception.class)
    public VisitRecordEntity recordOpen(
            PortfolioEntity portfolio,
            Long visitorId,
            String visitorKey,
            String sourceType,
            String idempotencyKey
    ) {
        validatePortfolio(portfolio);
        validateVisitor(visitorId, visitorKey);
        VisitorTeamPortfolioEventRequest event = new VisitorTeamPortfolioEventRequest();
        event.setEventType(VisitEventTypeDict.PORTFOLIO_OPENED.getCode());
        event.setIdempotencyKey(idempotencyKey);
        event.setSourceType(normalizeSourceType(sourceType));
        validateExactEventRequest(event);

        VisitRecordEntity record = findVisitRecord(portfolio, visitorId, visitorKey);
        if (record == null) {
            record = createVisitRecord(portfolio, visitorId, visitorKey, event.getSourceType());
        } else {
            validateOwnedRecord(record, portfolio, visitorId, visitorKey);
            refreshSnapshot(record, portfolio, event.getSourceType());
        }
        return persistEventIfAbsent(portfolio, record, visitorKey, event).visitRecord();
    }

    /**
     * 记录已认证访客可上报的团队作品集事件。
     *
     * @param portfolio 已发布标准团队作品集
     * @param visitorId 已认证访客 ID
     * @param visitorKey 已认证访客稳定键
     * @param request 事件请求
     * @return 幂等事件结果
     */
    @Transactional(rollbackFor = Exception.class)
    public EventRecordResult recordEvent(
            PortfolioEntity portfolio,
            Long visitorId,
            String visitorKey,
            VisitorTeamPortfolioEventRequest request
    ) {
        validatePortfolio(portfolio);
        validateVisitor(visitorId, visitorKey);
        VisitEventTypeDict type = validateExactEventRequest(request);
        if (!CLIENT_EVENT_TYPES.contains(type)) {
            throw new BusinessException(EVENT_INVALID_MESSAGE);
        }
        VisitRecordEntity record = findVisitRecord(portfolio, visitorId, visitorKey);
        validateOwnedRecord(record, portfolio, visitorId, visitorKey);
        validatePublishedReference(portfolio, request, type);
        return persistEventIfAbsent(portfolio, record, visitorKey, request);
    }

    /**
     * 幂等记录团队查档事件。
     *
     * @param portfolio 已发布标准团队作品集
     * @param visitorId 已认证访客 ID
     * @param visitorKey 已认证访客稳定键
     * @param request 团队查档请求
     * @return 事件结果，用于门控查档快照
     */
    @Transactional(rollbackFor = Exception.class)
    public EventRecordResult recordScheduleQuery(
            PortfolioEntity portfolio,
            Long visitorId,
            String visitorKey,
            TeamPortfolioScheduleQueryRequest request
    ) {
        validatePortfolio(portfolio);
        validateVisitor(visitorId, visitorKey);
        VisitorTeamPortfolioEventRequest event = new VisitorTeamPortfolioEventRequest();
        event.setEventType(VisitEventTypeDict.SCHEDULE_QUERIED.getCode());
        event.setComponentKey(request == null ? null : request.getComponentKey());
        event.setQueriedDate(request == null ? null : request.getQueriedDate());
        event.setIdempotencyKey(request == null ? null : request.getIdempotencyKey());
        validateExactEventRequest(event);
        VisitRecordEntity record = findVisitRecord(portfolio, visitorId, visitorKey);
        validateOwnedRecord(record, portfolio, visitorId, visitorKey);
        validatePublishedComponent(
                portfolio, event.getComponentKey(), COMPONENT_TYPE_SCHEDULE_QUERY);
        return persistEventIfAbsent(portfolio, record, visitorKey, event);
    }

    /**
     * 在线索成功落库后幂等记录提交事件。
     *
     * @param portfolio 已发布标准团队作品集
     * @param visitorId 已认证访客 ID
     * @param visitorKey 已认证访客稳定键
     * @param leadId 已落库线索 ID
     * @param idempotencyKey 线索提交幂等键
     * @return 事件结果
     */
    @Transactional(rollbackFor = Exception.class)
    public EventRecordResult recordContactLeadSubmitted(
            PortfolioEntity portfolio,
            Long visitorId,
            String visitorKey,
            Long leadId,
            String idempotencyKey
    ) {
        validatePortfolio(portfolio);
        validateVisitor(visitorId, visitorKey);
        VisitorTeamPortfolioEventRequest event = new VisitorTeamPortfolioEventRequest();
        event.setEventType(VisitEventTypeDict.CONTACT_LEAD_SUBMITTED.getCode());
        event.setIdempotencyKey(idempotencyKey);
        event.setLeadId(leadId);
        validateExactEventRequest(event);
        VisitRecordEntity record = findVisitRecord(portfolio, visitorId, visitorKey);
        validateOwnedRecord(record, portfolio, visitorId, visitorKey);
        return persistEventIfAbsent(portfolio, record, visitorKey, event);
    }

    /** 创建初始团队访问汇总，并处理唯一键并发竞争。 */
    private VisitRecordEntity createVisitRecord(
            PortfolioEntity portfolio,
            Long visitorId,
            String visitorKey,
            String sourceType
    ) {
        LocalDateTime now = LocalDateTime.now();
        VisitRecordEntity record = new VisitRecordEntity();
        record.setVisitorId(visitorId);
        record.setVisitorKey(visitorKey);
        record.setPortfolioId(portfolio.getId());
        record.setPortfolioType(PortfolioTypeDict.TEAM.getCode());
        record.setOwnerType(PortfolioOwnerTypeDict.TEAM.getCode());
        record.setOwnerId(portfolio.getOwnerId());
        record.setVisitCount(0);
        record.setViewWorkCount(0);
        record.setPlayVideoCount(0);
        record.setScheduleQueryCount(0);
        record.setQrActionCount(0);
        record.setContactSubmitCount(0);
        record.setTotalDurationSeconds(0);
        record.setFollowStatus(FollowStatusDict.NOT_FOLLOWED_UP.getCode());
        record.setFollowNote("");
        record.setFirstVisitedAt(now);
        record.setLastVisitedAt(now);
        refreshSnapshot(record, portfolio, sourceType);
        try {
            if (visitRecordEntityMapper.insert(record) != 1
                    || record.getId() == null || record.getId() <= 0) {
                throw new BusinessException(RECORD_PERSISTENCE_FAILED_MESSAGE);
            }
            return record;
        } catch (DuplicateKeyException exception) {
            VisitRecordEntity winner = findVisitRecordForUpdate(portfolio, visitorId, visitorKey);
            validateOwnedRecord(winner, portfolio, visitorId, visitorKey);
            refreshSnapshot(winner, portfolio, sourceType);
            return winner;
        }
    }

    /** 刷新团队作品集访问快照。 */
    private void refreshSnapshot(VisitRecordEntity record, PortfolioEntity portfolio, String sourceType) {
        record.setPortfolioTitleSnapshot(resolveTitle(portfolio));
        record.setPortfolioShareCodeSnapshot(portfolio.getShareCode());
        record.setLastPortfolioRevision(portfolio.getPublishedRevision());
        record.setSourceType(normalizeSourceType(sourceType));
        record.setLastVisitedAt(LocalDateTime.now());
    }

    /** 插入事件成功后更新汇总计数。 */
    private EventRecordResult persistEventIfAbsent(
            PortfolioEntity portfolio,
            VisitRecordEntity record,
            String visitorKey,
            VisitorTeamPortfolioEventRequest request
    ) {
        VisitEventTypeDict type = validateExactEventRequest(request);
        VisitEventEntity candidate = buildEvent(portfolio, record, visitorKey, request, LocalDateTime.now(), type);
        VisitEventEntity existing = findEvent(candidate.getIdempotencyKey());
        if (existing != null) {
            validateExistingEvent(existing, candidate, type);
            return new EventRecordResult(false, record, existing.getOccurredAt());
        }
        try {
            if (visitEventEntityMapper.insert(candidate) != 1) {
                throw new BusinessException(EVENT_PERSISTENCE_FAILED_MESSAGE);
            }
        } catch (DuplicateKeyException exception) {
            VisitEventEntity raced = findEventForUpdate(candidate.getIdempotencyKey());
            if (raced == null) {
                throw exception;
            }
            validateExistingEvent(raced, candidate, type);
            return new EventRecordResult(false, record, raced.getOccurredAt());
        }
        updateCounters(record, type, candidate.getDurationSeconds());
        record.setLastVisitedAt(candidate.getOccurredAt());
        if (visitRecordEntityMapper.updateById(record) != 1) {
            throw new BusinessException(RECORD_PERSISTENCE_FAILED_MESSAGE);
        }
        return new EventRecordResult(true, record, candidate.getOccurredAt());
    }

    /** 构建团队访问事件和 canonical metadata。 */
    private VisitEventEntity buildEvent(
            PortfolioEntity portfolio,
            VisitRecordEntity record,
            String visitorKey,
            VisitorTeamPortfolioEventRequest request,
            LocalDateTime occurredAt,
            VisitEventTypeDict type
    ) {
        VisitEventEntity event = new VisitEventEntity();
        event.setVisitRecordId(record.getId());
        event.setPortfolioId(portfolio.getId());
        event.setPortfolioRevision(portfolio.getPublishedRevision());
        event.setVisitorKey(visitorKey);
        event.setEventType(type.getCode());
        event.setWorkId(request.getWorkId());
        event.setOwnerType(PortfolioOwnerTypeDict.TEAM.getCode());
        event.setOwnerId(portfolio.getOwnerId());
        event.setQueriedDate(request.getQueriedDate());
        event.setDurationSeconds(request.getDurationSeconds());
        event.setIdempotencyKey(requireIdempotencyKey(request.getIdempotencyKey()));
        event.setMetadata(buildCanonicalMetadata(request, type));
        event.setOccurredAt(occurredAt);
        return event;
    }

    /** 按事件类型更新对应汇总计数。 */
    private void updateCounters(VisitRecordEntity record, VisitEventTypeDict type, Integer durationSeconds) {
        switch (type) {
            case PORTFOLIO_OPENED -> record.setVisitCount(safeCount(record.getVisitCount()) + 1);
            case WORK_VIEWED -> record.setViewWorkCount(safeCount(record.getViewWorkCount()) + 1);
            case VIDEO_PLAYED -> record.setPlayVideoCount(safeCount(record.getPlayVideoCount()) + 1);
            case SCHEDULE_QUERIED -> record.setScheduleQueryCount(safeCount(record.getScheduleQueryCount()) + 1);
            case QR_CODE_INTERACTED -> record.setQrActionCount(safeCount(record.getQrActionCount()) + 1);
            case CONTACT_LEAD_SUBMITTED ->
                    record.setContactSubmitCount(safeCount(record.getContactSubmitCount()) + 1);
            case MEMBER_PORTFOLIO_OPENED, CONTACT_FORM_EXPOSED -> {
                // 这两类事件只更新事件明细和最近访问时间。
            }
        }
        if (durationSeconds != null && durationSeconds > 0) {
            record.setTotalDurationSeconds(safeCount(record.getTotalDurationSeconds()) + durationSeconds);
        }
    }

    /** 校验客户端事件关联资源属于当前作品集发布引用。 */
    private void validatePublishedReference(
            PortfolioEntity portfolio,
            VisitorTeamPortfolioEventRequest request,
            VisitEventTypeDict type
    ) {
        ReferenceTypeDict referenceType;
        Long referenceId;
        if (type == VisitEventTypeDict.WORK_VIEWED || type == VisitEventTypeDict.VIDEO_PLAYED) {
            referenceType = ReferenceTypeDict.WORK;
            referenceId = request.getWorkId();
        } else if (type == VisitEventTypeDict.MEMBER_PORTFOLIO_OPENED) {
            referenceType = ReferenceTypeDict.MEMBER_PORTFOLIO;
            referenceId = request.getMemberPortfolioId();
        } else if (type == VisitEventTypeDict.QR_CODE_INTERACTED) {
            referenceType = ReferenceTypeDict.QR_CODE_ASSET;
            referenceId = portfolio.getOwnerId();
        } else if (type == VisitEventTypeDict.CONTACT_FORM_EXPOSED) {
            validatePublishedComponent(
                    portfolio, request.getComponentKey(), COMPONENT_TYPE_CONTACT_FORM);
            return;
        } else {
            return;
        }
        String componentKey = normalizeRequiredText(request.getComponentKey(), COMPONENT_KEY_MAX_BYTES);
        List<PortfolioReferenceEntity> references = portfolioReferenceEntityMapper.selectList(
                Wrappers.lambdaQuery(PortfolioReferenceEntity.class)
                        .eq(PortfolioReferenceEntity::getPortfolioId, portfolio.getId())
                        .eq(PortfolioReferenceEntity::getConfigScope,
                                PortfolioConfigScopeDict.PUBLISHED.getCode())
                        .eq(PortfolioReferenceEntity::getReferenceType, referenceType.getCode())
                        .eq(PortfolioReferenceEntity::getReferenceId, referenceId)
                        .eq(PortfolioReferenceEntity::getComponentKey, componentKey)
                        .eq(PortfolioReferenceEntity::getIsValid, 1)
                        .eq(PortfolioReferenceEntity::getDeleted, 0L));
        boolean matched = safeList(references).stream().anyMatch(reference ->
                Objects.equals(reference.getPortfolioId(), portfolio.getId())
                        && PortfolioConfigScopeDict.PUBLISHED.getCode().equals(reference.getConfigScope())
                        && referenceType.getCode().equals(reference.getReferenceType())
                        && Objects.equals(reference.getReferenceId(), referenceId)
                        && Objects.equals(reference.getComponentKey(), componentKey)
                        && Objects.equals(reference.getIsValid(), 1)
                        && Objects.equals(reference.getDeleted(), 0L));
        if (!matched) {
            throw new BusinessException(EVENT_INVALID_MESSAGE);
        }
        if (type == VisitEventTypeDict.WORK_VIEWED || type == VisitEventTypeDict.VIDEO_PLAYED) {
            validateTrustedWork(request, type);
        }
    }

    /**
     * 从已发布配置的全部菜单确认互动事件目标组件存在且已启用。
     */
    private void validatePublishedComponent(
            PortfolioEntity portfolio,
            String componentKey,
            String componentType
    ) {
        try {
            TeamPortfolioConfigDto config = JSON.parseObject(
                    portfolio.getPublishedConfigJson(), TeamPortfolioConfigDto.class);
            if (config == null
                    || !TeamPortfolioConstants.SCHEMA_VERSION_STANDARD_TEAM_V1.equals(
                            config.getSchemaVersion())
                    || TeamPortfolioComponentTraversal.findEnabledComponent(
                            config, componentKey, componentType).isEmpty()) {
                throw new BusinessException(EVENT_INVALID_MESSAGE);
            }
        } catch (BusinessException exception) {
            throw exception;
        } catch (RuntimeException exception) {
            throw new BusinessException(EVENT_INVALID_MESSAGE, exception);
        }
    }

    /** 发布引用命中后复验可信作品主数据和媒体类型。 */
    private void validateTrustedWork(
            VisitorTeamPortfolioEventRequest request,
            VisitEventTypeDict type
    ) {
        String expectedMediaType = type == VisitEventTypeDict.WORK_VIEWED
                ? MediaTypeDict.IMAGE.getCode() : MediaTypeDict.VIDEO.getCode();
        WorkEntity work = workEntityMapper.selectById(request.getWorkId());
        if (work == null
                || !Objects.equals(work.getId(), request.getWorkId())
                || !Objects.equals(work.getDeleted(), 0L)
                || !expectedMediaType.equals(work.getMediaType())) {
            throw new BusinessException(EVENT_INVALID_MESSAGE);
        }
    }

    /** 查询当前访客在当前团队作品集的访问汇总。 */
    private VisitRecordEntity findVisitRecord(PortfolioEntity portfolio, Long visitorId, String visitorKey) {
        return visitRecordEntityMapper.selectOne(
                Wrappers.lambdaQuery(VisitRecordEntity.class)
                        .eq(VisitRecordEntity::getVisitorId, visitorId)
                        .eq(VisitRecordEntity::getVisitorKey, visitorKey)
                        .eq(VisitRecordEntity::getPortfolioId, portfolio.getId())
                        .eq(VisitRecordEntity::getPortfolioType, PortfolioTypeDict.TEAM.getCode())
                        .eq(VisitRecordEntity::getOwnerType, PortfolioOwnerTypeDict.TEAM.getCode())
                        .eq(VisitRecordEntity::getOwnerId, portfolio.getOwnerId())
                        .last(QUERY_LIMIT_ONE));
    }

    /** 唯一键竞争后锁定读取当前访客在当前团队作品集的赢家访问汇总。 */
    private VisitRecordEntity findVisitRecordForUpdate(
            PortfolioEntity portfolio,
            Long visitorId,
            String visitorKey
    ) {
        return visitRecordEntityMapper.selectOne(
                Wrappers.lambdaQuery(VisitRecordEntity.class)
                        .eq(VisitRecordEntity::getVisitorId, visitorId)
                        .eq(VisitRecordEntity::getVisitorKey, visitorKey)
                        .eq(VisitRecordEntity::getPortfolioId, portfolio.getId())
                        .eq(VisitRecordEntity::getPortfolioType, PortfolioTypeDict.TEAM.getCode())
                        .eq(VisitRecordEntity::getOwnerType, PortfolioOwnerTypeDict.TEAM.getCode())
                        .eq(VisitRecordEntity::getOwnerId, portfolio.getOwnerId())
                        .last(QUERY_LIMIT_ONE_FOR_UPDATE));
    }

    /** 按全局幂等键查询事件。 */
    private VisitEventEntity findEvent(String idempotencyKey) {
        return visitEventEntityMapper.selectOne(
                Wrappers.lambdaQuery(VisitEventEntity.class)
                        .eq(VisitEventEntity::getIdempotencyKey, requireIdempotencyKey(idempotencyKey))
                        .last(QUERY_LIMIT_ONE));
    }

    /** 唯一键竞争后按全局幂等键锁定读取当前事件。 */
    private VisitEventEntity findEventForUpdate(String idempotencyKey) {
        return visitEventEntityMapper.selectOne(
                Wrappers.lambdaQuery(VisitEventEntity.class)
                        .eq(VisitEventEntity::getIdempotencyKey, requireIdempotencyKey(idempotencyKey))
                        .last(QUERY_LIMIT_ONE_FOR_UPDATE));
    }

    /** 复验已存在事件的完整原始业务载荷。 */
    private void validateExistingEvent(
            VisitEventEntity existing,
            VisitEventEntity candidate,
            VisitEventTypeDict type
    ) {
        if (!Objects.equals(existing.getVisitRecordId(), candidate.getVisitRecordId())
                || !Objects.equals(existing.getPortfolioId(), candidate.getPortfolioId())
                || !PortfolioOwnerTypeDict.TEAM.getCode().equals(existing.getOwnerType())
                || !Objects.equals(existing.getOwnerId(), candidate.getOwnerId())
                || !Objects.equals(existing.getVisitorKey(), candidate.getVisitorKey())
                || !Objects.equals(existing.getEventType(), candidate.getEventType())
                || !Objects.equals(existing.getWorkId(), candidate.getWorkId())
                || !Objects.equals(existing.getQueriedDate(), candidate.getQueriedDate())
                || !Objects.equals(existing.getDurationSeconds(), candidate.getDurationSeconds())
                || !Objects.equals(existing.getIdempotencyKey(), candidate.getIdempotencyKey())
                || !Objects.equals(canonicalizeStoredMetadata(existing.getMetadata(), type),
                        candidate.getMetadata())) {
            throw new BusinessException(IDEMPOTENCY_CONFLICT_MESSAGE);
        }
    }

    /** 复验访问汇总的访客、作品集和团队归属。 */
    private void validateOwnedRecord(
            VisitRecordEntity record,
            PortfolioEntity portfolio,
            Long visitorId,
            String visitorKey
    ) {
        if (record == null || record.getId() == null || record.getId() <= 0
                || !Objects.equals(record.getVisitorId(), visitorId)
                || !Objects.equals(record.getVisitorKey(), visitorKey)
                || !Objects.equals(record.getPortfolioId(), portfolio.getId())
                || !PortfolioTypeDict.TEAM.getCode().equals(record.getPortfolioType())
                || !PortfolioOwnerTypeDict.TEAM.getCode().equals(record.getOwnerType())
                || !Objects.equals(record.getOwnerId(), portfolio.getOwnerId())) {
            throw new BusinessException(VISIT_RECORD_INVALID_MESSAGE);
        }
    }

    /** 严格校验已发布标准团队作品集身份。 */
    private void validatePortfolio(PortfolioEntity portfolio) {
        if (portfolio == null || portfolio.getId() == null || portfolio.getId() <= 0
                || portfolio.getOwnerId() == null || portfolio.getOwnerId() <= 0
                || !PortfolioOwnerTypeDict.TEAM.getCode().equals(portfolio.getOwnerType())
                || !PortfolioTemplateTypeDict.STANDARD.getCode().equals(portfolio.getTemplateType())
                || !TeamPortfolioConstants.SCHEMA_VERSION_STANDARD_TEAM_V1.equals(portfolio.getSchemaVersion())
                || !PortfolioPublicationStatusDict.PUBLISHED.getCode().equals(portfolio.getPublicationStatus())
                || !PortfolioStatusDict.ACTIVE.getCode().equals(portfolio.getStatus())
                || !Objects.equals(portfolio.getDeleted(), 0L)
                || portfolio.getPublishedRevision() == null || portfolio.getPublishedRevision() <= 0
                || !hasText(portfolio.getPublishedConfigJson())) {
            throw new BusinessException(PORTFOLIO_INVALID_MESSAGE);
        }
    }

    /** 校验已认证访客身份。 */
    private void validateVisitor(Long visitorId, String visitorKey) {
        if (visitorId == null || visitorId <= 0 || !hasText(visitorKey)
                || visitorKey.length() > VISITOR_KEY_MAX_LENGTH) {
            throw new BusinessException(VISITOR_INVALID_MESSAGE);
        }
    }

    /** 按事件类型严格校验 typed 字段和兼容 metadata。 */
    private VisitEventTypeDict validateExactEventRequest(VisitorTeamPortfolioEventRequest request) {
        VisitEventTypeDict type = request == null ? null : VisitEventTypeDict.fromCode(request.getEventType());
        if (type == null) {
            throw new BusinessException(EVENT_INVALID_MESSAGE);
        }
        requireIdempotencyKey(request.getIdempotencyKey());
        validateDuration(request.getDurationSeconds());
        switch (type) {
            case PORTFOLIO_OPENED -> {
                normalizeRequiredText(request.getSourceType(), CODE_VALUE_MAX_BYTES);
                requireNoWork(request);
                requireAbsent(request.getComponentKey(), request.getMemberPortfolioId(), request.getLeadId(),
                        request.getMediaType(), request.getAction(), request.getQueriedDate(),
                        request.getDurationSeconds());
            }
            case WORK_VIEWED -> {
                requirePositive(request.getWorkId());
                normalizeRequiredText(request.getComponentKey(), COMPONENT_KEY_MAX_BYTES);
                requireExpectedMediaType(request.getMediaType(), MediaTypeDict.IMAGE);
                requireAbsent(request.getMemberPortfolioId(), request.getLeadId(), request.getAction(),
                        request.getSourceType(), request.getQueriedDate());
            }
            case VIDEO_PLAYED -> {
                requirePositive(request.getWorkId());
                normalizeRequiredText(request.getComponentKey(), COMPONENT_KEY_MAX_BYTES);
                requireExpectedMediaType(request.getMediaType(), MediaTypeDict.VIDEO);
                if (request.getDurationSeconds() == null) {
                    throw new BusinessException(EVENT_INVALID_MESSAGE);
                }
                requireAbsent(request.getMemberPortfolioId(), request.getLeadId(), request.getAction(),
                        request.getSourceType(), request.getQueriedDate());
            }
            case QR_CODE_INTERACTED -> {
                normalizeRequiredText(request.getComponentKey(), COMPONENT_KEY_MAX_BYTES);
                normalizeQrAction(request.getAction());
                requireNoWork(request);
                requireAbsent(request.getMemberPortfolioId(), request.getLeadId(), request.getMediaType(),
                        request.getSourceType(), request.getQueriedDate(), request.getDurationSeconds());
            }
            case MEMBER_PORTFOLIO_OPENED -> {
                normalizeRequiredText(request.getComponentKey(), COMPONENT_KEY_MAX_BYTES);
                requirePositive(request.getMemberPortfolioId());
                requireNoWork(request);
                requireAbsent(request.getLeadId(), request.getMediaType(), request.getAction(),
                        request.getSourceType(), request.getQueriedDate(), request.getDurationSeconds());
            }
            case CONTACT_FORM_EXPOSED -> {
                normalizeRequiredText(request.getComponentKey(), COMPONENT_KEY_MAX_BYTES);
                requireNoWork(request);
                requireAbsent(request.getMemberPortfolioId(), request.getLeadId(), request.getMediaType(),
                        request.getAction(), request.getSourceType(), request.getQueriedDate(),
                        request.getDurationSeconds());
            }
            case SCHEDULE_QUERIED -> {
                normalizeRequiredText(request.getComponentKey(), COMPONENT_KEY_MAX_BYTES);
                if (request.getQueriedDate() == null) {
                    throw new BusinessException(EVENT_INVALID_MESSAGE);
                }
                requireNoWork(request);
                requireAbsent(request.getMemberPortfolioId(), request.getLeadId(), request.getMediaType(),
                        request.getAction(), request.getSourceType(), request.getDurationSeconds());
            }
            case CONTACT_LEAD_SUBMITTED -> {
                requirePositive(request.getLeadId());
                requireNoWork(request);
                requireAbsent(request.getComponentKey(), request.getMemberPortfolioId(), request.getMediaType(),
                        request.getAction(), request.getSourceType(), request.getQueriedDate(),
                        request.getDurationSeconds());
            }
        }
        validateCompatibilityMetadata(request, type);
        return type;
    }

    /** 校验客户端兼容 metadata 只包含当前事件允许的安全 scalar。 */
    private void validateCompatibilityMetadata(
            VisitorTeamPortfolioEventRequest request,
            VisitEventTypeDict type
    ) {
        Map<String, Object> metadata = request.getMetadata();
        if (metadata == null || metadata.isEmpty()) {
            return;
        }
        if (metadata.size() > METADATA_MAX_ENTRIES) {
            throw new BusinessException(EVENT_INVALID_MESSAGE);
        }
        Set<String> allowedKeys = switch (type) {
            case WORK_VIEWED, VIDEO_PLAYED -> Set.of(METADATA_MEDIA_TYPE);
            case QR_CODE_INTERACTED -> Set.of(METADATA_ACTION);
            default -> Set.of();
        };
        try {
            metadata.forEach((key, value) -> {
                if (!hasText(key) || utf8Length(key) > METADATA_KEY_MAX_BYTES
                        || !allowedKeys.contains(key) || !(value instanceof String stringValue)
                        || utf8Length(stringValue) > METADATA_VALUE_MAX_BYTES) {
                    throw new BusinessException(EVENT_INVALID_MESSAGE);
                }
                String expected = METADATA_ACTION.equals(key)
                        ? normalizeQrAction(request.getAction())
                        : normalizeOptionalMediaType(request.getMediaType(), false);
                String actual = METADATA_ACTION.equals(key)
                        ? normalizeQrAction(stringValue)
                        : normalizeOptionalMediaType(stringValue, false);
                if (!Objects.equals(expected, actual)) {
                    throw new BusinessException(EVENT_INVALID_MESSAGE);
                }
            });
        } catch (BusinessException exception) {
            throw exception;
        } catch (RuntimeException exception) {
            throw new BusinessException(EVENT_INVALID_MESSAGE, exception);
        }
    }

    /** 从 typed 字段构建确定顺序的 metadata。 */
    private String buildCanonicalMetadata(
            VisitorTeamPortfolioEventRequest request,
            VisitEventTypeDict type
    ) {
        Map<String, Object> canonical = new TreeMap<>();
        switch (type) {
            case PORTFOLIO_OPENED -> canonical.put(
                    METADATA_SOURCE_TYPE, normalizeSourceType(request.getSourceType()));
            case WORK_VIEWED, VIDEO_PLAYED -> {
                canonical.put(METADATA_COMPONENT_KEY,
                        normalizeRequiredText(request.getComponentKey(), COMPONENT_KEY_MAX_BYTES));
                String mediaType = normalizeOptionalMediaType(
                        request.getMediaType(), type == VisitEventTypeDict.VIDEO_PLAYED);
                if (mediaType != null) {
                    canonical.put(METADATA_MEDIA_TYPE, mediaType);
                }
            }
            case QR_CODE_INTERACTED -> {
                canonical.put(METADATA_ACTION, normalizeQrAction(request.getAction()));
                canonical.put(METADATA_COMPONENT_KEY,
                        normalizeRequiredText(request.getComponentKey(), COMPONENT_KEY_MAX_BYTES));
            }
            case MEMBER_PORTFOLIO_OPENED -> {
                canonical.put(METADATA_COMPONENT_KEY,
                        normalizeRequiredText(request.getComponentKey(), COMPONENT_KEY_MAX_BYTES));
                canonical.put(METADATA_MEMBER_PORTFOLIO_ID, request.getMemberPortfolioId());
            }
            case CONTACT_FORM_EXPOSED, SCHEDULE_QUERIED -> canonical.put(
                    METADATA_COMPONENT_KEY,
                    normalizeRequiredText(request.getComponentKey(), COMPONENT_KEY_MAX_BYTES));
            case CONTACT_LEAD_SUBMITTED -> canonical.put(METADATA_LEAD_ID, request.getLeadId());
        }
        return serializeCanonicalMetadata(canonical, EVENT_INVALID_MESSAGE);
    }

    /** 将已落库 metadata 重新清洗为同一 canonical 表示。 */
    private String canonicalizeStoredMetadata(String metadata, VisitEventTypeDict type) {
        try {
            JSONObject parsed = hasText(metadata) ? JSON.parseObject(metadata) : new JSONObject();
            if (parsed == null || parsed.size() > METADATA_MAX_ENTRIES) {
                throw new BusinessException(IDEMPOTENCY_CONFLICT_MESSAGE);
            }
            Set<String> allowedKeys = canonicalMetadataKeys(type);
            Map<String, Object> canonical = new TreeMap<>();
            parsed.forEach((key, value) -> {
                if (!hasText(key) || utf8Length(key) > METADATA_KEY_MAX_BYTES
                        || !allowedKeys.contains(key) || !isScalar(value)
                        || utf8Length(String.valueOf(value)) > METADATA_VALUE_MAX_BYTES) {
                    throw new BusinessException(IDEMPOTENCY_CONFLICT_MESSAGE);
                }
                canonical.put(key, value);
            });
            return serializeCanonicalMetadata(canonical, IDEMPOTENCY_CONFLICT_MESSAGE);
        } catch (BusinessException exception) {
            throw exception;
        } catch (RuntimeException exception) {
            throw new BusinessException(IDEMPOTENCY_CONFLICT_MESSAGE, exception);
        }
    }

    /** 返回各事件落库 metadata 的精确键集合。 */
    private Set<String> canonicalMetadataKeys(VisitEventTypeDict type) {
        return switch (type) {
            case PORTFOLIO_OPENED -> Set.of(METADATA_SOURCE_TYPE);
            case WORK_VIEWED, VIDEO_PLAYED -> Set.of(METADATA_COMPONENT_KEY, METADATA_MEDIA_TYPE);
            case QR_CODE_INTERACTED -> Set.of(METADATA_ACTION, METADATA_COMPONENT_KEY);
            case MEMBER_PORTFOLIO_OPENED -> Set.of(METADATA_COMPONENT_KEY, METADATA_MEMBER_PORTFOLIO_ID);
            case CONTACT_FORM_EXPOSED, SCHEDULE_QUERIED -> Set.of(METADATA_COMPONENT_KEY);
            case CONTACT_LEAD_SUBMITTED -> Set.of(METADATA_LEAD_ID);
        };
    }

    /** 安全序列化 canonical metadata 并限制 UTF-8 字节数。 */
    private String serializeCanonicalMetadata(Map<String, Object> metadata, String failureMessage) {
        try {
            String json = JSON.toJSONString(metadata);
            if (utf8Length(json) > METADATA_MAX_BYTES) {
                throw new BusinessException(failureMessage);
            }
            return json;
        } catch (BusinessException exception) {
            throw exception;
        } catch (RuntimeException exception) {
            throw new BusinessException(failureMessage, exception);
        }
    }

    /** 从发布配置解析标题快照。 */
    private String resolveTitle(PortfolioEntity portfolio) {
        try {
            TeamPortfolioConfigDto config = JSON.parseObject(
                    portfolio.getPublishedConfigJson(), TeamPortfolioConfigDto.class);
            String title = config == null || config.getShare() == null ? null : config.getShare().getTitle();
            return hasText(title) ? title.strip() : DEFAULT_PORTFOLIO_TITLE;
        } catch (RuntimeException exception) {
            throw new BusinessException(PORTFOLIO_INVALID_MESSAGE, exception);
        }
    }

    /** 规范化访问来源。 */
    private String normalizeSourceType(String sourceType) {
        String normalized = hasText(sourceType) ? sourceType.strip() : null;
        return VisitSourceTypeDict.fromCode(normalized) == null
                ? VisitSourceTypeDict.UNKNOWN.getCode() : normalized;
    }

    /** 校验并规范化幂等键。 */
    private String requireIdempotencyKey(String idempotencyKey) {
        String normalized = hasText(idempotencyKey) ? idempotencyKey.strip() : null;
        if (!hasText(normalized) || utf8Length(normalized) > IDEMPOTENCY_KEY_MAX_BYTES) {
            throw new BusinessException(EVENT_INVALID_MESSAGE);
        }
        return normalized;
    }

    /** 规范化必填文本。 */
    private String normalizeRequiredText(String value, int maxBytes) {
        String normalized = hasText(value) ? value.strip() : null;
        if (!hasText(normalized) || utf8Length(normalized) > maxBytes) {
            throw new BusinessException(EVENT_INVALID_MESSAGE);
        }
        return normalized;
    }

    /** 规范化可选媒体类型。 */
    private String normalizeOptionalMediaType(String mediaType, boolean requireVideo) {
        if (mediaType == null && !requireVideo) {
            return null;
        }
        String normalized = normalizeRequiredText(mediaType, CODE_VALUE_MAX_BYTES);
        if (MediaTypeDict.fromCode(normalized) == null
                || (requireVideo && !MediaTypeDict.VIDEO.getCode().equals(normalized))) {
            throw new BusinessException(EVENT_INVALID_MESSAGE);
        }
        return normalized;
    }

    /** 校验客户端明确媒体类型与事件语义一致。 */
    private String requireExpectedMediaType(String mediaType, MediaTypeDict expectedType) {
        String normalized = normalizeRequiredText(mediaType, CODE_VALUE_MAX_BYTES);
        if (!expectedType.getCode().equals(normalized)) {
            throw new BusinessException(EVENT_INVALID_MESSAGE);
        }
        return normalized;
    }

    /** 规范化二维码动作。 */
    private String normalizeQrAction(String action) {
        String normalized = normalizeRequiredText(action, CODE_VALUE_MAX_BYTES);
        if (!QR_ACTIONS.contains(normalized)) {
            throw new BusinessException(EVENT_INVALID_MESSAGE);
        }
        return normalized;
    }

    /** 校验时长边界。 */
    private void validateDuration(Integer durationSeconds) {
        if (durationSeconds != null && (durationSeconds < 0 || durationSeconds > DURATION_MAX_SECONDS)) {
            throw new BusinessException(EVENT_INVALID_MESSAGE);
        }
    }

    /** 校验作品 ID 不应出现。 */
    private void requireNoWork(VisitorTeamPortfolioEventRequest request) {
        if (request.getWorkId() != null) {
            throw new BusinessException(EVENT_INVALID_MESSAGE);
        }
    }

    /** 校验正数业务 ID。 */
    private void requirePositive(Long value) {
        if (value == null || value <= 0) {
            throw new BusinessException(EVENT_INVALID_MESSAGE);
        }
    }

    /** 校验当前事件不允许出现的字段。 */
    private void requireAbsent(Object... values) {
        for (Object value : values) {
            if (value != null) {
                throw new BusinessException(EVENT_INVALID_MESSAGE);
            }
        }
    }

    /** 判断 JSON 值是否为有限安全标量。 */
    private boolean isScalar(Object value) {
        return value instanceof String || value instanceof Number || value instanceof Boolean;
    }

    /** 计算 UTF-8 字节数。 */
    private int utf8Length(String value) {
        return value == null ? 0 : value.getBytes(StandardCharsets.UTF_8).length;
    }

    /** 安全读取计数。 */
    private int safeCount(Integer value) {
        return value == null ? 0 : value;
    }

    /** 安全读取列表。 */
    private <T> List<T> safeList(List<T> values) {
        return values == null ? List.of() : values;
    }

    /** 判断文本是否非空。 */
    private boolean hasText(String value) {
        return value != null && !value.isBlank();
    }

    /**
     * 幂等事件写入结果。
     *
     * @param recorded 本次是否新插入事件并更新计数
     * @param visitRecord 当前团队访问汇总
     * @param occurredAt 首次事件发生时间
     */
    public record EventRecordResult(
            boolean recorded,
            VisitRecordEntity visitRecord,
            LocalDateTime occurredAt
    ) {
    }
}
