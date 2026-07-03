package com.jxc.wefolio.service;

import com.alibaba.fastjson2.JSON;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.jxc.wefolio.dict.PortfolioOwnerTypeDict;
import com.jxc.wefolio.dict.PortfolioPublicationStatusDict;
import com.jxc.wefolio.dict.PointSceneCodeDict;
import com.jxc.wefolio.dict.ScheduleStatusDict;
import com.jxc.wefolio.dto.PortfolioConfigDto;
import com.jxc.wefolio.dto.VisitorPortfolioEventRequest;
import com.jxc.wefolio.dto.VisitorPortfolioResponse;
import com.jxc.wefolio.dto.VisitorPortfolioScheduleResponse;
import com.jxc.wefolio.entity.PortfolioEntity;
import com.jxc.wefolio.entity.ScheduleEntity;
import com.jxc.wefolio.entity.VisitRecordEntity;
import com.jxc.wefolio.exception.BusinessException;
import com.jxc.wefolio.mapper.PortfolioEntityMapper;
import com.jxc.wefolio.mapper.ScheduleEntityMapper;
import com.jxc.wefolio.message.PortfolioMessage;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.List;

/**
 * 访客作品集服务 — 负责访客读取已发布作品集和公开档期。
 */
@Service
@RequiredArgsConstructor
public class VisitorPortfolioService {

    /** 维护中英文主文案 */
    private static final String MAINTENANCE_PRIMARY = "UNDER MAINTENANCE";

    /** 维护中中文副文案 */
    private static final String MAINTENANCE_SECONDARY = "维护中";

    /** 默认标题 */
    private static final String DEFAULT_TITLE = "个人作品集";

    /** 时间展示格式 */
    private static final DateTimeFormatter TIME_FORMATTER = DateTimeFormatter.ofPattern("HH:mm");

    /** 作品集 Mapper */
    private final PortfolioEntityMapper portfolioEntityMapper;

    /** 档期 Mapper */
    private final ScheduleEntityMapper scheduleEntityMapper;

    /** 积分服务 */
    private final PointService pointService;

    /** 访问服务 */
    private final PortfolioVisitService portfolioVisitService;

    /** 作品集渲染服务 */
    private final PortfolioRenderService portfolioRenderService;

    /**
     * 获取访客作品集。
     *
     * @param shareCode 分享编码
     * @param visitorKey 访客摘要
     * @param sourceType 来源类型
     * @param idempotencyKey 打开事件幂等键
     * @return 访客作品集响应
     */
    public VisitorPortfolioResponse getPortfolio(
            String shareCode,
            String visitorKey,
            String sourceType,
            String idempotencyKey
    ) {
        PortfolioEntity portfolio = requirePublishedPortfolio(shareCode);
        PortfolioConfigDto config = parseConfig(portfolio.getPublishedConfigJson());
        try {
            pointService.assertCanConsume(portfolio.getOwnerId(), PointSceneCodeDict.VISIT_PERSONAL_PORTFOLIO.getCode(), 1);
        } catch (BusinessException e) {
            return buildMaintenanceResponse(portfolio, config);
        }
        VisitRecordEntity record = portfolioVisitService.recordOpen(portfolio, visitorKey, sourceType, idempotencyKey);
        VisitorPortfolioResponse response = buildNormalResponse(portfolio, config);
        response.setVisitRecordId(record == null ? null : record.getId());
        response.setRenderData(portfolioRenderService.render(
                portfolio,
                config,
                false,
                false,
                null,
                response.getVisitRecordId()
        ));
        return response;
    }

    /**
     * 查询访客档期。
     *
     * @param shareCode 分享编码
     * @param startDate 开始日期
     * @param endDate 结束日期
     * @param scope 查询范围
     * @param visitorKey 访客摘要
     * @param idempotencyKey 幂等键
     * @return 档期响应
     */
    public VisitorPortfolioScheduleResponse querySchedule(
            String shareCode,
            LocalDate startDate,
            LocalDate endDate,
            String scope,
            String visitorKey,
            String idempotencyKey
    ) {
        PortfolioEntity portfolio = requirePublishedPortfolio(shareCode);
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
        portfolioVisitService.recordScheduleQuery(portfolio, visitorKey, normalizedStart, idempotencyKey);
        VisitorPortfolioScheduleResponse response = new VisitorPortfolioScheduleResponse();
        response.setSchedules(safeList(schedules).stream().map(this::buildScheduleItem).toList());
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
        portfolioVisitService.recordEvent(portfolio, request);
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
