package com.jxc.wefolio.service;

import com.alibaba.fastjson2.JSON;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.jxc.wefolio.common.auth.AuthContextHolder;
import com.jxc.wefolio.dict.FollowStatusDict;
import com.jxc.wefolio.dict.PortfolioOwnerTypeDict;
import com.jxc.wefolio.dict.VisitEventTypeDict;
import com.jxc.wefolio.dict.VisitSourceTypeDict;
import com.jxc.wefolio.dto.MineVisitRecordsResponse;
import com.jxc.wefolio.entity.VisitEventEntity;
import com.jxc.wefolio.entity.VisitRecordEntity;
import com.jxc.wefolio.entity.VisitorEntity;
import com.jxc.wefolio.exception.BusinessException;
import com.jxc.wefolio.mapper.VisitEventEntityMapper;
import com.jxc.wefolio.mapper.VisitRecordEntityMapper;
import com.jxc.wefolio.mapper.VisitorEntityMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

/**
 * 访问记录服务 — 负责维护者视角的客资访问统计与明细聚合。
 */
@Service
@RequiredArgsConstructor
public class MineVisitService {

    /** 明细列表最多返回条数 */
    private static final int RECORD_LIMIT = 20;

    /** 事件明细默认页码 */
    private static final int FIRST_EVENT_PAGE_NO = 1;

    /** 事件明细默认页大小 */
    private static final int DEFAULT_EVENT_PAGE_SIZE = 20;

    /** 事件明细最大页大小 */
    private static final int MAX_EVENT_PAGE_SIZE = 50;

    /** 趋势覆盖天数 */
    private static final int TREND_DAYS = 7;

    /** MySQL 单条限制片段 */
    private static final String SQL_SINGLE_LIMIT_CLAUSE = "LIMIT 1";

    /** 日期展示格式 */
    private static final DateTimeFormatter DATE_FORMATTER = DateTimeFormatter.ISO_LOCAL_DATE;

    /** 查档日期展示格式 */
    private static final DateTimeFormatter SCHEDULE_DATE_FORMATTER = DateTimeFormatter.ofPattern("MM-dd");

    /** 最近访问时间展示格式 */
    private static final DateTimeFormatter VISITED_TIME_FORMATTER = DateTimeFormatter.ofPattern("MM-dd HH:mm");

    /** 事件时间展示格式 */
    private static final DateTimeFormatter EVENT_TIME_FORMATTER = DateTimeFormatter.ofPattern("HH:mm");

    /** 访问记录不存在提示 */
    private static final String VISIT_RECORD_NOT_FOUND_MESSAGE = "访问记录不存在或无访问权限";

    /** 默认事件补充说明 */
    private static final String DEFAULT_EVENT_DETAIL_TEXT = "暂无补充信息";

    /** 访问汇总 Mapper */
    private final VisitRecordEntityMapper visitRecordEntityMapper;

    /** 访问事件 Mapper */
    private final VisitEventEntityMapper visitEventEntityMapper;

    /** 访客身份 Mapper */
    private final VisitorEntityMapper visitorEntityMapper;

    /**
     * 获取当前维护者访问记录页数据。
     *
     * @return 访问记录页响应
     */
    public MineVisitRecordsResponse getVisitRecords() {
        Long userId = AuthContextHolder.requireUserId();
        List<VisitRecordEntity> records = selectOwnerVisitRecords(userId);
        List<VisitEventEntity> openedEvents = selectRecentOpenedEvents(userId);
        Map<Long, VisitorEntity> visitorsById = selectVisitorsById(records);

        MineVisitRecordsResponse response = new MineVisitRecordsResponse();
        response.setSummary(buildSummary(records, openedEvents));
        response.setTrend(buildTrend(openedEvents));
        response.setRecords(buildRecords(records, visitorsById));
        return response;
    }

    /**
     * 获取当前维护者某条访问记录的事件时间线。
     *
     * @param recordId 访问汇总记录 ID
     * @return 事件时间线
     */
    public MineVisitRecordsResponse.EventTimeline getVisitEvents(Long recordId) {
        return getVisitEvents(recordId, FIRST_EVENT_PAGE_NO, DEFAULT_EVENT_PAGE_SIZE);
    }

    /**
     * 获取当前维护者某条访问记录的分页事件时间线。
     *
     * @param recordId 访问汇总记录 ID
     * @param pageNo 页码，从 1 开始
     * @param pageSize 每页事件数量
     * @return 事件时间线
     */
    public MineVisitRecordsResponse.EventTimeline getVisitEvents(Long recordId, Integer pageNo, Integer pageSize) {
        Long userId = AuthContextHolder.requireUserId();
        VisitRecordEntity record = selectOwnerVisitRecord(userId, recordId);
        if (record == null) {
            throw new BusinessException(VISIT_RECORD_NOT_FOUND_MESSAGE);
        }
        int normalizedPageNo = normalizeEventPageNo(pageNo);
        int normalizedPageSize = normalizeEventPageSize(pageSize);
        VisitorEntity visitor = record.getVisitorId() == null
                ? null
                : visitorEntityMapper.selectById(record.getVisitorId());
        MineVisitRecordsResponse.Record recordView = buildRecord(record, visitor);
        Page<VisitEventEntity> eventPage = selectVisitEvents(record, normalizedPageNo, normalizedPageSize);
        List<VisitEventEntity> pagedEvents = eventPage.getRecords();
        boolean hasMore = eventPage.getCurrent() < eventPage.getPages();
        List<MineVisitRecordsResponse.VisitEventItem> eventItems = pagedEvents.stream()
                .map(this::buildVisitEventItem)
                .toList();

        MineVisitRecordsResponse.EventTimeline timeline = new MineVisitRecordsResponse.EventTimeline();
        timeline.setRecordId(record.getId());
        timeline.setVisitorLabel(recordView.getVisitorLabel());
        timeline.setVisitorInitial(recordView.getVisitorInitial());
        timeline.setVisitorAvatarUrl(recordView.getVisitorAvatarUrl());
        timeline.setSourceText(recordView.getSourceText());
        timeline.setFollowStatusText(recordView.getFollowStatusText());
        timeline.setFollowTone(recordView.getFollowTone());
        timeline.setLastVisitedText(recordView.getLastVisitedText());
        timeline.setPageNo(normalizedPageNo);
        timeline.setPageSize(normalizedPageSize);
        timeline.setHasMore(hasMore);
        timeline.setEvents(eventItems);
        return timeline;
    }

    /**
     * 标记当前维护者某条访问记录已跟进。
     *
     * @param recordId 访问汇总记录 ID
     * @return 更新后的访问明细
     */
    public MineVisitRecordsResponse.Record markVisitFollowed(Long recordId) {
        Long userId = AuthContextHolder.requireUserId();
        VisitRecordEntity record = selectOwnerVisitRecord(userId, recordId);
        if (record == null) {
            throw new BusinessException(VISIT_RECORD_NOT_FOUND_MESSAGE);
        }
        record.setFollowStatus(FollowStatusDict.CONTACTED.getCode());
        int updated = visitRecordEntityMapper.updateById(record);
        if (updated <= 0) {
            throw new BusinessException("访问记录跟进状态保存失败");
        }
        VisitorEntity visitor = record.getVisitorId() == null
                ? null
                : visitorEntityMapper.selectById(record.getVisitorId());
        return buildRecord(record, visitor);
    }

    /**
     * 查询当前维护者名下访问汇总。
     *
     * @param userId 当前用户 ID
     * @return 访问汇总列表
     */
    private List<VisitRecordEntity> selectOwnerVisitRecords(Long userId) {
        return visitRecordEntityMapper.selectList(
                Wrappers.lambdaQuery(VisitRecordEntity.class)
                        .eq(VisitRecordEntity::getOwnerType, PortfolioOwnerTypeDict.USER.getCode())
                        .eq(VisitRecordEntity::getOwnerId, userId)
                        .orderByDesc(VisitRecordEntity::getLastVisitedAt)
        );
    }

    /**
     * 查询当前维护者名下单条访问汇总。
     *
     * @param userId 当前用户 ID
     * @param recordId 访问汇总记录 ID
     * @return 访问汇总
     */
    private VisitRecordEntity selectOwnerVisitRecord(Long userId, Long recordId) {
        if (recordId == null) {
            return null;
        }
        return visitRecordEntityMapper.selectOne(
                Wrappers.lambdaQuery(VisitRecordEntity.class)
                        .eq(VisitRecordEntity::getId, recordId)
                        .eq(VisitRecordEntity::getOwnerType, PortfolioOwnerTypeDict.USER.getCode())
                        .eq(VisitRecordEntity::getOwnerId, userId)
                        .last(SQL_SINGLE_LIMIT_CLAUSE)
        );
    }

    /**
     * 查询访问记录的具体事件。
     *
     * @param record 访问汇总
     * @param pageNo 页码，从 1 开始
     * @param pageSize 每页事件数量
     * @return 访问事件列表
     */
    private Page<VisitEventEntity> selectVisitEvents(VisitRecordEntity record, int pageNo, int pageSize) {
        Page<VisitEventEntity> eventPage = new Page<>(pageNo, pageSize);
        return visitEventEntityMapper.selectPage(
                eventPage,
                Wrappers.lambdaQuery(VisitEventEntity.class)
                        .eq(VisitEventEntity::getVisitRecordId, record.getId())
                        .eq(VisitEventEntity::getOwnerType, record.getOwnerType())
                        .eq(VisitEventEntity::getOwnerId, record.getOwnerId())
                        .orderByDesc(VisitEventEntity::getOccurredAt)
                        .orderByDesc(VisitEventEntity::getId)
        );
    }

    /**
     * 归一化事件明细页码。
     *
     * @param pageNo 原始页码
     * @return 合法页码
     */
    private int normalizeEventPageNo(Integer pageNo) {
        if (pageNo == null || pageNo < FIRST_EVENT_PAGE_NO) {
            return FIRST_EVENT_PAGE_NO;
        }
        return pageNo;
    }

    /**
     * 归一化事件明细页大小。
     *
     * @param pageSize 原始页大小
     * @return 合法页大小
     */
    private int normalizeEventPageSize(Integer pageSize) {
        if (pageSize == null || pageSize <= 0) {
            return DEFAULT_EVENT_PAGE_SIZE;
        }
        return Math.min(pageSize, MAX_EVENT_PAGE_SIZE);
    }

    /**
     * 查询近 7 日作品集打开事件。
     *
     * @param userId 当前用户 ID
     * @return 打开事件列表
     */
    private List<VisitEventEntity> selectRecentOpenedEvents(Long userId) {
        LocalDate today = LocalDate.now();
        LocalDateTime start = today.minusDays(TREND_DAYS - 1L).atStartOfDay();
        LocalDateTime end = today.plusDays(1).atStartOfDay();
        return visitEventEntityMapper.selectList(
                Wrappers.lambdaQuery(VisitEventEntity.class)
                        .eq(VisitEventEntity::getOwnerType, PortfolioOwnerTypeDict.USER.getCode())
                        .eq(VisitEventEntity::getOwnerId, userId)
                        .eq(VisitEventEntity::getEventType, VisitEventTypeDict.PORTFOLIO_OPENED.getCode())
                        .ge(VisitEventEntity::getOccurredAt, start)
                        .lt(VisitEventEntity::getOccurredAt, end)
                        .orderByAsc(VisitEventEntity::getOccurredAt)
        );
    }

    /**
     * 构建顶部统计。
     *
     * @param records 访问汇总
     * @param openedEvents 近 7 日打开事件
     * @return 顶部统计
     */
    private MineVisitRecordsResponse.Summary buildSummary(
            List<VisitRecordEntity> records,
            List<VisitEventEntity> openedEvents
    ) {
        LocalDate today = LocalDate.now();
        MineVisitRecordsResponse.Summary summary = new MineVisitRecordsResponse.Summary();
        summary.setTotalVisitCount(sumInteger(records, VisitRecordEntity::getVisitCount));
        summary.setTodayVisitCount(openedEvents.stream()
                .map(VisitEventEntity::getOccurredAt)
                .filter(Objects::nonNull)
                .filter(time -> today.equals(time.toLocalDate()))
                .count());
        summary.setScheduleQueryCount(sumInteger(records, VisitRecordEntity::getScheduleQueryCount));
        return summary;
    }

    /**
     * 构建近 7 日趋势。
     *
     * @param openedEvents 作品集打开事件
     * @return 趋势数据
     */
    private MineVisitRecordsResponse.Trend buildTrend(List<VisitEventEntity> openedEvents) {
        LocalDate today = LocalDate.now();
        LocalDate startDate = today.minusDays(TREND_DAYS - 1L);
        Map<LocalDate, Long> countsByDate = openedEvents.stream()
                .map(VisitEventEntity::getOccurredAt)
                .filter(Objects::nonNull)
                .map(LocalDateTime::toLocalDate)
                .collect(Collectors.groupingBy(Function.identity(), Collectors.counting()));

        List<MineVisitRecordsResponse.TrendPoint> points = IntStream.range(0, TREND_DAYS)
                .mapToObj(index -> {
                    LocalDate date = startDate.plusDays(index);
                    MineVisitRecordsResponse.TrendPoint point = new MineVisitRecordsResponse.TrendPoint();
                    point.setDate(date.format(DATE_FORMATTER));
                    point.setLabel(toWeekLabel(date.getDayOfWeek()));
                    point.setValue(countsByDate.getOrDefault(date, 0L));
                    return point;
                })
                .toList();

        MineVisitRecordsResponse.Trend trend = new MineVisitRecordsResponse.Trend();
        trend.setPoints(points);
        trend.setChangeText(buildTrendChangeText(points));
        return trend;
    }

    /**
     * 构建最近访问明细。
     *
     * @param records 访问汇总
     * @return 明细列表
     */
    private List<MineVisitRecordsResponse.Record> buildRecords(
            List<VisitRecordEntity> records,
            Map<Long, VisitorEntity> visitorsById
    ) {
        return records.stream()
                .sorted(Comparator.comparing(VisitRecordEntity::getLastVisitedAt,
                        Comparator.nullsLast(Comparator.reverseOrder())))
                .limit(RECORD_LIMIT)
                .map(record -> buildRecord(record, record.getVisitorId() == null ? null : visitorsById.get(record.getVisitorId())))
                .toList();
    }

    /**
     * 构建单条访问明细。
     *
     * @param record 访问汇总实体
     * @return 访问明细 DTO
     */
    private MineVisitRecordsResponse.Record buildRecord(VisitRecordEntity record, VisitorEntity visitor) {
        String visitorCode = buildVisitorCode(record.getVisitorKey());
        String followStatus = defaultString(record.getFollowStatus(), FollowStatusDict.NOT_FOLLOWED_UP.getCode());
        MineVisitRecordsResponse.Record item = new MineVisitRecordsResponse.Record();
        item.setId(record.getId());
        item.setVisitorCode(visitorCode);
        item.setVisitorLabel(resolveVisitorLabel(visitor, visitorCode));
        item.setVisitorInitial(visitorCode.substring(0, 1));
        item.setVisitorAvatarUrl(resolveVisitorAvatarUrl(visitor));
        item.setSourceText(buildSourceText(record));
        item.setSummaryText(buildSummaryText(record));
        item.setFollowStatus(followStatus);
        item.setFollowStatusText(buildFollowStatusText(followStatus));
        item.setFollowTone(buildFollowTone(followStatus));
        item.setLastVisitedText(buildLastVisitedText(record.getLastVisitedAt()));
        return item;
    }

    /**
     * 构建访问事件展示项。
     *
     * @param event 访问事件
     * @return 事件展示项
     */
    private MineVisitRecordsResponse.VisitEventItem buildVisitEventItem(VisitEventEntity event) {
        String eventType = defaultString(event.getEventType(), "");
        MineVisitRecordsResponse.VisitEventItem item = new MineVisitRecordsResponse.VisitEventItem();
        item.setEventId(event.getId());
        item.setEventType(eventType);
        item.setTitle(buildEventTitle(eventType));
        item.setDetailText(buildEventDetailText(event));
        item.setOccurredDateText(buildEventDateText(event.getOccurredAt()));
        item.setOccurredTimeText(buildEventTimeText(event.getOccurredAt()));
        item.setTone(buildEventTone(eventType));
        return item;
    }

    /**
     * 构建事件标题。
     *
     * @param eventType 事件类型
     * @return 事件标题
     */
    private String buildEventTitle(String eventType) {
        VisitEventTypeDict type = VisitEventTypeDict.fromCode(eventType);
        return type == null ? "未知事件" : type.getDisplayName();
    }

    /**
     * 构建事件补充说明。
     *
     * @param event 访问事件
     * @return 补充说明
     */
    private String buildEventDetailText(VisitEventEntity event) {
        String eventType = defaultString(event.getEventType(), "");
        if (VisitEventTypeDict.WORK_VIEWED.getCode().equals(eventType)) {
            return event.getWorkId() == null ? DEFAULT_EVENT_DETAIL_TEXT : "作品 ID " + event.getWorkId();
        }
        if (VisitEventTypeDict.VIDEO_PLAYED.getCode().equals(eventType)) {
            return event.getWorkId() == null ? DEFAULT_EVENT_DETAIL_TEXT : "视频作品 ID " + event.getWorkId();
        }
        if (VisitEventTypeDict.SCHEDULE_QUERIED.getCode().equals(eventType)) {
            return event.getQueriedDate() == null
                    ? "查询档期"
                    : "查询 " + event.getQueriedDate().format(DATE_FORMATTER) + " 档期";
        }
        if (VisitEventTypeDict.QR_CODE_INTERACTED.getCode().equals(eventType)) {
            return "点击或长按二维码";
        }
        if (VisitEventTypeDict.CONTACT_FORM_EXPOSED.getCode().equals(eventType)) {
            return "打开联系表单";
        }
        if (VisitEventTypeDict.CONTACT_LEAD_SUBMITTED.getCode().equals(eventType)) {
            return "提交联系信息";
        }
        if (VisitEventTypeDict.MEMBER_PORTFOLIO_OPENED.getCode().equals(eventType)) {
            return "打开成员作品集";
        }
        if (VisitEventTypeDict.PORTFOLIO_OPENED.getCode().equals(eventType)) {
            return "访问作品集页面";
        }
        return DEFAULT_EVENT_DETAIL_TEXT;
    }

    /**
     * 构建事件日期文案。
     *
     * @param occurredAt 事件发生时间
     * @return 日期文案
     */
    private String buildEventDateText(LocalDateTime occurredAt) {
        return occurredAt == null ? "" : occurredAt.toLocalDate().format(DATE_FORMATTER);
    }

    /**
     * 构建事件时间文案。
     *
     * @param occurredAt 事件发生时间
     * @return 时间文案
     */
    private String buildEventTimeText(LocalDateTime occurredAt) {
        return occurredAt == null ? "" : occurredAt.format(EVENT_TIME_FORMATTER);
    }

    /**
     * 构建事件颜色语义。
     *
     * @param eventType 事件类型
     * @return 颜色语义
     */
    private String buildEventTone(String eventType) {
        if (VisitEventTypeDict.SCHEDULE_QUERIED.getCode().equals(eventType)) {
            return "blue";
        }
        if (VisitEventTypeDict.QR_CODE_INTERACTED.getCode().equals(eventType)
                || VisitEventTypeDict.CONTACT_LEAD_SUBMITTED.getCode().equals(eventType)) {
            return "rose";
        }
        if (VisitEventTypeDict.WORK_VIEWED.getCode().equals(eventType)
                || VisitEventTypeDict.VIDEO_PLAYED.getCode().equals(eventType)) {
            return "teal";
        }
        return "muted";
    }

    /**
     * 批量查询访客资料。
     *
     * @param records 访问汇总
     * @return 访客 ID 到访客资料的映射
     */
    private Map<Long, VisitorEntity> selectVisitorsById(List<VisitRecordEntity> records) {
        List<Long> visitorIds = records.stream()
                .map(VisitRecordEntity::getVisitorId)
                .filter(Objects::nonNull)
                .distinct()
                .toList();
        if (visitorIds.isEmpty()) {
            return Map.of();
        }
        return visitorEntityMapper.selectBatchIds(visitorIds).stream()
                .collect(Collectors.toMap(VisitorEntity::getId, Function.identity(), (left, right) -> left));
    }

    /**
     * 解析访客展示名称。
     *
     * @param visitor 访客资料
     * @param visitorCode 匿名短码
     * @return 展示名称
     */
    private String resolveVisitorLabel(VisitorEntity visitor, String visitorCode) {
        if (visitor != null && hasText(visitor.getNickname())) {
            return visitor.getNickname().strip();
        }
        return "微信访客 " + visitorCode;
    }

    /**
     * 解析访客头像地址。
     *
     * @param visitor 访客资料
     * @return 头像地址
     */
    private String resolveVisitorAvatarUrl(VisitorEntity visitor) {
        if (visitor != null && hasText(visitor.getAvatarUrl())) {
            return visitor.getAvatarUrl().strip();
        }
        return "";
    }

    /**
     * 汇总整数实体字段。
     *
     * @param records 访问汇总
     * @param mapper 字段读取器
     * @return 汇总值
     */
    private long sumInteger(List<VisitRecordEntity> records, Function<VisitRecordEntity, Integer> mapper) {
        return records.stream()
                .map(mapper)
                .filter(Objects::nonNull)
                .mapToLong(Integer::longValue)
                .sum();
    }

    /**
     * 构建匿名访客短码。
     *
     * @param visitorKey 匿名访客摘要
     * @return 四位短码
     */
    private String buildVisitorCode(String visitorKey) {
        String normalized = defaultString(visitorKey, "0000").replaceAll("[^A-Za-z0-9]", "").toUpperCase();
        if (normalized.length() >= 4) {
            return normalized.substring(normalized.length() - 4);
        }
        return String.format("%4s", normalized).replace(' ', '0');
    }

    /**
     * 构建访问来源文案。
     *
     * @param record 访问汇总实体
     * @return 来源文案
     */
    private String buildSourceText(VisitRecordEntity record) {
        String title = defaultString(record.getSourcePortfolioTitleSnapshot(), "");
        String titlePart = title.isBlank() ? "" : "「" + title + "」";
        String sourceType = defaultString(record.getSourceType(), VisitSourceTypeDict.UNKNOWN.getCode());
        if (VisitSourceTypeDict.WECHAT_SHARE_CARD.getCode().equals(sourceType)) {
            return "来自分享卡片" + titlePart;
        }
        if (VisitSourceTypeDict.QR_CODE.getCode().equals(sourceType)) {
            return "来自二维码扫码";
        }
        if (VisitSourceTypeDict.TEAM_PORTFOLIO.getCode().equals(sourceType)) {
            return "来自团队作品集" + titlePart + "跳转";
        }
        if (VisitSourceTypeDict.PERSONAL_PORTFOLIO.getCode().equals(sourceType)) {
            return "来自个人作品集" + titlePart + "跳转";
        }
        return "来自未知来源";
    }

    /**
     * 构建行为摘要。
     *
     * @param record 访问汇总实体
     * @return 行为摘要
     */
    private String buildSummaryText(VisitRecordEntity record) {
        int visitCount = safeInt(record.getVisitCount());
        int viewWorkCount = safeInt(record.getViewWorkCount());
        int playVideoCount = safeInt(record.getPlayVideoCount());
        int scheduleQueryCount = safeInt(record.getScheduleQueryCount());
        int qrActionCount = safeInt(record.getQrActionCount());

        List<String> parts = new java.util.ArrayList<>();
        parts.add("第 " + visitCount + " 次访问");
        parts.add("查看作品 " + viewWorkCount + " 次");
        if (playVideoCount > 0) {
            parts.add("播放视频 " + playVideoCount + " 次");
        }
        parts.add(buildScheduleText(scheduleQueryCount, record.getQueriedScheduleDates()));
        parts.add(qrActionCount > 0 ? "点击二维码 " + qrActionCount + " 次" : "未点二维码");
        return String.join(" · ", parts);
    }

    /**
     * 构建档期查询文案。
     *
     * @param scheduleQueryCount 查询次数
     * @param queriedScheduleDates 查询日期缓存 JSON
     * @return 档期查询文案
     */
    private String buildScheduleText(int scheduleQueryCount, String queriedScheduleDates) {
        if (scheduleQueryCount <= 0) {
            return "未查询档期";
        }
        LocalDate firstDate = parseFirstScheduleDate(queriedScheduleDates);
        if (firstDate != null) {
            return "查询 " + firstDate.format(SCHEDULE_DATE_FORMATTER) + " 档期";
        }
        return "查询档期 " + scheduleQueryCount + " 次";
    }

    /**
     * 解析第一个查询档期日期。
     *
     * @param queriedScheduleDates 查询日期缓存 JSON
     * @return 第一个有效日期
     */
    private LocalDate parseFirstScheduleDate(String queriedScheduleDates) {
        if (queriedScheduleDates == null || queriedScheduleDates.isBlank()) {
            return null;
        }
        try {
            List<String> dates = JSON.parseArray(queriedScheduleDates, String.class);
            if (dates == null || dates.isEmpty() || dates.get(0) == null) {
                return null;
            }
            return LocalDate.parse(dates.get(0));
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * 构建跟进状态展示文案。
     *
     * @param followStatus 跟进状态编码
     * @return 展示文案
     */
    private String buildFollowStatusText(String followStatus) {
        FollowStatusDict status = FollowStatusDict.fromCode(followStatus);
        return status == null ? FollowStatusDict.NOT_FOLLOWED_UP.getDisplayName() : status.getDisplayName();
    }

    /**
     * 构建跟进状态颜色语义。
     *
     * @param followStatus 跟进状态编码
     * @return 颜色语义
     */
    private String buildFollowTone(String followStatus) {
        if (FollowStatusDict.CONTACTED.getCode().equals(followStatus)) {
            return "teal";
        }
        if (FollowStatusDict.DEAL_WON.getCode().equals(followStatus)) {
            return "blue";
        }
        if (FollowStatusDict.INVALID.getCode().equals(followStatus)) {
            return "muted";
        }
        return "rose";
    }

    /**
     * 构建最近访问时间文案。
     *
     * @param lastVisitedAt 最近访问时间
     * @return 时间文案
     */
    private String buildLastVisitedText(LocalDateTime lastVisitedAt) {
        if (lastVisitedAt == null) {
            return "";
        }
        return lastVisitedAt.format(VISITED_TIME_FORMATTER);
    }

    /**
     * 构建趋势变化文案。
     *
     * @param points 趋势点
     * @return 趋势变化文案
     */
    private String buildTrendChangeText(List<MineVisitRecordsResponse.TrendPoint> points) {
        if (points.isEmpty()) {
            return "暂无趋势";
        }
        long first = points.get(0).getValue();
        long last = points.get(points.size() - 1).getValue();
        if (first == 0L && last == 0L) {
            return "暂无趋势";
        }
        if (first == last) {
            return "持平";
        }
        long percent = first == 0L ? 100L : Math.round(Math.abs(last - first) * 100.0 / first);
        return last > first ? "上升 " + percent + "%" : "下降 " + percent + "%";
    }

    /**
     * 转换周几标签。
     *
     * @param dayOfWeek 周几枚举
     * @return 中文周几
     */
    private String toWeekLabel(DayOfWeek dayOfWeek) {
        return switch (dayOfWeek) {
            case MONDAY -> "周一";
            case TUESDAY -> "周二";
            case WEDNESDAY -> "周三";
            case THURSDAY -> "周四";
            case FRIDAY -> "周五";
            case SATURDAY -> "周六";
            case SUNDAY -> "周日";
        };
    }

    /**
     * 安全整数。
     *
     * @param value 可空整数
     * @return 非空整数
     */
    private int safeInt(Integer value) {
        return value == null ? 0 : value;
    }

    /**
     * 空值安全字符串。
     *
     * @param value 原字符串
     * @param fallback 兜底字符串
     * @return 非空字符串
     */
    private String defaultString(String value, String fallback) {
        return value == null ? fallback : value;
    }

    /**
     * 判断文本是否有内容。
     *
     * @param value 原值
     * @return 是否有非空白内容
     */
    private boolean hasText(String value) {
        return value != null && !value.isBlank();
    }
}
