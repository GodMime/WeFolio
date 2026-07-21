package com.jxc.wefolio.service;

import com.alibaba.fastjson2.JSON;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.jxc.wefolio.common.auth.VisitorContextHolder;
import com.jxc.wefolio.dict.PortfolioOwnerTypeDict;
import com.jxc.wefolio.dict.PortfolioPublicationStatusDict;
import com.jxc.wefolio.dict.PortfolioTypeDict;
import com.jxc.wefolio.dict.ScheduleStatusDict;
import com.jxc.wefolio.dict.SlotDefinitionStatusDict;
import com.jxc.wefolio.dict.VisitEventTypeDict;
import com.jxc.wefolio.dict.VisitSourceTypeDict;
import com.jxc.wefolio.dto.PortfolioConfigDto;
import com.jxc.wefolio.dto.PortfolioScheduleOptionsResponse;
import com.jxc.wefolio.dto.PortfolioScheduleQueryRequest;
import com.jxc.wefolio.dto.PortfolioScheduleQueryResponse;
import com.jxc.wefolio.dto.VisitorAvatarUploadTicketRequest;
import com.jxc.wefolio.dto.VisitorAvatarUploadTicketResponse;
import com.jxc.wefolio.dto.VisitorPortfolioEventRequest;
import com.jxc.wefolio.dto.VisitorPortfolioOpenRequest;
import com.jxc.wefolio.dto.VisitorPortfolioResponse;
import com.jxc.wefolio.dto.VisitorPortfolioScheduleResponse;
import com.jxc.wefolio.dto.VisitorProfileUpdateRequest;
import com.jxc.wefolio.entity.PortfolioEntity;
import com.jxc.wefolio.entity.ScheduleEntity;
import com.jxc.wefolio.entity.ScheduleQueryRecordEntity;
import com.jxc.wefolio.entity.SlotDefinitionEntity;
import com.jxc.wefolio.entity.VisitRecordEntity;
import com.jxc.wefolio.entity.VisitorEntity;
import com.jxc.wefolio.exception.BusinessException;
import com.jxc.wefolio.mapper.PortfolioEntityMapper;
import com.jxc.wefolio.mapper.ScheduleEntityMapper;
import com.jxc.wefolio.mapper.ScheduleQueryRecordEntityMapper;
import com.jxc.wefolio.mapper.SlotDefinitionEntityMapper;
import com.jxc.wefolio.message.PointMessage;
import com.jxc.wefolio.message.PortfolioMessage;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalTime;
import java.time.YearMonth;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

/**
 * 访客作品集服务 — 负责访客读取已发布作品集和公开档期。
 */
@Service
@RequiredArgsConstructor
public class VisitorPortfolioService {

    /** 积分非正维护原因。 */
    private static final String POINT_BALANCE_NON_POSITIVE = "POINT_BALANCE_NON_POSITIVE";

    /** 维护中英文主文案 */
    private static final String MAINTENANCE_PRIMARY = "UNDER MAINTENANCE";

    /** 维护中中文副文案 */
    private static final String MAINTENANCE_SECONDARY = "维护中";

    /** 默认标题 */
    private static final String DEFAULT_TITLE = "个人作品集";

    /** 时间展示格式 */
    private static final DateTimeFormatter TIME_FORMATTER = DateTimeFormatter.ofPattern("HH:mm");

    /** 月份格式 */
    private static final DateTimeFormatter MONTH_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM");

    /** 档期查询组件类型 */
    private static final String COMPONENT_TYPE_SCHEDULE_QUERY = "SCHEDULE_QUERY";

    /** 展示方式配置键 */
    private static final String CONFIG_KEY_DISPLAY_MODE = "displayMode";

    /** 默认弹层月历展示方式 */
    private static final String DISPLAY_MODE_MODAL_CALENDAR = "MODAL_CALENDAR";

    /** 可约状态兜底编码 */
    private static final String STATUS_AVAILABLE = "AVAILABLE";

    /** 可约提示 */
    private static final String MESSAGE_AVAILABLE = "档期空闲";

    /** 已约提示 */
    private static final String MESSAGE_BOOKED = "该档期已约";

    /** 作品集 Mapper */
    private final PortfolioEntityMapper portfolioEntityMapper;

    /** 档期 Mapper */
    private final ScheduleEntityMapper scheduleEntityMapper;

    /** 档位定义 Mapper */
    private final SlotDefinitionEntityMapper slotDefinitionEntityMapper;

    /** 访问服务 */
    private final PortfolioVisitService portfolioVisitService;

    /** 作品集渲染服务 */
    private final PortfolioRenderService portfolioRenderService;

    /** 访客身份服务 */
    private final VisitorService visitorService;

    /** 访客登录令牌服务 */
    private final VisitorAuthTokenService visitorAuthTokenService;

    /** 查询档期记录 Mapper */
    private final ScheduleQueryRecordEntityMapper scheduleQueryRecordEntityMapper;

    /** 维护者本人访问识别服务 */
    private final OwnerSelfVisitService ownerSelfVisitService;

    /** 维护者实际可用积分门禁。 */
    private final PointBalanceGateService pointBalanceGateService;

    /** 作品集打开分段耗时日志器。 */
    private final PortfolioOpenPerformanceLogger portfolioOpenPerformanceLogger;

    /**
     * 打开访客作品集，使用微信 openid 创建或复用全局访客。
     *
     * @param shareCode 分享编码
     * @param request 打开请求
     * @return 访客作品集响应
     */
    public VisitorPortfolioResponse openPortfolio(String shareCode, VisitorPortfolioOpenRequest request) {
        PortfolioOpenPerformanceLogger.Trace trace = portfolioOpenPerformanceLogger.start(
                PortfolioOpenPerformanceLogger.PortfolioType.PERSONAL);
        Throwable failure = null;
        try {
            PortfolioEntity portfolio = trace.measure(
                    PortfolioOpenPerformanceLogger.Phase.PORTFOLIO_LOOKUP,
                    () -> requirePublishedPortfolio(shareCode));
            PortfolioConfigDto config = trace.measure(
                    PortfolioOpenPerformanceLogger.Phase.PORTFOLIO_LOOKUP,
                    () -> parseConfig(portfolio.getPublishedConfigJson()));
            VisitorService.VisitorSession visitorSession = visitorService.resolveByLoginCode(
                    request == null ? null : request.getLoginCode(), trace);
            VisitorEntity visitor = visitorSession.visitor();
            boolean ownerSelfVisitor = ownerSelfVisitService.isOwnerSelfVisitor(
                    portfolio.getOwnerId(),
                    visitor == null ? null : visitor.getOpenid(),
                    portfolio.getId(),
                    visitor == null ? null : visitor.getId(),
                    VisitEventTypeDict.PORTFOLIO_OPENED.getCode()
            );
            if (ownerSelfVisitor) {
                VisitorPortfolioResponse response = buildNormalResponse(portfolio, config);
                response.setVisitRecordId(null);
                fillVisitorProfileOpenFields(response, visitorSession, null, portfolio.getId(), true);
                response.setRenderData(trace.measure(
                        PortfolioOpenPerformanceLogger.Phase.RENDER,
                        () -> portfolioRenderService.render(
                                portfolio, config, false, false, null, null)));
                trace.outcome(PortfolioOpenPerformanceLogger.Outcome.OWNER_SELF);
                return response;
            }
            boolean nonPositive = trace.measure(
                    PortfolioOpenPerformanceLogger.Phase.POINT_GATE,
                    () -> pointBalanceGateService.isNonPositive(portfolio.getOwnerId()));
            if (nonPositive) {
                VisitorPortfolioResponse maintenanceResponse = trace.measure(
                        PortfolioOpenPerformanceLogger.Phase.RENDER,
                        () -> buildMaintenanceResponse(portfolio, config));
                maintenanceResponse.setMaintenanceReason(POINT_BALANCE_NON_POSITIVE);
                fillVisitorProfileOpenFields(maintenanceResponse, visitorSession, null, portfolio.getId());
                trace.outcome(PortfolioOpenPerformanceLogger.Outcome.MAINTENANCE);
                return maintenanceResponse;
            }
            VisitRecordEntity record;
            try {
                record = trace.measure(
                        PortfolioOpenPerformanceLogger.Phase.VISIT_WRITE,
                        () -> portfolioVisitService.recordOpen(
                                portfolio,
                                visitor.getId(),
                                visitor.getVisitorKey(),
                                request == null ? null : request.getSourceType(),
                                request == null ? null : request.getIdempotencyKey()));
            } catch (BusinessException exception) {
                if (!PointMessage.INSUFFICIENT_BALANCE_MESSAGE.equals(exception.getMessage())) {
                    throw exception;
                }
                VisitorPortfolioResponse maintenanceResponse = trace.measure(
                        PortfolioOpenPerformanceLogger.Phase.RENDER,
                        () -> buildMaintenanceResponse(portfolio, config));
                fillVisitorProfileOpenFields(maintenanceResponse, visitorSession, null, portfolio.getId());
                trace.outcome(PortfolioOpenPerformanceLogger.Outcome.MAINTENANCE);
                return maintenanceResponse;
            }
            VisitorPortfolioResponse response = buildNormalResponse(portfolio, config);
            response.setVisitRecordId(record == null ? null : record.getId());
            fillVisitorProfileOpenFields(response, visitorSession, response.getVisitRecordId(), portfolio.getId());
            response.setRenderData(trace.measure(
                    PortfolioOpenPerformanceLogger.Phase.RENDER,
                    () -> portfolioRenderService.render(
                            portfolio, config, false, false, null, response.getVisitRecordId())));
            trace.outcome(PortfolioOpenPerformanceLogger.Outcome.SUCCESS);
            return response;
        } catch (RuntimeException | Error exception) {
            failure = exception;
            throw exception;
        } finally {
            trace.finish(failure);
        }
    }

    /**
     * 创建访客头像上传票据。
     *
     * @param shareCode 分享编码
     * @param request 票据请求
     * @return 直传票据响应
     */
    public VisitorAvatarUploadTicketResponse createVisitorAvatarUploadTicket(
            String shareCode,
            VisitorAvatarUploadTicketRequest request
    ) {
        PortfolioEntity portfolio = requirePublishedPortfolio(shareCode);
        return visitorService.createAvatarUploadTicket(portfolio.getId(), request);
    }

    /**
     * 更新访客头像昵称。
     *
     * @param shareCode 分享编码
     * @param request 保存请求
     */
    public void updateVisitorProfile(String shareCode, VisitorProfileUpdateRequest request) {
        PortfolioEntity portfolio = requirePublishedPortfolio(shareCode);
        visitorService.saveProfile(portfolio.getId(), request);
    }

    /**
     * 填充访客打开相关字段。
     *
     * @param response 响应
     * @param visitorSession 访客会话
     * @param visitRecordId 访问汇总 ID
     * @param portfolioId 作品集 ID
     */
    private void fillVisitorProfileOpenFields(
            VisitorPortfolioResponse response,
            VisitorService.VisitorSession visitorSession,
            Long visitRecordId,
            Long portfolioId
    ) {
        fillVisitorProfileOpenFields(response, visitorSession, visitRecordId, portfolioId, false);
    }

    /**
     * 填充访客打开相关字段。
     *
     * @param response 响应
     * @param visitorSession 访客会话
     * @param visitRecordId 访问汇总 ID
     * @param portfolioId 作品集 ID
     * @param suppressVisitorProfile 是否压制访客头像昵称授权
     */
    private void fillVisitorProfileOpenFields(
            VisitorPortfolioResponse response,
            VisitorService.VisitorSession visitorSession,
            Long visitRecordId,
            Long portfolioId,
            boolean suppressVisitorProfile
    ) {
        VisitorEntity visitor = visitorSession.visitor();
        response.setVisitorKey(visitor.getVisitorKey());
        VisitorAuthTokenService.VisitorLoginToken loginToken =
                visitorAuthTokenService.issueToken(visitor.getId(), visitor.getVisitorKey());
        response.setTokenType(loginToken.tokenType());
        response.setToken(loginToken.token());
        response.setExpiresInSeconds(loginToken.expiresInSeconds());
        response.setNewVisitor(visitorSession.newVisitor());
        boolean needVisitorProfile = !suppressVisitorProfile && needVisitorProfile(visitor);
        response.setNeedVisitorProfile(needVisitorProfile);
        if (needVisitorProfile) {
            response.setVisitorProfileToken(visitorService.createProfileToken(visitor.getId(), portfolioId, visitRecordId));
        }
    }

    /**
     * 判断访客是否仍需补充头像昵称。
     *
     * @param visitor 访客实体
     * @return 是否需要补充资料
     */
    private boolean needVisitorProfile(VisitorEntity visitor) {
        return visitor == null || !hasText(visitor.getNickname()) || !hasText(visitor.getAvatarUrl());
    }

    /**
     * 查询访客档期。
     *
     * @param shareCode 分享编码
     * @param startDate 开始日期
     * @param endDate 结束日期
     * @param scope 查询范围
     * @param visitorKey 旧版客户端兼容参数，服务端已改用访客认证上下文并忽略该值
     * @param idempotencyKey 幂等键
     * @return 档期响应
     */
    public VisitorPortfolioScheduleResponse querySchedule(
            String shareCode,
            LocalDate startDate,
            LocalDate endDate,
            String scope,
            @Deprecated
            String visitorKey,
            String idempotencyKey
    ) {
        PortfolioEntity portfolio = requirePublishedPortfolio(shareCode);
        String authenticatedVisitorKey = VisitorContextHolder.requireVisitorKey();
        LocalDate normalizedStart = startDate == null ? LocalDate.now() : startDate;
        LocalDate normalizedEnd = endDate == null ? normalizedStart : endDate;
        List<ScheduleEntity> schedules = scheduleEntityMapper.selectList(
                Wrappers.lambdaQuery(ScheduleEntity.class)
                        .eq(ScheduleEntity::getUserId, portfolio.getOwnerId())
                        .ge(ScheduleEntity::getScheduleDate, normalizedStart)
                        .le(ScheduleEntity::getScheduleDate, normalizedEnd)
                        .orderByAsc(ScheduleEntity::getScheduleDate)
                        .orderByAsc(ScheduleEntity::getStartTimeSnapshot)
        );
        portfolioVisitService.recordScheduleQuery(portfolio, authenticatedVisitorKey, normalizedStart, idempotencyKey);
        VisitorPortfolioScheduleResponse response = new VisitorPortfolioScheduleResponse();
        response.setSchedules(safeList(schedules).stream().map(this::buildScheduleItem).toList());
        return response;
    }

    /**
     * 查询档期组件月历选项，不记录访客事件。
     *
     * @param shareCode 分享编码
     * @param month 月份，格式 yyyy-MM
     * @param componentKey 组件实例键
     * @return 月历选项响应
     */
    public PortfolioScheduleOptionsResponse queryScheduleOptions(String shareCode, String month, String componentKey) {
        PortfolioEntity portfolio = requirePublishedPortfolio(shareCode);
        PortfolioConfigDto config = parseConfig(portfolio.getPublishedConfigJson());
        requireScheduleComponentConfig(config, componentKey);
        return buildScheduleOptions(portfolio.getOwnerId(), parseMonth(month));
    }

    /**
     * 提交档期查询并记录访客事件。
     *
     * @param shareCode 分享编码
     * @param request 查询请求
     * @return 查询响应
     */
    @Transactional(rollbackFor = Exception.class)
    public PortfolioScheduleQueryResponse submitScheduleQuery(String shareCode, PortfolioScheduleQueryRequest request) {
        if (request == null) {
            throw new BusinessException(PortfolioMessage.SCHEDULE_QUERY_REQUEST_REQUIRED_MESSAGE);
        }
        request.setVisitorKey(VisitorContextHolder.requireVisitorKey());
        PortfolioEntity portfolio = requirePublishedPortfolio(shareCode);
        PortfolioConfigDto config = parseConfig(portfolio.getPublishedConfigJson());
        Map<String, Object> componentConfig = requireScheduleComponentConfig(config, request.getComponentKey());
        PortfolioScheduleQueryResponse response = buildScheduleQueryResponse(portfolio.getOwnerId(), request);
        Map<String, Object> metadata = buildScheduleQueryMetadata(request, response, componentConfig);
        PortfolioVisitService.ScheduleQueryRecordResult recordResult = portfolioVisitService.recordScheduleQuery(
                portfolio,
                request.getVisitorKey(),
                request.getQueriedDate(),
                metadata,
                request.getIdempotencyKey()
        );
        recordScheduleQuerySnapshot(portfolio, config, request, response, componentConfig, recordResult);
        return response;
    }

    /**
     * 记录访客事件。
     *
     * @param shareCode 分享编码
     * @param request 事件请求
     */
    public void recordEvent(String shareCode, VisitorPortfolioEventRequest request) {
        PortfolioEntity portfolio = requirePublishedPortfolio(shareCode);
        Long visitorId = VisitorContextHolder.requireVisitorId();
        String visitorKey = VisitorContextHolder.requireVisitorKey();
        request.setVisitorKey(visitorKey);
        VisitorEntity visitor = visitorService.findById(visitorId);
        if (visitor != null && ownerSelfVisitService.isOwnerSelfVisitor(
                portfolio.getOwnerId(),
                visitor.getOpenid(),
                portfolio.getId(),
                visitorId,
                request.getEventType()
        )) {
            return;
        }
        portfolioVisitService.recordEvent(portfolio, visitorId, request);
    }

    /**
     * 查询已发布作品集。
     *
     * @param shareCode 分享编码
     * @return 作品集
     */
    PortfolioEntity requirePublishedPortfolio(String shareCode) {
        PortfolioEntity portfolio = portfolioEntityMapper.selectOne(
                Wrappers.lambdaQuery(PortfolioEntity.class)
                        .eq(PortfolioEntity::getShareCode, shareCode)
                        .last("LIMIT 1")
        );
        if (portfolio == null
                || !PortfolioPublicationStatusDict.PUBLISHED.getCode().equals(portfolio.getPublicationStatus())
                || !PortfolioOwnerTypeDict.USER.getCode().equals(portfolio.getOwnerType())) {
            throw new BusinessException(PortfolioMessage.PORTFOLIO_UNAVAILABLE_MESSAGE);
        }
        return portfolio;
    }

    /**
     * 构建正常响应。
     *
     * @param portfolio 作品集
     * @param config 配置
     * @return 响应
     */
    private VisitorPortfolioResponse buildNormalResponse(PortfolioEntity portfolio, PortfolioConfigDto config) {
        VisitorPortfolioResponse response = new VisitorPortfolioResponse();
        response.setShareCode(portfolio.getShareCode());
        response.setPortfolioId(portfolio.getId());
        response.setPublishedRevision(portfolio.getPublishedRevision());
        response.setTitle(resolveTitle(config));
        response.setUnderMaintenance(false);
        response.setConfig(config);
        return response;
    }

    /**
     * 构建维护中响应。
     *
     * @param portfolio 作品集
     * @param config 配置
     * @return 响应
     */
    private VisitorPortfolioResponse buildMaintenanceResponse(PortfolioEntity portfolio, PortfolioConfigDto config) {
        VisitorPortfolioResponse response = buildNormalResponse(portfolio, config);
        response.setUnderMaintenance(true);
        response.setConfig(null);
        VisitorPortfolioResponse.MaintenanceText text = new VisitorPortfolioResponse.MaintenanceText();
        text.setPrimary(MAINTENANCE_PRIMARY);
        text.setSecondary(MAINTENANCE_SECONDARY);
        response.setMaintenanceText(text);
        response.setRenderData(portfolioRenderService.render(portfolio, config, false, true, text, null));
        return response;
    }

    /**
     * 构建访客档期项。
     *
     * @param schedule 档期实体
     * @return 访客档期项
     */
    private VisitorPortfolioScheduleResponse.Item buildScheduleItem(ScheduleEntity schedule) {
        ScheduleStatusDict status = ScheduleStatusDict.fromCode(schedule.getStatus());
        VisitorPortfolioScheduleResponse.Item item = new VisitorPortfolioScheduleResponse.Item();
        item.setDate(schedule.getScheduleDate() == null ? "" : schedule.getScheduleDate().toString());
        item.setSlotName(schedule.getSlotNameSnapshot());
        item.setStartTime(schedule.getStartTimeSnapshot() == null ? "" : schedule.getStartTimeSnapshot().format(TIME_FORMATTER));
        item.setEndTime(schedule.getEndTimeSnapshot() == null ? "" : schedule.getEndTimeSnapshot().format(TIME_FORMATTER));
        item.setColor(schedule.getColorSnapshot());
        item.setStatus(schedule.getStatus());
        item.setStatusText(status == null ? schedule.getStatus() : status.getDisplayName());
        item.setStatusTone(status == null ? "muted" : status.getTone());
        return item;
    }

    /**
     * 构建访客月历选项。
     *
     * <p>访客端只提前展示可查询档位和空白月历，不返回已有档期标记，避免查询前泄露维护者档期。</p>
     *
     * @param ownerId 作品集归属用户 ID
     * @param yearMonth 月份
     * @return 月历选项
     */
    private PortfolioScheduleOptionsResponse buildScheduleOptions(Long ownerId, YearMonth yearMonth) {
        List<SlotDefinitionEntity> slots = safeList(slotDefinitionEntityMapper.selectList(
                Wrappers.lambdaQuery(SlotDefinitionEntity.class)
                        .eq(SlotDefinitionEntity::getUserId, ownerId)
                        .eq(SlotDefinitionEntity::getStatus, SlotDefinitionStatusDict.ACTIVE.getCode())
                        .orderByAsc(SlotDefinitionEntity::getStartTime)
                        .orderByAsc(SlotDefinitionEntity::getId)
        ));
        PortfolioScheduleOptionsResponse response = new PortfolioScheduleOptionsResponse();
        response.setYearMonth(yearMonth.format(MONTH_FORMATTER));
        response.setSlotDefinitions(slots.stream().map(this::buildSlotDefinitionItem).toList());
        response.setSchedules(List.of());
        response.setDays(buildMonthDays(yearMonth, List.of()));
        return response;
    }

    /**
     * 构建提交查询响应。
     *
     * @param ownerId 作品集归属用户 ID
     * @param request 查询请求
     * @return 查询响应
     */
    private PortfolioScheduleQueryResponse buildScheduleQueryResponse(Long ownerId, PortfolioScheduleQueryRequest request) {
        if (request == null || request.getQueriedDate() == null) {
            throw new BusinessException(PortfolioMessage.SCHEDULE_QUERY_DATE_REQUIRED_MESSAGE);
        }
        if (request.getSlotDefinitionId() == null) {
            throw new BusinessException(PortfolioMessage.SCHEDULE_QUERY_SLOT_REQUIRED_MESSAGE);
        }
        SlotDefinitionEntity slot = slotDefinitionEntityMapper.selectById(request.getSlotDefinitionId());
        if (slot == null
                || !Objects.equals(slot.getUserId(), ownerId)
                || !SlotDefinitionStatusDict.ACTIVE.getCode().equals(slot.getStatus())) {
            throw new BusinessException(PortfolioMessage.SCHEDULE_QUERY_SLOT_UNAVAILABLE_MESSAGE);
        }
        ScheduleEntity schedule = scheduleEntityMapper.selectOne(
                Wrappers.lambdaQuery(ScheduleEntity.class)
                        .eq(ScheduleEntity::getUserId, ownerId)
                        .eq(ScheduleEntity::getScheduleDate, request.getQueriedDate())
                        .eq(ScheduleEntity::getSlotDefinitionId, request.getSlotDefinitionId())
                        .last("LIMIT 1")
        );
        return buildScheduleQueryResponse(request.getQueriedDate(), slot, schedule);
    }

    /**
     * 构建提交查询响应。
     *
     * @param queriedDate 查询日期
     * @param slot 档位定义
     * @param schedule 档期记录
     * @return 查询响应
     */
    private PortfolioScheduleQueryResponse buildScheduleQueryResponse(
            LocalDate queriedDate,
            SlotDefinitionEntity slot,
            ScheduleEntity schedule
    ) {
        ScheduleStatusDict status = schedule == null ? null : ScheduleStatusDict.fromCode(schedule.getStatus());
        boolean booked = schedule != null && ScheduleStatusDict.BOOKED.getCode().equals(schedule.getStatus());
        PortfolioScheduleQueryResponse response = new PortfolioScheduleQueryResponse();
        response.setQueriedDate(queriedDate.toString());
        response.setSlotDefinitionId(slot.getId());
        response.setSlotName(schedule == null ? slot.getName() : schedule.getSlotNameSnapshot());
        response.setStartTime(formatTime(schedule == null ? slot.getStartTime() : schedule.getStartTimeSnapshot()));
        response.setEndTime(formatTime(schedule == null ? slot.getEndTime() : schedule.getEndTimeSnapshot()));
        response.setColor(schedule == null ? slot.getColor() : schedule.getColorSnapshot());
        response.setStatus(schedule == null ? STATUS_AVAILABLE : schedule.getStatus());
        response.setStatusText(schedule == null ? MESSAGE_AVAILABLE : status == null ? schedule.getStatus() : status.getDisplayName());
        response.setAvailable(!booked);
        response.setMessage(booked ? MESSAGE_BOOKED : MESSAGE_AVAILABLE);
        return response;
    }

    /**
     * 构建档期查询事件元数据。
     *
     * @param request 查询请求
     * @param response 查询响应
     * @param componentConfig 组件配置
     * @return 元数据
     */
    private Map<String, Object> buildScheduleQueryMetadata(
            PortfolioScheduleQueryRequest request,
            PortfolioScheduleQueryResponse response,
            Map<String, Object> componentConfig
    ) {
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("componentKey", request.getComponentKey());
        metadata.put("displayMode", defaultString(asString(componentConfig.get(CONFIG_KEY_DISPLAY_MODE)), DISPLAY_MODE_MODAL_CALENDAR));
        metadata.put("slotDefinitionId", response.getSlotDefinitionId());
        metadata.put("slotName", response.getSlotName());
        metadata.put("startTime", response.getStartTime());
        metadata.put("endTime", response.getEndTime());
        metadata.put("status", response.getStatus());
        metadata.put("available", response.isAvailable());
        metadata.put("resultMessage", response.getMessage());
        return metadata;
    }

    /**
     * 写入按钮查档业务快照。
     *
     * @param portfolio 作品集
     * @param config 作品集配置
     * @param request 查询请求
     * @param response 查询响应
     * @param componentConfig 查档组件配置
     * @param recordResult 访问事件写入结果
     */
    private void recordScheduleQuerySnapshot(
            PortfolioEntity portfolio,
            PortfolioConfigDto config,
            PortfolioScheduleQueryRequest request,
            PortfolioScheduleQueryResponse response,
            Map<String, Object> componentConfig,
            PortfolioVisitService.ScheduleQueryRecordResult recordResult
    ) {
        if (recordResult == null || !recordResult.isSnapshotRecordable()
                || recordResult.getRecord() == null || recordResult.getOccurredAt() == null) {
            return;
        }
        VisitRecordEntity visitRecord = recordResult.getRecord();
        ScheduleQueryRecordEntity record = new ScheduleQueryRecordEntity();
        record.setPortfolioId(portfolio.getId());
        record.setPortfolioType(PortfolioTypeDict.PERSONAL.getCode());
        record.setPortfolioTitleSnapshot(resolveTitle(config));
        record.setVisitRecordId(visitRecord.getId());
        record.setVisitorId(visitRecord.getVisitorId());
        record.setVisitorKey(defaultString(visitRecord.getVisitorKey(), request.getVisitorKey()));
        record.setOwnerType(portfolio.getOwnerType());
        record.setOwnerId(portfolio.getOwnerId());
        record.setSourceType(defaultSourceType(visitRecord.getSourceType()));
        record.setDisplayMode(defaultString(asString(componentConfig.get(CONFIG_KEY_DISPLAY_MODE)), DISPLAY_MODE_MODAL_CALENDAR));
        record.setQueriedDate(request.getQueriedDate());
        record.setSlotDefinitionId(response.getSlotDefinitionId());
        record.setSlotNameSnapshot(defaultString(response.getSlotName(), ""));
        record.setStartTimeSnapshot(parseTime(response.getStartTime()));
        record.setEndTimeSnapshot(parseTime(response.getEndTime()));
        record.setColorSnapshot(defaultString(response.getColor(), ""));
        record.setResultStatus(defaultString(response.getStatus(), ""));
        record.setResultStatusText(defaultString(response.getStatusText(), ""));
        record.setAvailable(response.isAvailable() ? 1 : 0);
        record.setResultMessage(defaultString(response.getMessage(), ""));
        record.setQueriedAt(recordResult.getOccurredAt());
        scheduleQueryRecordEntityMapper.insert(record);
    }

    /**
     * 校验档期查询组件存在。
     *
     * @param config 作品集配置
     * @param componentKey 组件实例键
     * @return 组件配置
     */
    private Map<String, Object> requireScheduleComponentConfig(PortfolioConfigDto config, String componentKey) {
        return safeList(config == null ? null : config.getComponents()).stream()
                .filter(component -> component != null && Boolean.TRUE.equals(component.getEnabled()))
                .filter(component -> COMPONENT_TYPE_SCHEDULE_QUERY.equals(component.getComponentType()))
                .filter(component -> Objects.equals(component.getComponentKey(), componentKey))
                .findFirst()
                .map(component -> component.getConfig() == null ? Map.<String, Object>of() : component.getConfig())
                .orElseThrow(() -> new BusinessException(PortfolioMessage.SCHEDULE_QUERY_COMPONENT_NOT_FOUND_MESSAGE));
    }

    /**
     * 构建档位定义响应项。
     *
     * @param slot 档位定义
     * @return 响应项
     */
    private PortfolioScheduleOptionsResponse.SlotDefinitionItem buildSlotDefinitionItem(SlotDefinitionEntity slot) {
        PortfolioScheduleOptionsResponse.SlotDefinitionItem item = new PortfolioScheduleOptionsResponse.SlotDefinitionItem();
        item.setId(slot.getId());
        item.setName(slot.getName());
        item.setStartTime(formatTime(slot.getStartTime()));
        item.setEndTime(formatTime(slot.getEndTime()));
        item.setColor(slot.getColor());
        return item;
    }

    /**
     * 构建月历档期响应项。
     *
     * @param schedule 档期
     * @return 响应项
     */
    private PortfolioScheduleOptionsResponse.ScheduleItem buildScheduleOptionItem(ScheduleEntity schedule) {
        String statusCode = defaultString(schedule.getStatus(), "");
        ScheduleStatusDict status = ScheduleStatusDict.fromCode(statusCode);
        PortfolioScheduleOptionsResponse.ScheduleItem item = new PortfolioScheduleOptionsResponse.ScheduleItem();
        item.setDate(schedule.getScheduleDate() == null ? "" : schedule.getScheduleDate().toString());
        item.setSlotDefinitionId(schedule.getSlotDefinitionId());
        item.setSlotName(defaultString(schedule.getSlotNameSnapshot(), ""));
        item.setStartTime(formatTime(schedule.getStartTimeSnapshot()));
        item.setEndTime(formatTime(schedule.getEndTimeSnapshot()));
        item.setColor(defaultString(schedule.getColorSnapshot(), ""));
        item.setStatus(statusCode);
        item.setStatusText(status == null ? statusCode : status.getDisplayName());
        item.setStatusTone(status == null ? "muted" : status.getTone());
        return item;
    }

    /**
     * 构建固定 6 行月历格子。
     *
     * @param yearMonth 月份
     * @param schedules 当月档期
     * @return 月历格子
     */
    private List<PortfolioScheduleOptionsResponse.MonthDayItem> buildMonthDays(
            YearMonth yearMonth,
            List<ScheduleEntity> schedules
    ) {
        LocalDate firstDay = yearMonth.atDay(1);
        LocalDate cursor = firstDay.minusDays(firstDay.getDayOfWeek().getValue() % 7L);
        Map<LocalDate, List<ScheduleEntity>> schedulesByDate = safeList(schedules).stream()
                .filter(schedule -> schedule.getScheduleDate() != null)
                .collect(Collectors.groupingBy(ScheduleEntity::getScheduleDate));
        List<PortfolioScheduleOptionsResponse.MonthDayItem> days = new ArrayList<>();
        for (int index = 0; index < 42; index++) {
            LocalDate date = cursor.plus(index, ChronoUnit.DAYS);
            List<ScheduleEntity> daySchedules = schedulesByDate.getOrDefault(date, List.of());
            PortfolioScheduleOptionsResponse.MonthDayItem item = new PortfolioScheduleOptionsResponse.MonthDayItem();
            item.setDate(date.toString());
            item.setDayNumber(date.getDayOfMonth());
            item.setCurrentMonth(YearMonth.from(date).equals(yearMonth));
            item.setColors(daySchedules.stream()
                    .map(ScheduleEntity::getColorSnapshot)
                    .filter(this::hasText)
                    .distinct()
                    .toList());
            item.setCount(daySchedules.size());
            days.add(item);
        }
        return days;
    }

    /**
     * 解析标题。
     *
     * @param config 配置
     * @return 标题
     */
    private String resolveTitle(PortfolioConfigDto config) {
        if (config != null && config.getShare() != null && hasText(config.getShare().getTitle())) {
            return config.getShare().getTitle();
        }
        return DEFAULT_TITLE;
    }

    /**
     * 解析配置。
     *
     * @param configJson 配置 JSON
     * @return 配置
     */
    private PortfolioConfigDto parseConfig(String configJson) {
        if (!hasText(configJson)) {
            return null;
        }
        try {
            return JSON.parseObject(configJson, PortfolioConfigDto.class);
        } catch (Exception e) {
            throw new BusinessException(PortfolioMessage.PORTFOLIO_UNAVAILABLE_MESSAGE, e);
        }
    }

    /**
     * 判断字符串是否有内容。
     *
     * @param value 原值
     * @return true 表示有内容
     */
    private boolean hasText(String value) {
        return value != null && !value.isBlank();
    }

    /**
     * 解析月份。
     *
     * @param month 月份文本
     * @return 月份
     */
    private YearMonth parseMonth(String month) {
        try {
            return YearMonth.parse(month, MONTH_FORMATTER);
        } catch (DateTimeParseException e) {
            throw new BusinessException(PortfolioMessage.SCHEDULE_QUERY_DATE_INVALID_MESSAGE, e);
        }
    }

    /**
     * 格式化时间。
     *
     * @param time 时间
     * @return HH:mm 文本
     */
    private String formatTime(LocalTime time) {
        return time == null ? "" : time.format(TIME_FORMATTER);
    }

    /**
     * 解析 HH:mm 时间。
     *
     * @param value 时间文本
     * @return 时间
     */
    private LocalTime parseTime(String value) {
        if (!hasText(value)) {
            return null;
        }
        try {
            return LocalTime.parse(value.strip(), TIME_FORMATTER);
        } catch (DateTimeParseException e) {
            return null;
        }
    }

    /**
     * 空字符串兜底。
     *
     * @param value 原值
     * @return 字符串
     */
    private String asString(Object value) {
        return value == null ? "" : String.valueOf(value);
    }

    /**
     * 默认字符串。
     *
     * @param value 原值
     * @param fallback 兜底值
     * @return 字符串
     */
    private String defaultString(String value, String fallback) {
        return hasText(value) ? value.strip() : fallback;
    }

    /**
     * 来源类型兜底。
     *
     * @param sourceType 来源类型
     * @return 有效来源类型
     */
    private String defaultSourceType(String sourceType) {
        return VisitSourceTypeDict.fromCode(sourceType) == null
                ? VisitSourceTypeDict.UNKNOWN.getCode()
                : sourceType;
    }

    /**
     * 空列表兜底。
     *
     * @param list 原列表
     * @param <T> 元素类型
     * @return 非空列表
     */
    private <T> List<T> safeList(List<T> list) {
        return list == null ? List.of() : list;
    }
}
