package com.jxc.wefolio.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.jxc.wefolio.common.auth.AuthContext;
import com.jxc.wefolio.common.auth.AuthContextHolder;
import com.jxc.wefolio.dto.MineScheduleQueryRecordRow;
import com.jxc.wefolio.dto.MineVisitRecordPageResponse;
import com.jxc.wefolio.dto.MineVisitRecordsResponse;
import com.jxc.wefolio.dto.MineVisitStatisticsResponse;
import com.jxc.wefolio.entity.ContactLeadEntity;
import com.jxc.wefolio.entity.VisitEventEntity;
import com.jxc.wefolio.entity.VisitRecordEntity;
import com.jxc.wefolio.entity.VisitorEntity;
import com.jxc.wefolio.exception.BusinessException;
import com.jxc.wefolio.mapper.ContactLeadEntityMapper;
import com.jxc.wefolio.mapper.ScheduleQueryRecordEntityMapper;
import com.jxc.wefolio.mapper.VisitEventEntityMapper;
import com.jxc.wefolio.mapper.VisitRecordEntityMapper;
import com.jxc.wefolio.mapper.VisitorEntityMapper;
import com.jxc.wefolio.service.teamportfolio.component.contactform.TeamContactLeadCryptoService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.transaction.annotation.Transactional;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.Collection;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.tuple;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * 访问记录服务测试 — 覆盖维护者视角统计、趋势和明细摘要。
 */
@ExtendWith(MockitoExtension.class)
class MineVisitServiceTest {

    @BeforeAll
    static void initializeTableMetadata() {
        MybatisConfiguration configuration = new MybatisConfiguration();
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(configuration, "mine-visit-contact-lead"),
                ContactLeadEntity.class);
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(configuration, "mine-visit-record"),
                VisitRecordEntity.class);
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(configuration, "mine-visit-event"),
                VisitEventEntity.class);
    }

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

    @Mock
    private MineVisitScopeService mineVisitScopeService;

    @Mock
    private TeamContactLeadCryptoService teamContactLeadCryptoService;

    @BeforeEach
    void setUpScope() {
        lenient().when(mineVisitScopeService.resolveReadScope(7L))
                .thenReturn(MineVisitScopeService.Scope.complete(Map.of()));
        lenient().when(mineVisitScopeService.requireScope(7L))
                .thenReturn(MineVisitScopeService.Scope.complete(Map.of()));
    }

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
        firstRecord.setPortfolioTitleSnapshot(" 个人作品集 A ");
        firstRecord.setPortfolioType("PERSONAL");
        firstRecord.setOwnerType("USER");
        firstRecord.setOwnerId(7L);
        secondRecord.setPortfolioTitleSnapshot("团队作品集 B");
        secondRecord.setPortfolioType("TEAM");
        secondRecord.setOwnerType("TEAM");
        secondRecord.setOwnerId(202L);
        List<VisitEventEntity> trendEvents = List.of(
                buildOpenedEvent(now),
                buildOpenedEvent(now.minusMinutes(8)),
                buildOpenedEvent(now.minusMinutes(16)),
                buildOpenedEvent(now.minusDays(1)),
                buildOpenedEvent(now.minusDays(6))
        );

        when(visitRecordEntityMapper.selectList(any())).thenReturn(List.of(firstRecord, secondRecord));
        when(visitEventEntityMapper.selectList(any())).thenReturn(trendEvents);
        when(scheduleQueryRecordEntityMapper.countVisibleRecords(any(), any())).thenReturn(12L);
        when(contactLeadEntityMapper.selectCount(any())).thenReturn(5L);
        MineVisitScopeService.Scope scope = MineVisitScopeService.Scope.complete(Map.of(
                201L, "MANAGER",
                202L, "MEMBER"
        ));
        when(mineVisitScopeService.resolveReadScope(7L)).thenReturn(scope);

        MineVisitRecordsResponse response = service().getVisitRecords();

        assertThat(response.getScopeComplete()).isTrue();
        assertThat(response.getScopeReason()).isEmpty();
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
        assertThat(response.getRecords())
                .extracting("portfolioTitle", "portfolioType", "portfolioTypeText", "canMarkFollowed")
                .containsExactly(
                        tuple("个人作品集 A", "PERSONAL", "个人作品集", true),
                        tuple("团队作品集 B", "TEAM", "团队作品集", false));

        ArgumentCaptor<LambdaQueryWrapper<VisitRecordEntity>> recordWrapperCaptor = ArgumentCaptor.captor();
        verify(visitRecordEntityMapper).selectList(recordWrapperCaptor.capture());
        assertThat(recordWrapperCaptor.getValue().getSqlSegment())
                .contains("owner_type", "owner_id", "IN", "ORDER BY last_visited_at DESC,id DESC");
        assertThat(recordWrapperCaptor.getValue().getParamNameValuePairs().values())
                .contains("USER", 7L, "TEAM", 201L, 202L);

        ArgumentCaptor<LambdaQueryWrapper<VisitEventEntity>> eventWrapperCaptor = ArgumentCaptor.captor();
        verify(visitEventEntityMapper).selectList(eventWrapperCaptor.capture());
        assertThat(eventWrapperCaptor.getValue().getSqlSegment())
                .contains("owner_type", "owner_id", "IN", "event_type", "occurred_at");
        assertThat(eventWrapperCaptor.getValue().getParamNameValuePairs().values())
                .contains("USER", 7L, "TEAM", 201L, 202L, "PORTFOLIO_OPENED");

        ArgumentCaptor<Collection<Long>> scheduleTeamIdsCaptor = ArgumentCaptor.captor();
        verify(scheduleQueryRecordEntityMapper).countVisibleRecords(
                org.mockito.ArgumentMatchers.eq(7L), scheduleTeamIdsCaptor.capture());
        assertThat(scheduleTeamIdsCaptor.getValue()).containsExactly(201L, 202L);

        ArgumentCaptor<LambdaQueryWrapper<ContactLeadEntity>> leadWrapperCaptor = ArgumentCaptor.captor();
        verify(contactLeadEntityMapper).selectCount(leadWrapperCaptor.capture());
        leadWrapperCaptor.getValue().getSqlSegment();
        assertThat(leadWrapperCaptor.getValue().getParamNameValuePairs().values())
                .contains("USER", 7L, "TEAM", 201L, 202L);
        verify(mineVisitScopeService).resolveReadScope(7L);
    }

    @Test
    void visitStatisticsAggregateSummaryAndTrendWithoutVisitRecords() {
        AuthContextHolder.set(new AuthContext(7L, "wf-dev-user-7"));
        LocalDate today = LocalDate.now();
        LocalDateTime now = today.atTime(12, 0);
        VisitRecordEntity record = buildRecord(
                101L,
                "anonymous-visitor-key-8A21",
                "WECHAT_SHARE_CARD",
                "PERSONAL",
                "林安婚礼司仪",
                3,
                0,
                0,
                0,
                0,
                null,
                "NOT_FOLLOWED_UP",
                now
        );
        when(visitRecordEntityMapper.selectList(any())).thenReturn(List.of(record));
        when(visitEventEntityMapper.selectList(any())).thenReturn(List.of(
                buildOpenedEvent(now),
                buildOpenedEvent(now.minusDays(1))
        ));
        when(scheduleQueryRecordEntityMapper.countVisibleRecords(any(), any())).thenReturn(4L);
        when(contactLeadEntityMapper.selectCount(any())).thenReturn(2L);
        MineVisitStatisticsResponse response = service().getVisitStatistics();

        assertThat(response.getScopeComplete()).isTrue();
        assertThat(response.getScopeReason()).isEmpty();
        assertThat(response.getSummary().getTotalVisitCount()).isEqualTo(3L);
        assertThat(response.getSummary().getTodayVisitCount()).isEqualTo(1L);
        assertThat(response.getSummary().getScheduleQueryCount()).isEqualTo(4L);
        assertThat(response.getSummary().getContactLeadCount()).isEqualTo(2L);
        assertThat(response.getTrend().getPoints()).hasSize(7);
        assertThat(response.getTrend().getPoints().get(6).getValue()).isEqualTo(1L);
        verify(visitorEntityMapper, never()).selectBatchIds(any());
    }

    @Test
    void visitRecordPageReturnsStableReverseTimePageAndHasMore() {
        AuthContextHolder.set(new AuthContext(7L, "wf-dev-user-7"));
        LocalDateTime now = LocalDateTime.of(2026, 7, 20, 17, 30);
        VisitRecordEntity firstRecord = buildRecord(
                102L, "visitor-C19F", "WECHAT_SHARE_CARD", "PERSONAL", "作品集 B",
                2, 0, 0, 0, 0, null, "CONTACTED", now
        );
        VisitRecordEntity secondRecord = buildRecord(
                101L, "visitor-8A21", "WECHAT_SHARE_CARD", "PERSONAL", "作品集 A",
                1, 0, 0, 0, 0, null, "NOT_FOLLOWED_UP", now
        );
        secondRecord.setOwnerType("TEAM");
        secondRecord.setOwnerId(201L);
        secondRecord.setPortfolioType("TEAM");
        secondRecord.setPortfolioTitleSnapshot("   ");
        Page<VisitRecordEntity> resultPage = new Page<>(1, 2, 3);
        resultPage.setRecords(List.of(firstRecord, secondRecord));
        when(visitRecordEntityMapper.selectPage(any(Page.class), any())).thenReturn(resultPage);
        when(mineVisitScopeService.resolveReadScope(7L)).thenReturn(
                MineVisitScopeService.Scope.complete(Map.of(201L, "OWNER")));

        MineVisitRecordPageResponse response = service().getVisitRecordPage(1, 2);

        assertThat(response.getPageNo()).isEqualTo(1);
        assertThat(response.getPageSize()).isEqualTo(2);
        assertThat(response.getHasMore()).isTrue();
        assertThat(response.getRecords()).extracting(MineVisitRecordsResponse.Record::getId)
                .containsExactly(102L, 101L);
        assertThat(response.getRecords()).extracting(MineVisitRecordsResponse.Record::getPortfolioTitle)
                .containsExactly("作品集 B", "");
        assertThat(response.getScopeComplete()).isTrue();
        assertThat(response.getScopeReason()).isEmpty();
        ArgumentCaptor<LambdaQueryWrapper<VisitRecordEntity>> wrapperCaptor = ArgumentCaptor.captor();
        verify(visitRecordEntityMapper).selectPage(any(Page.class), wrapperCaptor.capture());
        assertThat(wrapperCaptor.getValue().getSqlSegment())
                .contains("owner_type", "owner_id", "IN", "ORDER BY last_visited_at DESC,id DESC");
        assertThat(wrapperCaptor.getValue().getParamNameValuePairs().values())
                .contains("USER", 7L, "TEAM", 201L);
    }

    @Test
    void recentOpenedEventsShouldKeepOwnerScopeGroupedBeforeEventAndTimeConditions() {
        AuthContextHolder.set(new AuthContext(7L, "wf-dev-user-7"));
        when(mineVisitScopeService.resolveReadScope(7L)).thenReturn(
                MineVisitScopeService.Scope.complete(Map.of(201L, "OWNER", 202L, "MEMBER")));

        service().getVisitStatistics();

        ArgumentCaptor<LambdaQueryWrapper<VisitEventEntity>> eventWrapperCaptor = ArgumentCaptor.captor();
        verify(visitEventEntityMapper).selectList(eventWrapperCaptor.capture());
        String sql = eventWrapperCaptor.getValue().getSqlSegment().replaceAll("\\s+", " ");
        assertThat(sql)
                .startsWith("((owner_type =")
                .contains(" OR (owner_type =", "))) AND event_type =")
                .contains(" AND occurred_at >=", " AND occurred_at <");
    }

    @Test
    void degradedReadScopeShouldUseOnlyPersonalConditionsAndExposeMetadata() {
        AuthContextHolder.set(new AuthContext(7L, "wf-dev-user-7"));
        when(mineVisitScopeService.resolveReadScope(7L)).thenReturn(MineVisitScopeService.Scope.degraded());
        when(visitRecordEntityMapper.selectPage(any(Page.class), any())).thenAnswer(invocation -> {
            Page<VisitRecordEntity> requestedPage = invocation.getArgument(0);
            requestedPage.setRecords(List.of());
            requestedPage.setTotal(0);
            return requestedPage;
        });

        MineVisitStatisticsResponse statistics = service().getVisitStatistics();
        MineVisitRecordPageResponse page = service().getVisitRecordPage(1, 20);

        assertThat(statistics.getScopeComplete()).isFalse();
        assertThat(statistics.getScopeReason()).isEqualTo("TEAM_SCOPE_UNAVAILABLE");
        assertThat(page.getScopeComplete()).isFalse();
        assertThat(page.getScopeReason()).isEqualTo("TEAM_SCOPE_UNAVAILABLE");

        ArgumentCaptor<LambdaQueryWrapper<VisitRecordEntity>> recordListWrapper = ArgumentCaptor.captor();
        verify(visitRecordEntityMapper).selectList(recordListWrapper.capture());
        recordListWrapper.getValue().getSqlSegment();
        assertThat(recordListWrapper.getValue().getParamNameValuePairs().values())
                .contains("USER", 7L)
                .doesNotContain("TEAM");

        ArgumentCaptor<LambdaQueryWrapper<VisitEventEntity>> eventWrapper = ArgumentCaptor.captor();
        verify(visitEventEntityMapper).selectList(eventWrapper.capture());
        eventWrapper.getValue().getSqlSegment();
        assertThat(eventWrapper.getValue().getParamNameValuePairs().values())
                .contains("USER", 7L)
                .doesNotContain("TEAM");

        ArgumentCaptor<LambdaQueryWrapper<VisitRecordEntity>> pageWrapper = ArgumentCaptor.captor();
        verify(visitRecordEntityMapper).selectPage(any(Page.class), pageWrapper.capture());
        pageWrapper.getValue().getSqlSegment();
        assertThat(pageWrapper.getValue().getParamNameValuePairs().values())
                .contains("USER", 7L)
                .doesNotContain("TEAM");
        verify(mineVisitScopeService, org.mockito.Mockito.times(2)).resolveReadScope(7L);
    }

    @Test
    void visitRecordPageNormalizesInvalidAndOversizedParameters() {
        AuthContextHolder.set(new AuthContext(7L, "wf-dev-user-7"));
        when(visitRecordEntityMapper.selectPage(any(Page.class), any())).thenAnswer(invocation -> {
            Page<VisitRecordEntity> requestedPage = invocation.getArgument(0);
            requestedPage.setRecords(List.of());
            requestedPage.setTotal(0);
            return requestedPage;
        });

        MineVisitRecordPageResponse defaultPage = service().getVisitRecordPage(null, 0);
        MineVisitRecordPageResponse cappedPage = service().getVisitRecordPage(0, 100);

        assertThat(defaultPage.getPageNo()).isEqualTo(1);
        assertThat(defaultPage.getPageSize()).isEqualTo(20);
        assertThat(defaultPage.getHasMore()).isFalse();
        assertThat(cappedPage.getPageNo()).isEqualTo(1);
        assertThat(cappedPage.getPageSize()).isEqualTo(50);
        assertThat(cappedPage.getHasMore()).isFalse();
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
        assertThat(response.getPortfolioTitle()).isEqualTo("林安婚礼司仪");
        assertThat(response.getPortfolioType()).isEqualTo("PERSONAL");
        assertThat(response.getPortfolioTypeText()).isEqualTo("个人作品集");
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
        verify(mineVisitScopeService, never()).requireScope(7L);
        assertThat(MineVisitRecordsResponse.EventTimeline.class.getDeclaredFields())
                .extracting(Field::getName)
                .doesNotContain("scopeComplete", "scopeReason");
    }

    /**
     * 团队事件读取 — 已加入团队的普通成员也可以读取事件，并返回团队作品集快照信息。
     */
    @Test
    void visitEventsShouldAllowJoinedTeamMemberAndReturnPortfolioSnapshot() {
        AuthContextHolder.set(new AuthContext(7L, "wf-dev-user-7"));
        LocalDateTime now = LocalDateTime.of(2026, 7, 5, 11, 1);
        VisitRecordEntity record = buildRecord(
                101L,
                "anonymous-visitor-key-8A21",
                "TEAM_PORTFOLIO",
                "TEAM",
                "星曜司仪团",
                4,
                6,
                0,
                1,
                1,
                null,
                "NOT_FOLLOWED_UP",
                now
        );
        record.setOwnerType("TEAM");
        record.setOwnerId(201L);
        VisitEventEntity opened = buildEvent(9001L, 101L, "PORTFOLIO_OPENED", now);
        opened.setOwnerType("TEAM");
        opened.setOwnerId(201L);
        when(visitRecordEntityMapper.selectOne(any())).thenReturn(null, record);
        when(mineVisitScopeService.requireScope(7L)).thenReturn(
                MineVisitScopeService.Scope.complete(Map.of(201L, "MEMBER", 202L, "MANAGER")));
        Page<VisitEventEntity> resultPage = new Page<>(1, 20, 1);
        resultPage.setRecords(List.of(opened));
        when(visitEventEntityMapper.selectPage(any(Page.class), any())).thenReturn(resultPage);

        MineVisitRecordsResponse.EventTimeline timeline = service().getVisitEvents(101L, 1, 20);

        assertThat(timeline.getPortfolioTitle()).isEqualTo("星曜司仪团");
        assertThat(timeline.getPortfolioType()).isEqualTo("TEAM");
        assertThat(timeline.getPortfolioTypeText()).isEqualTo("团队作品集");
        assertThat(timeline.getEvents()).hasSize(1);
        verify(mineVisitScopeService).requireScope(7L);

        ArgumentCaptor<LambdaQueryWrapper<VisitRecordEntity>> recordQueryCaptor = ArgumentCaptor.captor();
        verify(visitRecordEntityMapper, times(2)).selectOne(recordQueryCaptor.capture());
        LambdaQueryWrapper<VisitRecordEntity> teamRecordQuery = recordQueryCaptor.getAllValues().get(1);
        String teamRecordSql = teamRecordQuery.getSqlSegment().replaceAll("\\s+", " ");
        assertThat(teamRecordSql).contains(
                "id = #{ew.paramNameValuePairs.MPGENVAL1}",
                "owner_type = #{ew.paramNameValuePairs.MPGENVAL2}",
                "owner_id IN (#{ew.paramNameValuePairs.MPGENVAL3},#{ew.paramNameValuePairs.MPGENVAL4})");
        assertThat(teamRecordQuery.getParamNameValuePairs())
                .containsEntry("MPGENVAL1", 101L)
                .containsEntry("MPGENVAL2", "TEAM")
                .containsEntry("MPGENVAL3", 201L)
                .containsEntry("MPGENVAL4", 202L);

        ArgumentCaptor<LambdaQueryWrapper<VisitEventEntity>> eventQueryCaptor = ArgumentCaptor.captor();
        verify(visitEventEntityMapper).selectPage(any(Page.class), eventQueryCaptor.capture());
        LambdaQueryWrapper<VisitEventEntity> eventQuery = eventQueryCaptor.getValue();
        String eventSql = eventQuery.getSqlSegment().replaceAll("\\s+", " ");
        assertThat(eventSql).contains(
                "visit_record_id = #{ew.paramNameValuePairs.MPGENVAL1}",
                "owner_type = #{ew.paramNameValuePairs.MPGENVAL2}",
                "owner_id = #{ew.paramNameValuePairs.MPGENVAL3}",
                "ORDER BY occurred_at DESC,id DESC");
        assertThat(eventQuery.getParamNameValuePairs())
                .containsEntry("MPGENVAL1", 101L)
                .containsEntry("MPGENVAL2", "TEAM")
                .containsEntry("MPGENVAL3", 201L);
    }

    /**
     * 团队事件读取 — 非团队成员不能读取团队事件。
     */
    @Test
    void visitEventsShouldRejectNonMemberTeamRecord() {
        AuthContextHolder.set(new AuthContext(7L, "wf-dev-user-7"));
        when(visitRecordEntityMapper.selectOne(any())).thenReturn(null);
        when(mineVisitScopeService.requireScope(7L)).thenReturn(
                MineVisitScopeService.Scope.complete(Map.of()));

        assertThatThrownBy(() -> service().getVisitEvents(101L, 1, 20))
                .hasMessage("访问记录不存在或无访问权限");

        verify(mineVisitScopeService).requireScope(7L);
        verifyNoInteractions(visitEventEntityMapper);
    }

    /**
     * 团队事件读取 — 个人记录不存在且团队范围查询失败时整体拒绝，不执行事件查询。
     */
    @Test
    void visitEventsShouldFailClosedWhenTeamScopeCannotBeResolved() {
        AuthContextHolder.set(new AuthContext(7L, "wf-dev-user-7"));
        DataAccessResourceFailureException scopeFailure =
                new DataAccessResourceFailureException("team scope unavailable");
        when(visitRecordEntityMapper.selectOne(any())).thenReturn(null);
        when(mineVisitScopeService.requireScope(7L)).thenThrow(scopeFailure);

        assertThatThrownBy(() -> service().getVisitEvents(101L, 1, 20)).isSameAs(scopeFailure);

        verifyNoInteractions(visitEventEntityMapper);
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
        MineScheduleQueryRecordRow first = buildScheduleQueryRow(
                301L, "PERSONAL", "林安婚礼司仪", 1024L, "visitor-key-8A21", now);
        MineScheduleQueryRecordRow second = buildScheduleQueryRow(
                302L, "PERSONAL", "林安婚礼司仪", null, "visitor-key-C19F", now.minusMinutes(6));
        MineScheduleQueryRecordRow extra = buildScheduleQueryRow(
                303L, "PERSONAL", "林安婚礼司仪", null, "visitor-key-D20A", now.minusMinutes(12));
        VisitorEntity visitor = new VisitorEntity();
        visitor.setId(1024L);
        visitor.setNickname("小陈");
        visitor.setAvatarUrl("https://cdn.example.com/avatar.jpg");
        when(scheduleQueryRecordEntityMapper.selectVisibleRecords(any(), any(), anyLong(), anyLong(), anyInt()))
                .thenReturn(List.of(first, second, extra));
        when(visitorEntityMapper.selectBatchIds(List.of(1024L))).thenReturn(List.of(visitor));

        MineVisitRecordsResponse.ScheduleQueryPage response = service().getScheduleQueryRecords(1, 2);

        assertThat(response.getScopeComplete()).isTrue();
        assertThat(response.getScopeReason()).isEmpty();
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
        ArgumentCaptor<Collection<Long>> teamIdsCaptor = ArgumentCaptor.captor();
        verify(scheduleQueryRecordEntityMapper).selectVisibleRecords(
                org.mockito.ArgumentMatchers.eq(7L), teamIdsCaptor.capture(),
                org.mockito.ArgumentMatchers.eq(3L), org.mockito.ArgumentMatchers.eq(0L),
                org.mockito.ArgumentMatchers.eq(3));
        assertThat(teamIdsCaptor.getValue()).isEmpty();
    }

    @Test
    void scheduleQueryRecordsShouldMergeAllJoinedTeamsAndUseCompositeKeys() {
        AuthContextHolder.set(new AuthContext(7L, "wf-dev-user-7"));
        LocalDateTime now = LocalDateTime.of(2026, 7, 5, 15, 0);
        MineScheduleQueryRecordRow teamOwnerRow = buildScheduleQueryRow(
                301L, "TEAM", "星曜司仪团", 1024L, "visitor-key-8A21", now);
        teamOwnerRow.setAvailableMemberCount(2);
        teamOwnerRow.setPartialAvailableMemberCount(1);
        teamOwnerRow.setFullMemberCount(3);
        MineScheduleQueryRecordRow personalRow = buildScheduleQueryRow(
                301L, "PERSONAL", "林安婚礼司仪", null, "visitor-key-C19F", now.minusMinutes(1));
        MineScheduleQueryRecordRow teamManagerRow = buildScheduleQueryRow(
                302L, "TEAM", "远山摄影团队", null, "visitor-key-D20A", now.minusMinutes(2));
        teamManagerRow.setAvailableMemberCount(1);
        teamManagerRow.setPartialAvailableMemberCount(0);
        teamManagerRow.setFullMemberCount(2);
        MineScheduleQueryRecordRow extraRow = buildScheduleQueryRow(
                303L, "PERSONAL", "备用个人作品集", null, "visitor-key-E31B", now.minusMinutes(3));

        when(mineVisitScopeService.resolveReadScope(7L)).thenReturn(
                MineVisitScopeService.Scope.complete(Map.of(
                        201L, "OWNER",
                        202L, "MANAGER",
                        203L, "MEMBER"
                )));
        when(scheduleQueryRecordEntityMapper.selectVisibleRecords(
                        any(), any(), anyLong(), anyLong(), anyInt()))
                .thenReturn(List.of(teamOwnerRow, personalRow, teamManagerRow, extraRow));
        VisitorEntity visitor = new VisitorEntity();
        visitor.setId(1024L);
        visitor.setNickname("小陈");
        visitor.setAvatarUrl("https://cdn.example.com/avatar.jpg");
        when(visitorEntityMapper.selectBatchIds(List.of(1024L))).thenReturn(List.of(visitor));

        MineVisitRecordsResponse.ScheduleQueryPage response = service().getScheduleQueryRecords(1, 3);

        assertThat(response.getScopeComplete()).isTrue();
        assertThat(response.getScopeReason()).isEmpty();
        assertThat(response.getHasMore()).isTrue();
        assertThat(response.getItems()).hasSize(3);
        assertThat(response.getItems())
                .extracting(MineVisitRecordsResponse.ScheduleQueryItem::getPortfolioTitle)
                .containsExactly("星曜司仪团", "林安婚礼司仪", "远山摄影团队");
        assertThat(response.getItems())
                .extracting("id", "recordType", "sourceRecordId", "scheduleQueryKey")
                .containsExactly(
                        tuple(301L, "TEAM", 301L, "TEAM:301"),
                        tuple(301L, "PERSONAL", 301L, "PERSONAL:301"),
                        tuple(302L, "TEAM", 302L, "TEAM:302"));
        assertThat(response.getItems().getFirst().getVisitorLabel()).isEqualTo("小陈");
        assertThat(response.getItems().getFirst().getSlotText())
                .isEqualTo("空闲 2 人 · 部分空闲 1 人 · 已满 3 人");
        assertThat(response.getItems().get(1).getSlotText()).isEqualTo("午宴 10:00-14:00");

        ArgumentCaptor<Collection<Long>> teamIdsCaptor = ArgumentCaptor.captor();
        verify(scheduleQueryRecordEntityMapper).selectVisibleRecords(
                org.mockito.ArgumentMatchers.eq(7L), teamIdsCaptor.capture(),
                org.mockito.ArgumentMatchers.eq(4L), org.mockito.ArgumentMatchers.eq(0L),
                org.mockito.ArgumentMatchers.eq(4));
        assertThat(teamIdsCaptor.getValue()).containsExactly(201L, 202L, 203L);
    }

    @Test
    void visitStatisticsShouldCountPersonalAndAllJoinedTeamScheduleQueries() {
        AuthContextHolder.set(new AuthContext(7L, "wf-dev-user-7"));
        when(mineVisitScopeService.resolveReadScope(7L)).thenReturn(
                MineVisitScopeService.Scope.complete(Map.of(
                        201L, "OWNER",
                        202L, "MANAGER",
                        203L, "MEMBER"
                )));
        when(scheduleQueryRecordEntityMapper.countVisibleRecords(any(), any())).thenReturn(7L);

        MineVisitStatisticsResponse response = service().getVisitStatistics();

        assertThat(response.getScopeComplete()).isTrue();
        assertThat(response.getScopeReason()).isEmpty();
        assertThat(response.getSummary().getScheduleQueryCount()).isEqualTo(7L);
        ArgumentCaptor<Collection<Long>> teamIdsCaptor = ArgumentCaptor.captor();
        verify(scheduleQueryRecordEntityMapper).countVisibleRecords(
                org.mockito.ArgumentMatchers.eq(7L), teamIdsCaptor.capture());
        assertThat(teamIdsCaptor.getValue()).containsExactly(201L, 202L, 203L);
    }

    @Test
    void scheduleQueryRecordsShouldExposeDegradedScopeAndQueryOnlyPersonalRecords() {
        AuthContextHolder.set(new AuthContext(7L, "wf-dev-user-7"));
        when(mineVisitScopeService.resolveReadScope(7L)).thenReturn(MineVisitScopeService.Scope.degraded());
        when(scheduleQueryRecordEntityMapper.selectVisibleRecords(any(), any(), anyLong(), anyLong(), anyInt()))
                .thenReturn(List.of());

        MineVisitRecordsResponse.ScheduleQueryPage response = service().getScheduleQueryRecords(1, 20);

        assertThat(response.getScopeComplete()).isFalse();
        assertThat(response.getScopeReason()).isEqualTo(MineVisitScopeService.TEAM_SCOPE_UNAVAILABLE);
        assertThat(response.getItems()).isEmpty();
        ArgumentCaptor<Collection<Long>> teamIdsCaptor = ArgumentCaptor.captor();
        verify(scheduleQueryRecordEntityMapper).selectVisibleRecords(
                org.mockito.ArgumentMatchers.eq(7L), teamIdsCaptor.capture(),
                org.mockito.ArgumentMatchers.eq(21L), org.mockito.ArgumentMatchers.eq(0L),
                org.mockito.ArgumentMatchers.eq(21));
        assertThat(teamIdsCaptor.getValue()).isEmpty();
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
        assertThat(item.getPortfolioType()).isEqualTo("PERSONAL");
        assertThat(item.getPortfolioTypeText()).isEqualTo("个人作品集");
        assertThat(item.getCanMarkFollowed()).isTrue();
        assertThat(item.getSubmittedTimeText()).isEqualTo("07-05 13:30");
        assertThat(MineVisitRecordsResponse.ContactLeadItem.class.getDeclaredFields())
                .extracting(java.lang.reflect.Field::getName)
                .contains("phone", "wechat")
                .doesNotContain("phoneCiphertext", "wechatCiphertext");
    }

    @Test
    void contactLeadsShouldMergeJoinedTeamRecordsAndDecryptTeamContacts() {
        AuthContextHolder.set(new AuthContext(7L, "wf-dev-user-7"));
        when(mineVisitScopeService.requireScope(7L)).thenReturn(
                MineVisitScopeService.Scope.complete(Map.of(201L, "MANAGER", 202L, "MEMBER")));

        ContactLeadEntity managerLead = buildContactLead(501L, "TEAM", 201L, "星曜司仪团");
        managerLead.setPhoneCiphertext("manager-phone-ciphertext");
        managerLead.setWechatCiphertext("manager-wechat-ciphertext");
        ContactLeadEntity memberLead = buildContactLead(502L, "TEAM", 202L, "远山摄影团队");
        memberLead.setPhoneCiphertext("member-phone-ciphertext");
        memberLead.setWechatCiphertext("member-wechat-ciphertext");
        when(teamContactLeadCryptoService.decryptPhone("manager-phone-ciphertext")).thenReturn("13800138001");
        when(teamContactLeadCryptoService.decryptWechat("manager-wechat-ciphertext")).thenReturn("manager-wx");
        when(teamContactLeadCryptoService.decryptPhone("member-phone-ciphertext")).thenReturn("13800138002");
        when(teamContactLeadCryptoService.decryptWechat("member-wechat-ciphertext")).thenReturn("member-wx");
        Page<ContactLeadEntity> resultPage = new Page<>(1, 20, 2);
        resultPage.setRecords(List.of(managerLead, memberLead));
        when(contactLeadEntityMapper.selectPage(any(Page.class), any())).thenReturn(resultPage);

        MineVisitRecordsResponse.ContactLeadPage response = service().getContactLeads(1, 20);

        assertThat(response.getItems()).extracting(MineVisitRecordsResponse.ContactLeadItem::getPortfolioTitle)
                .containsExactly("星曜司仪团", "远山摄影团队");
        assertThat(response.getItems()).extracting(MineVisitRecordsResponse.ContactLeadItem::getPortfolioType)
                .containsOnly("TEAM");
        assertThat(response.getItems()).extracting(MineVisitRecordsResponse.ContactLeadItem::getPortfolioTypeText)
                .containsOnly("团队作品集");
        assertThat(response.getItems()).extracting(MineVisitRecordsResponse.ContactLeadItem::getCanMarkFollowed)
                .containsExactly(true, false);
        assertThat(response.getItems()).extracting(MineVisitRecordsResponse.ContactLeadItem::getPhone)
                .containsExactly("13800138001", "13800138002");
        assertThat(response.getItems()).extracting(MineVisitRecordsResponse.ContactLeadItem::getWechat)
                .containsExactly("manager-wx", "member-wx");

        verify(mineVisitScopeService).requireScope(7L);

        ArgumentCaptor<LambdaQueryWrapper<ContactLeadEntity>> leadQueryCaptor = ArgumentCaptor.captor();
        verify(contactLeadEntityMapper).selectPage(any(Page.class), leadQueryCaptor.capture());
        assertThat(leadQueryCaptor.getValue().getSqlSegment())
                .contains("owner_type", "owner_id", "submitted_at", "ORDER BY");
        assertThat(leadQueryCaptor.getValue().getParamNameValuePairs().values())
                .contains("USER", 7L, "TEAM", 201L, 202L)
                .doesNotContain(203L);
    }

    @Test
    void visitSummaryShouldCountPersonalAndJoinedTeamLeadsWithSameScope() {
        AuthContextHolder.set(new AuthContext(7L, "wf-dev-user-7"));
        when(mineVisitScopeService.resolveReadScope(7L)).thenReturn(
                MineVisitScopeService.Scope.complete(Map.of(201L, "OWNER")));
        when(contactLeadEntityMapper.selectCount(any())).thenReturn(9L);

        MineVisitRecordsResponse response = service().getVisitRecords();

        assertThat(response.getSummary().getContactLeadCount()).isEqualTo(9L);
        ArgumentCaptor<LambdaQueryWrapper<ContactLeadEntity>> countQueryCaptor = ArgumentCaptor.captor();
        verify(contactLeadEntityMapper).selectCount(countQueryCaptor.capture());
        assertThat(countQueryCaptor.getValue().getSqlSegment()).contains("owner_type", "owner_id");
        assertThat(countQueryCaptor.getValue().getParamNameValuePairs().values())
                .contains("USER", 7L, "TEAM", 201L)
                .doesNotContain(203L);
    }

    @Test
    void markContactLeadFollowedShouldRollbackForAnyException() throws NoSuchMethodException {
        Method method = MineVisitService.class.getMethod("markContactLeadFollowed", Long.class);

        Transactional transactional = method.getAnnotation(Transactional.class);

        assertThat(transactional).isNotNull();
        assertThat(transactional.rollbackFor()).contains(Exception.class);
    }

    @Test
    void markContactLeadFollowedShouldAllowJoinedTeamManager() {
        AuthContextHolder.set(new AuthContext(7L, "wf-dev-user-7"));
        ContactLeadEntity lead = buildContactLead(501L, "TEAM", 201L, "星曜司仪团");
        when(mineVisitScopeService.requireScope(7L)).thenReturn(
                MineVisitScopeService.Scope.complete(Map.of(201L, "MANAGER")));
        when(contactLeadEntityMapper.selectOne(any())).thenReturn(lead);
        when(contactLeadEntityMapper.updateById(lead)).thenReturn(1);

        MineVisitRecordsResponse.ContactLeadItem response = service().markContactLeadFollowed(501L);

        assertThat(lead.getFollowStatus()).isEqualTo("CONTACTED");
        assertThat(response.getPortfolioType()).isEqualTo("TEAM");
        assertThat(response.getCanMarkFollowed()).isTrue();
        verify(contactLeadEntityMapper).updateById(lead);
    }

    @Test
    void markContactLeadFollowedShouldRejectJoinedTeamMember() {
        AuthContextHolder.set(new AuthContext(7L, "wf-dev-user-7"));
        ContactLeadEntity lead = buildContactLead(501L, "TEAM", 201L, "星曜司仪团");
        when(mineVisitScopeService.requireScope(7L)).thenReturn(
                MineVisitScopeService.Scope.complete(Map.of(201L, "MEMBER")));
        when(contactLeadEntityMapper.selectOne(any())).thenReturn(lead);

        assertThatThrownBy(() -> service().markContactLeadFollowed(501L))
                .hasMessage("预留信息不存在或无访问权限");
        verify(contactLeadEntityMapper, never()).updateById((ContactLeadEntity) any());
    }

    /** 已联系状态重复操作应幂等返回，不再触发写库。 */
    @Test
    void markContactLeadFollowedShouldReturnIdempotentlyWhenAlreadyContacted() {
        AuthContextHolder.set(new AuthContext(7L, "wf-dev-user-7"));
        ContactLeadEntity lead = buildContactLead(501L, "TEAM", 201L, "星曜司仪团");
        lead.setFollowStatus("CONTACTED");
        when(mineVisitScopeService.requireScope(7L)).thenReturn(
                MineVisitScopeService.Scope.complete(Map.of(201L, "MANAGER")));
        when(contactLeadEntityMapper.selectOne(any())).thenReturn(lead);

        MineVisitRecordsResponse.ContactLeadItem response = service().markContactLeadFollowed(501L);

        assertThat(response.getFollowStatus()).isEqualTo("CONTACTED");
        verify(contactLeadEntityMapper, never()).updateById(any(ContactLeadEntity.class));
    }

    /** 已成交或无效线索不得被“标记已跟进”倒退为已联系。 */
    @ParameterizedTest
    @ValueSource(strings = {"DEAL_WON", "INVALID"})
    void markContactLeadFollowedShouldRejectTerminalStateRegression(String followStatus) {
        AuthContextHolder.set(new AuthContext(7L, "wf-dev-user-7"));
        ContactLeadEntity lead = buildContactLead(501L, "TEAM", 201L, "星曜司仪团");
        lead.setFollowStatus(followStatus);
        when(mineVisitScopeService.requireScope(7L)).thenReturn(
                MineVisitScopeService.Scope.complete(Map.of(201L, "OWNER")));
        when(contactLeadEntityMapper.selectOne(any())).thenReturn(lead);

        assertThatThrownBy(() -> service().markContactLeadFollowed(501L))
                .isInstanceOf(BusinessException.class)
                .hasMessage("预留信息状态已变化，请刷新后重试");

        assertThat(lead.getFollowStatus()).isEqualTo(followStatus);
        verify(contactLeadEntityMapper, never()).updateById(any(ContactLeadEntity.class));
    }

    /** 单条团队历史脏线索解密失败时列表必须降级展示掩码，不能让整页失败。 */
    @Test
    void contactLeadListShouldDegradeSingleTeamDecryptionFailure() {
        AuthContextHolder.set(new AuthContext(7L, "wf-dev-user-7"));
        ContactLeadEntity lead = buildContactLead(501L, "TEAM", 201L, "星曜司仪团");
        lead.setPhoneCiphertext("13800138000");
        lead.setPhoneLast4("8000");
        lead.setWechatCiphertext("plain-wechat");
        lead.setWechatMaskHint("pl***at");
        when(mineVisitScopeService.requireScope(7L)).thenReturn(
                MineVisitScopeService.Scope.complete(Map.of(201L, "MANAGER")));
        when(contactLeadEntityMapper.selectPage(any(Page.class), any())).thenAnswer(invocation -> {
            Page<ContactLeadEntity> page = invocation.getArgument(0);
            page.setRecords(List.of(lead));
            page.setTotal(1L);
            return page;
        });
        when(teamContactLeadCryptoService.decryptPhone("13800138000"))
                .thenThrow(new BusinessException("团队预留联系信息解密失败"));
        when(teamContactLeadCryptoService.decryptWechat("plain-wechat"))
                .thenThrow(new BusinessException("团队预留联系信息解密失败"));

        MineVisitRecordsResponse.ContactLeadPage response = service().getContactLeads(1, 20);

        assertThat(response.getItems()).singleElement().satisfies(item -> {
            assertThat(item.getPhone()).isEmpty();
            assertThat(item.getPhoneLast4()).isEqualTo("8000");
            assertThat(item.getWechat()).isEmpty();
            assertThat(item.getWechatMaskHint()).isEqualTo("pl***at");
        });
    }

    @Test
    void contactLeadListShouldFailClosedWhenCompleteScopeCannotBeResolved() {
        AuthContextHolder.set(new AuthContext(7L, "wf-dev-user-7"));
        IllegalStateException scopeFailure = new IllegalStateException("team scope unavailable");
        when(mineVisitScopeService.requireScope(7L)).thenThrow(scopeFailure);

        assertThatThrownBy(() -> service().getContactLeads(1, 20)).isSameAs(scopeFailure);

        verifyNoInteractions(contactLeadEntityMapper);
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

    /**
     * 团队访问记录跟进 — 团队拥有者和管理员均可将记录标记为已跟进。
     *
     * @param role 当前用户在团队中的角色
     */
    @ParameterizedTest
    @ValueSource(strings = {"OWNER", "MANAGER"})
    void markVisitFollowedShouldAllowTeamOwnerAndManager(String role) {
        AuthContextHolder.set(new AuthContext(7L, "wf-dev-user-7"));
        VisitRecordEntity record = buildTeamVisitRecord("NOT_FOLLOWED_UP");
        when(visitRecordEntityMapper.selectOne(any())).thenReturn(null).thenReturn(record);
        when(mineVisitScopeService.requireScope(7L)).thenReturn(
                MineVisitScopeService.Scope.complete(Map.of(201L, role)));
        when(visitRecordEntityMapper.updateById(record)).thenReturn(1);

        MineVisitRecordsResponse.Record response = service().markVisitFollowed(101L);

        assertThat(response.getFollowStatus()).isEqualTo("CONTACTED");
        assertThat(response.getCanMarkFollowed()).isTrue();
        verify(visitRecordEntityMapper).updateById(record);
    }

    /**
     * 团队访问记录跟进 — 普通成员可以读取记录但不能标记跟进。
     */
    @Test
    void markVisitFollowedShouldRejectJoinedTeamMember() {
        AuthContextHolder.set(new AuthContext(7L, "wf-dev-user-7"));
        VisitRecordEntity record = buildTeamVisitRecord("NOT_FOLLOWED_UP");
        when(visitRecordEntityMapper.selectOne(any())).thenReturn(null).thenReturn(record);
        lenient().when(mineVisitScopeService.requireScope(7L)).thenReturn(
                MineVisitScopeService.Scope.complete(Map.of(201L, "MEMBER")));

        assertThatThrownBy(() -> service().markVisitFollowed(101L))
                .hasMessage("访问记录不存在或无访问权限");

        verify(mineVisitScopeService).requireScope(7L);
        verify(visitRecordEntityMapper, never()).updateById((VisitRecordEntity) any());
    }

    /**
     * 团队访问记录跟进 — 非成员不在可见范围内，不能触发写入。
     */
    @Test
    void markVisitFollowedShouldRejectNonMember() {
        AuthContextHolder.set(new AuthContext(7L, "wf-dev-user-7"));
        when(visitRecordEntityMapper.selectOne(any())).thenReturn(null);
        when(mineVisitScopeService.requireScope(7L)).thenReturn(
                MineVisitScopeService.Scope.complete(Map.of()));

        assertThatThrownBy(() -> service().markVisitFollowed(101L))
                .hasMessage("访问记录不存在或无访问权限");

        verify(mineVisitScopeService).requireScope(7L);
        verify(visitRecordEntityMapper, never()).updateById((VisitRecordEntity) any());
    }

    /**
     * 团队访问记录跟进 — 已经处于已跟进状态时直接幂等成功。
     */
    @Test
    void markVisitFollowedShouldBeIdempotentWhenAlreadyContacted() {
        AuthContextHolder.set(new AuthContext(7L, "wf-dev-user-7"));
        VisitRecordEntity record = buildTeamVisitRecord("CONTACTED");
        when(visitRecordEntityMapper.selectOne(any())).thenReturn(null).thenReturn(record);
        when(mineVisitScopeService.requireScope(7L)).thenReturn(
                MineVisitScopeService.Scope.complete(Map.of(201L, "OWNER")));

        MineVisitRecordsResponse.Record response = service().markVisitFollowed(101L);

        assertThat(response.getFollowStatus()).isEqualTo("CONTACTED");
        verify(visitRecordEntityMapper, never()).updateById((VisitRecordEntity) any());
    }

    /**
     * 访问记录跟进 — 初始状态不是未跟进或已跟进时拒绝覆盖业务终态。
     *
     * @param followStatus 已存在的业务终态
     */
    @ParameterizedTest
    @ValueSource(strings = {"DEAL_WON", "INVALID"})
    void markVisitFollowedShouldRejectExistingTerminalStatus(String followStatus) {
        AuthContextHolder.set(new AuthContext(7L, "wf-dev-user-7"));
        VisitRecordEntity record = buildTeamVisitRecord(followStatus);
        when(visitRecordEntityMapper.selectOne(any())).thenReturn(null).thenReturn(record);
        when(mineVisitScopeService.requireScope(7L)).thenReturn(
                MineVisitScopeService.Scope.complete(Map.of(201L, "OWNER")));

        assertThatThrownBy(() -> service().markVisitFollowed(101L))
                .isInstanceOf(BusinessException.class)
                .hasMessage("访问记录状态已变化，请刷新后重试");

        verify(visitRecordEntityMapper, never()).updateById((VisitRecordEntity) any());
    }

    /**
     * 访问记录跟进 — 乐观锁冲突后重读为已跟进时按幂等成功处理。
     */
    @Test
    void markVisitFollowedShouldSucceedWhenConflictReloadsContacted() {
        AuthContextHolder.set(new AuthContext(7L, "wf-dev-user-7"));
        VisitRecordEntity original = buildTeamVisitRecord("NOT_FOLLOWED_UP");
        VisitRecordEntity latest = buildTeamVisitRecord("CONTACTED");
        when(visitRecordEntityMapper.selectOne(any()))
                .thenReturn(null)
                .thenReturn(original)
                .thenReturn(null)
                .thenReturn(latest);
        when(mineVisitScopeService.requireScope(7L)).thenReturn(
                MineVisitScopeService.Scope.complete(Map.of(201L, "MANAGER")));
        when(visitRecordEntityMapper.updateById(original)).thenReturn(0);

        MineVisitRecordsResponse.Record response = service().markVisitFollowed(101L);

        assertThat(response.getFollowStatus()).isEqualTo("CONTACTED");
        verify(mineVisitScopeService, times(2)).requireScope(7L);
        verify(visitRecordEntityMapper, times(1)).updateById(original);
    }

    /**
     * 访问记录跟进 — 乐观锁冲突后重新授权失败时，即使最新为已跟进也拒绝返回成功。
     */
    @Test
    void markVisitFollowedShouldRejectConflictWhenManagementPermissionWasRevoked() {
        AuthContextHolder.set(new AuthContext(7L, "wf-dev-user-7"));
        VisitRecordEntity original = buildTeamVisitRecord("NOT_FOLLOWED_UP");
        VisitRecordEntity latest = buildTeamVisitRecord("CONTACTED");
        when(visitRecordEntityMapper.selectOne(any()))
                .thenReturn(null)
                .thenReturn(original)
                .thenReturn(null)
                .thenReturn(latest);
        when(mineVisitScopeService.requireScope(7L))
                .thenReturn(MineVisitScopeService.Scope.complete(Map.of(201L, "OWNER")))
                .thenReturn(MineVisitScopeService.Scope.complete(Map.of(201L, "MEMBER")));
        when(visitRecordEntityMapper.updateById(original)).thenReturn(0);

        assertThatThrownBy(() -> service().markVisitFollowed(101L))
                .isInstanceOf(BusinessException.class)
                .hasMessage("访问记录状态已变化，请刷新后重试");

        verify(visitRecordEntityMapper, times(1)).updateById(original);
    }

    /**
     * 访问记录跟进 — 乐观锁冲突后业务状态已变化时不执行第二次更新。
     *
     * @param latestFollowStatus 冲突后读到的最新业务状态
     */
    @ParameterizedTest
    @ValueSource(strings = {"DEAL_WON", "INVALID"})
    void markVisitFollowedShouldRejectChangedStatusAfterConflict(String latestFollowStatus) {
        AuthContextHolder.set(new AuthContext(7L, "wf-dev-user-7"));
        VisitRecordEntity original = buildTeamVisitRecord("NOT_FOLLOWED_UP");
        VisitRecordEntity latest = buildTeamVisitRecord(latestFollowStatus);
        when(visitRecordEntityMapper.selectOne(any()))
                .thenReturn(null)
                .thenReturn(original)
                .thenReturn(null)
                .thenReturn(latest);
        when(mineVisitScopeService.requireScope(7L)).thenReturn(
                MineVisitScopeService.Scope.complete(Map.of(201L, "OWNER")));
        when(visitRecordEntityMapper.updateById(original)).thenReturn(0);

        assertThatThrownBy(() -> service().markVisitFollowed(101L))
                .isInstanceOf(BusinessException.class)
                .hasMessage("访问记录状态已变化，请刷新后重试");

        verify(visitRecordEntityMapper, times(1)).updateById(original);
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
                contactLeadEntityMapper,
                mineVisitScopeService,
                teamContactLeadCryptoService
        );
    }

    private ContactLeadEntity buildContactLead(Long id, String ownerType, Long ownerId, String portfolioTitle) {
        ContactLeadEntity lead = new ContactLeadEntity();
        lead.setId(id);
        lead.setOwnerType(ownerType);
        lead.setOwnerId(ownerId);
        lead.setContactName("客户" + id);
        lead.setDesiredSchedule("2026-10-03");
        lead.setNeeds("婚礼服务");
        lead.setPortfolioTitleSnapshot(portfolioTitle);
        lead.setSourceType("WECHAT_SHARE_CARD");
        lead.setFollowStatus("NOT_FOLLOWED_UP");
        lead.setSubmittedAt(LocalDateTime.of(2026, 7, 5, 13, 30));
        return lead;
    }

    /**
     * 构造团队访问记录测试数据。
     *
     * @param followStatus 跟进状态
     * @return 归属团队 201 的访问记录
     */
    private VisitRecordEntity buildTeamVisitRecord(String followStatus) {
        VisitRecordEntity record = buildRecord(
                101L,
                "anonymous-visitor-key-8A21",
                "TEAM_PORTFOLIO",
                "TEAM",
                "星曜司仪团",
                4,
                6,
                0,
                1,
                1,
                null,
                followStatus,
                LocalDateTime.of(2026, 7, 5, 11, 1)
        );
        record.setOwnerType("TEAM");
        record.setOwnerId(201L);
        return record;
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
        record.setPortfolioTitleSnapshot(sourceTitle);
        record.setPortfolioType(sourcePortfolioType);
        record.setOwnerType("USER");
        record.setOwnerId(7L);
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

    private MineScheduleQueryRecordRow buildScheduleQueryRow(
            Long sourceRecordId,
            String recordType,
            String portfolioTitle,
            Long visitorId,
            String visitorKey,
            LocalDateTime queriedAt
    ) {
        MineScheduleQueryRecordRow row = new MineScheduleQueryRecordRow();
        row.setSourceRecordId(sourceRecordId);
        row.setRecordType(recordType);
        row.setPortfolioTitleSnapshot(portfolioTitle);
        row.setVisitorId(visitorId);
        row.setVisitorKey(visitorKey);
        row.setSourceType("WECHAT_SHARE_CARD");
        row.setQueriedDate(LocalDate.of(2026, 7, 18));
        row.setSlotNameSnapshot("午宴");
        row.setStartTimeSnapshot(LocalTime.of(10, 0));
        row.setEndTimeSnapshot(LocalTime.of(14, 0));
        row.setResultStatus("BOOKED");
        row.setResultStatusText("已约");
        row.setAvailable(0);
        row.setResultMessage("该档期已约");
        row.setQueriedAt(queriedAt);
        return row;
    }
}
