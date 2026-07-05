package com.jxc.wefolio.service;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.jxc.wefolio.common.auth.AuthContext;
import com.jxc.wefolio.common.auth.AuthContextHolder;
import com.jxc.wefolio.dto.MineVisitRecordsResponse;
import com.jxc.wefolio.entity.VisitEventEntity;
import com.jxc.wefolio.entity.VisitRecordEntity;
import com.jxc.wefolio.entity.VisitorEntity;
import com.jxc.wefolio.mapper.VisitEventEntityMapper;
import com.jxc.wefolio.mapper.VisitRecordEntityMapper;
import com.jxc.wefolio.mapper.VisitorEntityMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
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

    @Mock
    private VisitorEntityMapper visitorEntityMapper;

    @AfterEach
    void tearDown() {
        AuthContextHolder.clear();
    }

    @Test
    void visitRecordsAggregateSummaryTrendAndRecentDetails() {
        AuthContextHolder.set(new AuthContext(7L, "wf-dev-user-7"));
        LocalDate today = LocalDate.now();
        LocalDateTime now = today.atTime(12, 0);

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
                buildOpenedEvent(now.minusMinutes(16)),
                buildOpenedEvent(now.minusDays(1)),
                buildOpenedEvent(now.minusDays(6))
        );

        when(visitRecordEntityMapper.selectList(any())).thenReturn(List.of(firstRecord, secondRecord));
        when(visitEventEntityMapper.selectList(any())).thenReturn(trendEvents);

        MineVisitService service = new MineVisitService(visitRecordEntityMapper, visitEventEntityMapper, visitorEntityMapper);

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

    @Test
    void visitRecordsShouldPreferAuthorizedVisitorAvatarAndNickname() {
        AuthContextHolder.set(new AuthContext(7L, "wf-dev-user-7"));
        LocalDateTime now = LocalDateTime.now();
        VisitRecordEntity record = buildRecord(
                101L,
                "anonymous-visitor-key-8A21",
                "WECHAT_SHARE_CARD",
                "PERSONAL",
                "林安婚礼司仪",
                1,
                0,
                0,
                0,
                0,
                null,
                "NOT_FOLLOWED_UP",
                now
        );
        record.setVisitorId(1024L);
        VisitorEntity visitor = new VisitorEntity();
        visitor.setId(1024L);
        visitor.setNickname("小陈");
        visitor.setAvatarUrl("https://cdn.example.com/visit/visitor-avatar-1024-20260705093000-a1b2c3d4.jpg");
        when(visitRecordEntityMapper.selectList(any())).thenReturn(List.of(record));
        when(visitEventEntityMapper.selectList(any())).thenReturn(List.of(buildOpenedEvent(now)));
        when(visitorEntityMapper.selectBatchIds(List.of(1024L))).thenReturn(List.of(visitor));

        MineVisitRecordsResponse response = new MineVisitService(
                visitRecordEntityMapper,
                visitEventEntityMapper,
                visitorEntityMapper
        ).getVisitRecords();

        assertThat(response.getRecords()).hasSize(1);
        assertThat(response.getRecords().get(0).getVisitorLabel()).isEqualTo("小陈");
        assertThat(response.getRecords().get(0).getVisitorAvatarUrl())
                .isEqualTo("https://cdn.example.com/visit/visitor-avatar-1024-20260705093000-a1b2c3d4.jpg");
        assertThat(response.getRecords().get(0).getVisitorInitial()).isEqualTo("8");
    }

    @Test
    void visitEventsShouldReturnPagedCurrentOwnerEventTimelineInReverseTimeOrder() {
        AuthContextHolder.set(new AuthContext(7L, "wf-dev-user-7"));
        LocalDateTime now = LocalDateTime.of(2026, 7, 5, 11, 1);
        VisitRecordEntity record = buildRecord(
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
                null,
                "NOT_FOLLOWED_UP",
                now
        );
        record.setOwnerType("USER");
        record.setOwnerId(7L);
        VisitEventEntity opened = buildEvent(9001L, 101L, "PORTFOLIO_OPENED", now.minusMinutes(3));
        VisitEventEntity workViewed = buildEvent(9002L, 101L, "WORK_VIEWED", now.minusMinutes(2));
        workViewed.setWorkId(301L);
        VisitEventEntity queried = buildEvent(9003L, 101L, "SCHEDULE_QUERIED", now.minusMinutes(1));
        queried.setQueriedDate(LocalDate.of(2026, 10, 3));

        when(visitRecordEntityMapper.selectOne(any())).thenReturn(record);
        Page<VisitEventEntity> resultPage = new Page<>(1, 2, 3);
        resultPage.setRecords(List.of(queried, workViewed));
        when(visitEventEntityMapper.selectPage(any(Page.class), any())).thenReturn(resultPage);
        lenient().when(visitEventEntityMapper.selectList(any())).thenReturn(List.of(opened, queried, workViewed));

        MineVisitRecordsResponse.EventTimeline response = new MineVisitService(
                visitRecordEntityMapper,
                visitEventEntityMapper,
                visitorEntityMapper
        ).getVisitEvents(101L, 1, 2);

        assertThat(response.getRecordId()).isEqualTo(101L);
        assertThat(response.getVisitorLabel()).isEqualTo("微信访客 8A21");
        assertThat(response.getPageNo()).isEqualTo(1);
        assertThat(response.getPageSize()).isEqualTo(2);
        assertThat(response.getHasMore()).isTrue();
        assertThat(response.getEvents()).extracting(MineVisitRecordsResponse.VisitEventItem::getTitle)
                .containsExactly("查询档期", "查看作品");
        assertThat(response.getEvents()).extracting(MineVisitRecordsResponse.VisitEventItem::getOccurredTimeText)
                .containsExactly("11:00", "10:59");
        assertThat(response.getEvents().get(0).getDetailText()).isEqualTo("查询 2026-10-03 档期");
        assertThat(response.getEvents().get(1).getDetailText()).isEqualTo("作品 ID 301");
        ArgumentCaptor<Page<VisitEventEntity>> pageCaptor = ArgumentCaptor.captor();
        verify(visitEventEntityMapper).selectPage(pageCaptor.capture(), any());
        assertThat(pageCaptor.getValue().getCurrent()).isEqualTo(1L);
        assertThat(pageCaptor.getValue().getSize()).isEqualTo(2L);
        verify(visitEventEntityMapper, never()).selectList(any());
    }

    @Test
    void markVisitFollowedShouldUpdateCurrentOwnerRecordToContacted() {
        AuthContextHolder.set(new AuthContext(7L, "wf-dev-user-7"));
        LocalDateTime now = LocalDateTime.of(2026, 7, 5, 11, 1);
        VisitRecordEntity record = buildRecord(
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
                null,
                "NOT_FOLLOWED_UP",
                now
        );
        record.setOwnerType("USER");
        record.setOwnerId(7L);
        when(visitRecordEntityMapper.selectOne(any())).thenReturn(record);
        when(visitRecordEntityMapper.updateById(record)).thenReturn(1);

        MineVisitRecordsResponse.Record response = new MineVisitService(
                visitRecordEntityMapper,
                visitEventEntityMapper,
                visitorEntityMapper
        ).markVisitFollowed(101L);

        assertThat(record.getFollowStatus()).isEqualTo("CONTACTED");
        assertThat(response.getId()).isEqualTo(101L);
        assertThat(response.getFollowStatus()).isEqualTo("CONTACTED");
        assertThat(response.getFollowStatusText()).isEqualTo("已跟进");
        assertThat(response.getFollowTone()).isEqualTo("teal");
        verify(visitRecordEntityMapper).updateById(record);
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

    private VisitEventEntity buildEvent(Long id, Long recordId, String eventType, LocalDateTime occurredAt) {
        VisitEventEntity event = new VisitEventEntity();
        event.setId(id);
        event.setVisitRecordId(recordId);
        event.setEventType(eventType);
        event.setOccurredAt(occurredAt);
        return event;
    }
}
