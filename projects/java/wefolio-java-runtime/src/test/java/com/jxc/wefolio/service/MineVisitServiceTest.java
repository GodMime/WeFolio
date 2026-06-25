package com.jxc.wefolio.service;

import com.jxc.wefolio.common.auth.AuthContext;
import com.jxc.wefolio.common.auth.AuthContextHolder;
import com.jxc.wefolio.dto.MineVisitRecordsResponse;
import com.jxc.wefolio.entity.VisitEventEntity;
import com.jxc.wefolio.entity.VisitRecordEntity;
import com.jxc.wefolio.mapper.VisitEventEntityMapper;
import com.jxc.wefolio.mapper.VisitRecordEntityMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

/**
 * 访问记录服务测试 — 覆盖维护者视角统计、趋势和明细摘要。
 */
@ExtendWith(MockitoExtension.class)
class MineVisitServiceTest {

    @Mock
    private VisitRecordEntityMapper visitRecordEntityMapper;

    @Mock
    private VisitEventEntityMapper visitEventEntityMapper;

    @AfterEach
    void tearDown() {
        AuthContextHolder.clear();
    }

    @Test
    void visitRecordsAggregateSummaryTrendAndRecentDetails() {
        AuthContextHolder.set(new AuthContext(7L, "wf-dev-user-7"));
        LocalDate today = LocalDate.now();
        LocalDateTime now = LocalDateTime.now();

        VisitRecordEntity firstRecord = buildRecord(
                101L,
                "anonymous-visitor-key-8A21",
                "WECHAT_SHARE_CARD",
                "PERSONAL",
                "林安婚礼司仪",
                4,
                6,
                0,
                1,
                1,
                "[\"" + today.plusMonths(4).withDayOfMonth(3) + "\"]",
                "NOT_FOLLOWED_UP",
                now
        );
        VisitRecordEntity secondRecord = buildRecord(
                102L,
                "anonymous-visitor-key-C19F",
                "TEAM_PORTFOLIO",
                "TEAM",
                "星曜司仪团",
                2,
                3,
                2,
                0,
                0,
                null,
                "CONTACTED",
                now.minusHours(2)
        );
        List<VisitEventEntity> trendEvents = List.of(
                buildOpenedEvent(now),
                buildOpenedEvent(now.minusMinutes(8)),
                buildOpenedEvent(now.minusHours(2)),
                buildOpenedEvent(now.minusDays(1)),
                buildOpenedEvent(now.minusDays(6))
        );

        when(visitRecordEntityMapper.selectList(any())).thenReturn(List.of(firstRecord, secondRecord));
        when(visitEventEntityMapper.selectList(any())).thenReturn(trendEvents);

        MineVisitService service = new MineVisitService(visitRecordEntityMapper, visitEventEntityMapper);

        MineVisitRecordsResponse response = service.getVisitRecords();

        assertThat(response.getSummary().getTotalVisitCount()).isEqualTo(6L);
        assertThat(response.getSummary().getTodayVisitCount()).isEqualTo(3L);
        assertThat(response.getSummary().getScheduleQueryCount()).isEqualTo(1L);
        assertThat(response.getTrend().getPoints()).hasSize(7);
        assertThat(response.getTrend().getPoints().get(6).getValue()).isEqualTo(3L);
        assertThat(response.getRecords()).hasSize(2);
        assertThat(response.getRecords().get(0).getVisitorLabel()).isEqualTo("微信访客 8A21");
        assertThat(response.getRecords().get(0).getSourceText()).isEqualTo("来自分享卡片「林安婚礼司仪」");
        assertThat(response.getRecords().get(0).getSummaryText())
                .contains("第 4 次访问")
                .contains("查看作品 6 次")
                .contains("查询")
                .contains("档期")
                .contains("点击二维码 1 次");
        assertThat(response.getRecords().get(0).getFollowStatusText()).isEqualTo("未跟进");
        assertThat(response.getRecords().get(0).getFollowTone()).isEqualTo("rose");
        assertThat(response.getRecords().get(1).getSourceText()).isEqualTo("来自团队作品集「星曜司仪团」跳转");
        assertThat(response.getRecords().get(1).getSummaryText()).contains("播放视频 2 次").contains("未点二维码");
        assertThat(response.getRecords().get(1).getFollowTone()).isEqualTo("teal");
    }

    private VisitRecordEntity buildRecord(
            Long id,
            String visitorKey,
            String sourceType,
            String sourcePortfolioType,
            String sourceTitle,
            Integer visitCount,
            Integer viewWorkCount,
            Integer playVideoCount,
            Integer scheduleQueryCount,
            Integer qrActionCount,
            String queriedScheduleDates,
            String followStatus,
            LocalDateTime lastVisitedAt
    ) {
        VisitRecordEntity record = new VisitRecordEntity();
        record.setId(id);
        record.setVisitorKey(visitorKey);
        record.setSourceType(sourceType);
        record.setSourcePortfolioType(sourcePortfolioType);
        record.setSourcePortfolioTitleSnapshot(sourceTitle);
        record.setVisitCount(visitCount);
        record.setViewWorkCount(viewWorkCount);
        record.setPlayVideoCount(playVideoCount);
        record.setScheduleQueryCount(scheduleQueryCount);
        record.setQrActionCount(qrActionCount);
        record.setQueriedScheduleDates(queriedScheduleDates);
        record.setFollowStatus(followStatus);
        record.setLastVisitedAt(lastVisitedAt);
        return record;
    }

    private VisitEventEntity buildOpenedEvent(LocalDateTime occurredAt) {
        VisitEventEntity event = new VisitEventEntity();
        event.setEventType("PORTFOLIO_OPENED");
        event.setOccurredAt(occurredAt);
        return event;
    }
}
