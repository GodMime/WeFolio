package com.jxc.wefolio.service;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.jxc.wefolio.common.auth.AuthContext;
import com.jxc.wefolio.common.auth.AuthContextHolder;
import com.jxc.wefolio.dto.MineVisitRecordsResponse;
import com.jxc.wefolio.entity.ContactLeadEntity;
import com.jxc.wefolio.entity.ScheduleQueryRecordEntity;
import com.jxc.wefolio.entity.VisitEventEntity;
import com.jxc.wefolio.entity.VisitRecordEntity;
import com.jxc.wefolio.entity.VisitorEntity;
import com.jxc.wefolio.mapper.ContactLeadEntityMapper;
import com.jxc.wefolio.mapper.ScheduleQueryRecordEntityMapper;
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
import java.time.LocalTime;
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

    @Mock
    private ScheduleQueryRecordEntityMapper scheduleQueryRecordEntityMapper;

    @Mock
    private ContactLeadEntityMapper contactLeadEntityMapper;

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
        when(scheduleQueryRecordEntityMapper.selectCount(any())).thenReturn(12L);
        when(contactLeadEntityMapper.selectCount(any())).thenReturn(5L);

        MineVisitRecordsResponse response = service().getVisitRecords();

        assertThat(response.getSummary().getTotalVisitCount()).isEqualTo(6L);
        assertThat(response.getSummary().getTodayVisitCount()).isEqualTo(3L);
        assertThat(response.getSummary().getScheduleQueryCount()).isEqualTo(12L);
        assertThat(response.getSummary().getContactLeadCount()).isEqualTo(5L);
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

        MineVisitRecordsResponse response = service().getVisitRecords();

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

        MineVisitRecordsResponse.EventTimeline response = service().getVisitEvents(101L, 1, 2);

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
    void visitEventsShouldAppendScheduleSlotMetadataAndKeepLegacyFallback() {
        AuthContextHolder.set(new AuthContext(7L, "wf-dev-user-7"));
        LocalDateTime now = LocalDateTime.of(2026, 7, 5, 16, 34);
        VisitRecordEntity record = buildRecord(
                101L,
                "anonymous-visitor-key-8A21",
                "WECHAT_SHARE_CARD",
                "PERSONAL",
                "林安婚礼司仪",
                4,
                6,
                0,
                2,
                1,
                null,
                "NOT_FOLLOWED_UP",
                now
        );
        record.setOwnerType("USER");
        record.setOwnerId(7L);
        VisitEventEntity queriedWithSlot = buildEvent(9004L, 101L, "SCHEDULE_QUERIED", now);
        queriedWithSlot.setQueriedDate(LocalDate.of(2026, 7, 30));
        queriedWithSlot.setMetadata("{\"slotName\":\"午宴\",\"startTime\":\"10:00\",\"endTime\":\"14:00\"}");
        VisitEventEntity legacyQueried = buildEvent(9005L, 101L, "SCHEDULE_QUERIED", now.minusMinutes(1));
        legacyQueried.setQueriedDate(LocalDate.of(2026, 7, 31));

        when(visitRecordEntityMapper.selectOne(any())).thenReturn(record);
        Page<VisitEventEntity> resultPage = new Page<>(1, 20, 2);
        resultPage.setRecords(List.of(queriedWithSlot, legacyQueried));
        when(visitEventEntityMapper.selectPage(any(Page.class), any())).thenReturn(resultPage);

        MineVisitRecordsResponse.EventTimeline response = service().getVisitEvents(101L, 1, 20);

        assertThat(response.getEvents()).extracting(MineVisitRecordsResponse.VisitEventItem::getDetailText)
                .containsExactly("查询 2026-07-30 午宴 10:00-14:00 档期", "查询 2026-07-31 档期");
    }

    @Test
    void visitEventsShouldPreferWorkTitleMetadataAndKeepLegacyIdFallback() {
        AuthContextHolder.set(new AuthContext(7L, "wf-dev-user-7"));
        LocalDateTime now = LocalDateTime.of(2026, 7, 5, 13, 2);
        VisitRecordEntity record = buildRecord(
                101L,
                "anonymous-visitor-key-8A21",
                "WECHAT_SHARE_CARD",
                "PERSONAL",
                "林安婚礼司仪",
                4,
                6,
                2,
                1,
                1,
                null,
                "NOT_FOLLOWED_UP",
                now
        );
        record.setOwnerType("USER");
        record.setOwnerId(7L);
        VisitEventEntity imageViewed = buildEvent(9004L, 101L, "WORK_VIEWED", now);
        imageViewed.setWorkId(301L);
        imageViewed.setMetadata("{\"workTitle\":\"迎宾图\"}");
        VisitEventEntity videoPlayed = buildEvent(9005L, 101L, "VIDEO_PLAYED", now.minusMinutes(1));
        videoPlayed.setWorkId(302L);
        videoPlayed.setMetadata("{\"workTitle\":\"婚礼快剪\"}");
        VisitEventEntity legacyWorkViewed = buildEvent(9006L, 101L, "WORK_VIEWED", now.minusMinutes(2));
        legacyWorkViewed.setWorkId(303L);

        when(visitRecordEntityMapper.selectOne(any())).thenReturn(record);
        Page<VisitEventEntity> resultPage = new Page<>(1, 20, 3);
        resultPage.setRecords(List.of(imageViewed, videoPlayed, legacyWorkViewed));
        when(visitEventEntityMapper.selectPage(any(Page.class), any())).thenReturn(resultPage);

        MineVisitRecordsResponse.EventTimeline response = service().getVisitEvents(101L, 1, 20);

        assertThat(response.getEvents()).extracting(MineVisitRecordsResponse.VisitEventItem::getDetailText)
                .containsExactly("查看图片：迎宾图", "查看视频：婚礼快剪", "作品 ID 303");
    }

    @Test
    void scheduleQueryRecordsShouldReturnPagedOwnerPersonalRecordsInReverseCreatedOrder() {
        AuthContextHolder.set(new AuthContext(7L, "wf-dev-user-7"));
        LocalDateTime now = LocalDateTime.of(2026, 7, 5, 14, 18);
        ScheduleQueryRecordEntity first = buildScheduleQueryRecord(301L, 1024L, "visitor-key-8A21", now);
        ScheduleQueryRecordEntity second = buildScheduleQueryRecord(302L, null, "visitor-key-C19F", now.minusMinutes(6));
        Page<ScheduleQueryRecordEntity> resultPage = new Page<>(1, 2, 3);
        resultPage.setRecords(List.of(first, second));
        VisitorEntity visitor = new VisitorEntity();
        visitor.setId(1024L);
        visitor.setNickname("小陈");
        visitor.setAvatarUrl("https://cdn.example.com/avatar.jpg");
        when(scheduleQueryRecordEntityMapper.selectPage(any(Page.class), any())).thenReturn(resultPage);
        when(visitorEntityMapper.selectBatchIds(List.of(1024L))).thenReturn(List.of(visitor));

        MineVisitRecordsResponse.ScheduleQueryPage response = service().getScheduleQueryRecords(1, 2);

        assertThat(response.getPageNo()).isEqualTo(1);
        assertThat(response.getPageSize()).isEqualTo(2);
        assertThat(response.getHasMore()).isTrue();
        assertThat(response.getItems()).hasSize(2);
        assertThat(response.getItems().get(0).getId()).isEqualTo(301L);
        assertThat(response.getItems().get(0).getVisitorLabel()).isEqualTo("小陈");
        assertThat(response.getItems().get(0).getVisitorAvatarUrl()).isEqualTo("https://cdn.example.com/avatar.jpg");
        assertThat(response.getItems().get(0).getPortfolioTitle()).isEqualTo("林安婚礼司仪");
        assertThat(response.getItems().get(0).getQueriedDateText()).isEqualTo("2026-07-18");
        assertThat(response.getItems().get(0).getSlotText()).isEqualTo("午宴 10:00-14:00");
        assertThat(response.getItems().get(0).getResultStatusText()).isEqualTo("已约");
        assertThat(response.getItems().get(0).getAvailable()).isFalse();
        assertThat(response.getItems().get(0).getCreatedTimeText()).isEqualTo("07-05 14:18");
        assertThat(response.getItems().get(1).getVisitorLabel()).isEqualTo("微信访客 C19F");
        ArgumentCaptor<Page<ScheduleQueryRecordEntity>> pageCaptor = ArgumentCaptor.captor();
        verify(scheduleQueryRecordEntityMapper).selectPage(pageCaptor.capture(), any());
        assertThat(pageCaptor.getValue().getCurrent()).isEqualTo(1L);
        assertThat(pageCaptor.getValue().getSize()).isEqualTo(2L);
    }

    @Test
    void contactLeadsShouldReturnPagedOwnerRecordsWithoutCiphertextFields() {
        AuthContextHolder.set(new AuthContext(7L, "wf-dev-user-7"));
        ContactLeadEntity lead = new ContactLeadEntity();
        lead.setId(401L);
        lead.setContactName("王小姐");
        lead.setPhoneCiphertext("13800108899");
        lead.setPhoneLast4("8899");
        lead.setWechatCiphertext("wx-full-99");
        lead.setWechatMaskHint("wx***99");
        lead.setDesiredSchedule("2026-10-03 午宴");
        lead.setNeeds("想了解主持和摄影套餐");
        lead.setPortfolioTitleSnapshot("林安婚礼司仪");
        lead.setSourceType("WECHAT_SHARE_CARD");
        lead.setFollowStatus("NOT_FOLLOWED_UP");
        lead.setSubmittedAt(LocalDateTime.of(2026, 7, 5, 13, 30));
        Page<ContactLeadEntity> resultPage = new Page<>(1, 2, 1);
        resultPage.setRecords(List.of(lead));
        when(contactLeadEntityMapper.selectPage(any(Page.class), any())).thenReturn(resultPage);

        MineVisitRecordsResponse.ContactLeadPage response = service().getContactLeads(1, 2);

        assertThat(response.getPageNo()).isEqualTo(1);
        assertThat(response.getPageSize()).isEqualTo(2);
        assertThat(response.getHasMore()).isFalse();
        assertThat(response.getItems()).hasSize(1);
        MineVisitRecordsResponse.ContactLeadItem item = response.getItems().get(0);
        assertThat(item.getId()).isEqualTo(401L);
        assertThat(item.getContactName()).isEqualTo("王小姐");
        assertThat(item.getPhone()).isEqualTo("13800108899");
        assertThat(item.getPhoneLast4()).isEqualTo("8899");
        assertThat(item.getWechat()).isEqualTo("wx-full-99");
        assertThat(item.getWechatMaskHint()).isEqualTo("wx***99");
        assertThat(item.getDesiredSchedule()).isEqualTo("2026-10-03 午宴");
        assertThat(item.getNeeds()).isEqualTo("想了解主持和摄影套餐");
        assertThat(item.getPortfolioTitle()).isEqualTo("林安婚礼司仪");
        assertThat(item.getSourceText()).isEqualTo("来自分享卡片");
        assertThat(item.getFollowStatusText()).isEqualTo("未跟进");
        assertThat(item.getSubmittedTimeText()).isEqualTo("07-05 13:30");
        assertThat(MineVisitRecordsResponse.ContactLeadItem.class.getDeclaredFields())
                .extracting(java.lang.reflect.Field::getName)
                .contains("phone", "wechat")
                .doesNotContain("phoneCiphertext", "wechatCiphertext");
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

        MineVisitRecordsResponse.Record response = service().markVisitFollowed(101L);

        assertThat(record.getFollowStatus()).isEqualTo("CONTACTED");
        assertThat(response.getId()).isEqualTo(101L);
        assertThat(response.getFollowStatus()).isEqualTo("CONTACTED");
        assertThat(response.getFollowStatusText()).isEqualTo("已跟进");
        assertThat(response.getFollowTone()).isEqualTo("teal");
        verify(visitRecordEntityMapper).updateById(record);
    }

    @Test
    void markContactLeadFollowedShouldUpdateCurrentOwnerLeadToContacted() {
        AuthContextHolder.set(new AuthContext(7L, "wf-dev-user-7"));
        ContactLeadEntity lead = new ContactLeadEntity();
        lead.setId(401L);
        lead.setOwnerType("USER");
        lead.setOwnerId(7L);
        lead.setContactName("王小姐");
        lead.setPhoneCiphertext("13800108899");
        lead.setPhoneLast4("8899");
        lead.setWechatCiphertext("wx-full-99");
        lead.setWechatMaskHint("wx***99");
        lead.setDesiredSchedule("2026-10-03 午宴");
        lead.setNeeds("想了解主持和摄影套餐");
        lead.setPortfolioTitleSnapshot("林安婚礼司仪");
        lead.setSourceType("WECHAT_SHARE_CARD");
        lead.setFollowStatus("NOT_FOLLOWED_UP");
        lead.setSubmittedAt(LocalDateTime.of(2026, 7, 5, 13, 30));
        when(contactLeadEntityMapper.selectOne(any())).thenReturn(lead);
        when(contactLeadEntityMapper.updateById(lead)).thenReturn(1);

        MineVisitRecordsResponse.ContactLeadItem response = service().markContactLeadFollowed(401L);

        assertThat(lead.getFollowStatus()).isEqualTo("CONTACTED");
        assertThat(response.getId()).isEqualTo(401L);
        assertThat(response.getFollowStatus()).isEqualTo("CONTACTED");
        assertThat(response.getFollowStatusText()).isEqualTo("已跟进");
        verify(contactLeadEntityMapper).updateById(lead);
    }

    private MineVisitService service() {
        return new MineVisitService(
                visitRecordEntityMapper,
                visitEventEntityMapper,
                visitorEntityMapper,
                scheduleQueryRecordEntityMapper,
                contactLeadEntityMapper
        );
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

    private ScheduleQueryRecordEntity buildScheduleQueryRecord(
            Long id,
            Long visitorId,
            String visitorKey,
            LocalDateTime queriedAt
    ) {
        ScheduleQueryRecordEntity record = new ScheduleQueryRecordEntity();
        record.setId(id);
        record.setVisitorId(visitorId);
        record.setVisitorKey(visitorKey);
        record.setPortfolioTitleSnapshot("林安婚礼司仪");
        record.setSourceType("WECHAT_SHARE_CARD");
        record.setQueriedDate(LocalDate.of(2026, 7, 18));
        record.setSlotNameSnapshot("午宴");
        record.setStartTimeSnapshot(LocalTime.of(10, 0));
        record.setEndTimeSnapshot(LocalTime.of(14, 0));
        record.setResultStatus("BOOKED");
        record.setResultStatusText("已约");
        record.setAvailable(0);
        record.setResultMessage("该档期已约");
        record.setQueriedAt(queriedAt);
        return record;
    }
}
