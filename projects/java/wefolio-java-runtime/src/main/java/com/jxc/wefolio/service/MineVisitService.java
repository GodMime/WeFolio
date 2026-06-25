package com.jxc.wefolio.service;

import com.alibaba.fastjson2.JSON;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.jxc.wefolio.common.auth.AuthContextHolder;
import com.jxc.wefolio.dict.FollowStatusDict;
import com.jxc.wefolio.dict.PortfolioOwnerTypeDict;
import com.jxc.wefolio.dict.VisitEventTypeDict;
import com.jxc.wefolio.dict.VisitSourceTypeDict;
import com.jxc.wefolio.dto.MineVisitRecordsResponse;
import com.jxc.wefolio.entity.VisitEventEntity;
import com.jxc.wefolio.entity.VisitRecordEntity;
import com.jxc.wefolio.mapper.VisitEventEntityMapper;
import com.jxc.wefolio.mapper.VisitRecordEntityMapper;
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

    /** 趋势覆盖天数 */
    private static final int TREND_DAYS = 7;

    /** 日期展示格式 */
    private static final DateTimeFormatter DATE_FORMATTER = DateTimeFormatter.ISO_LOCAL_DATE;

    /** 查档日期展示格式 */
    private static final DateTimeFormatter SCHEDULE_DATE_FORMATTER = DateTimeFormatter.ofPattern("MM-dd");

    /** 最近访问时间展示格式 */
    private static final DateTimeFormatter VISITED_TIME_FORMATTER = DateTimeFormatter.ofPattern("MM-dd HH:mm");

    /** 访问汇总 Mapper */
    private final VisitRecordEntityMapper visitRecordEntityMapper;

    /** 访问事件 Mapper */
    private final VisitEventEntityMapper visitEventEntityMapper;

    /**
     * 获取当前维护者访问记录页数据。
     *
     * @return 访问记录页响应
     */
    public MineVisitRecordsResponse getVisitRecords() {
        Long userId = AuthContextHolder.requireUserId();
        List<VisitRecordEntity> records = selectOwnerVisitRecords(userId);
        List<VisitEventEntity> openedEvents = selectRecentOpenedEvents(userId);

        MineVisitRecordsResponse response = new MineVisitRecordsResponse();
        response.setSummary(buildSummary(records, openedEvents));
        response.setTrend(buildTrend(openedEvents));
        response.setRecords(buildRecords(records));
        return response;
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
    private List<MineVisitRecordsResponse.Record> buildRecords(List<VisitRecordEntity> records) {
        return records.stream()
                .sorted(Comparator.comparing(VisitRecordEntity::getLastVisitedAt,
                        Comparator.nullsLast(Comparator.reverseOrder())))
                .limit(RECORD_LIMIT)
                .map(this::buildRecord)
                .toList();
    }

    /**
     * 构建单条访问明细。
     *
     * @param record 访问汇总实体
     * @return 访问明细 DTO
     */
    private MineVisitRecordsResponse.Record buildRecord(VisitRecordEntity record) {
        String visitorCode = buildVisitorCode(record.getVisitorKey());
        String followStatus = defaultString(record.getFollowStatus(), FollowStatusDict.NOT_FOLLOWED_UP.getCode());
        MineVisitRecordsResponse.Record item = new MineVisitRecordsResponse.Record();
        item.setId(record.getId());
        item.setVisitorCode(visitorCode);
        item.setVisitorLabel("微信访客 " + visitorCode);
        item.setVisitorInitial(visitorCode.substring(0, 1));
        item.setSourceText(buildSourceText(record));
        item.setSummaryText(buildSummaryText(record));
        item.setFollowStatus(followStatus);
        item.setFollowStatusText(buildFollowStatusText(followStatus));
        item.setFollowTone(buildFollowTone(followStatus));
        item.setLastVisitedText(buildLastVisitedText(record.getLastVisitedAt()));
        return item;
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
}
