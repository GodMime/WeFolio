package com.jxc.wefolio.service;

import com.jxc.wefolio.common.auth.VisitorContext;
import com.jxc.wefolio.common.auth.VisitorContextHolder;
import com.jxc.wefolio.dict.PortfolioOwnerTypeDict;
import com.jxc.wefolio.dict.PortfolioPublicationStatusDict;
import com.jxc.wefolio.dict.PortfolioStatusDict;
import com.jxc.wefolio.dict.PortfolioTemplateTypeDict;
import com.jxc.wefolio.dict.ScheduleStatusDict;
import com.jxc.wefolio.dict.SlotDefinitionStatusDict;
import com.jxc.wefolio.dict.VisitEventTypeDict;
import com.jxc.wefolio.dict.VisitSourceTypeDict;
import com.jxc.wefolio.dto.PortfolioScheduleOptionsResponse;
import com.jxc.wefolio.dto.PortfolioScheduleQueryRequest;
import com.jxc.wefolio.dto.PortfolioScheduleQueryResponse;
import com.jxc.wefolio.dto.VisitorPortfolioEventRequest;
import com.jxc.wefolio.dto.VisitorPortfolioOpenRequest;
import com.jxc.wefolio.dto.PortfolioRenderDto;
import com.jxc.wefolio.dto.VisitorPortfolioResponse;
import com.jxc.wefolio.dto.VisitorPortfolioScheduleResponse;
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
import com.jxc.wefolio.message.PortfolioMessage;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.List;

import com.alibaba.fastjson2.JSON;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.lenient;

/**
 * 访客作品集服务测试 — 覆盖已发布配置读取、维护中遮罩和访客档期。
 */
@ExtendWith(MockitoExtension.class)
class VisitorPortfolioServiceTest {

    /** 作品集 Mapper 模拟 */
    @Mock
    private PortfolioEntityMapper portfolioEntityMapper;

    /** 档期 Mapper 模拟 */
    @Mock
    private ScheduleEntityMapper scheduleEntityMapper;

    /** 档位定义 Mapper 模拟 */
    @Mock
    private SlotDefinitionEntityMapper slotDefinitionEntityMapper;

    /** 查询档期记录 Mapper 模拟 */
    @Mock
    private ScheduleQueryRecordEntityMapper scheduleQueryRecordEntityMapper;

    /** 访问服务模拟 */
    @Mock
    private PortfolioVisitService portfolioVisitService;

    /** 作品集渲染服务模拟 */
    @Mock
    private PortfolioRenderService portfolioRenderService;

    /** 访客身份服务模拟 */
    @Mock
    private VisitorService visitorService;

    /** 访客登录令牌服务模拟 */
    @Mock
    private VisitorAuthTokenService visitorAuthTokenService;

    /** 维护者本人访问识别服务模拟 */
    @Mock
    private OwnerSelfVisitService ownerSelfVisitService;

    @BeforeEach
    void setUp() {
        VisitorContextHolder.set(new VisitorContext(1024L, "visitor-a", "Bearer wf-visitor-v1.test"));
        lenient().when(visitorAuthTokenService.issueToken(any(Long.class), any()))
                .thenReturn(new VisitorAuthTokenService.VisitorLoginToken(
                        "Bearer",
                        "wf-visitor-v1.test",
                        30L * 24L * 60L * 60L
                ));
    }

    @AfterEach
    void tearDown() {
        VisitorContextHolder.clear();
    }

    @Test
    void openPortfolioShouldRejectUnpublishedPortfolio() {
        PortfolioEntity portfolio = publishedPortfolio();
        portfolio.setPublicationStatus(PortfolioPublicationStatusDict.DRAFT_ONLY.getCode());
        when(portfolioEntityMapper.selectOne(any())).thenReturn(portfolio);
        VisitorPortfolioOpenRequest request = new VisitorPortfolioOpenRequest();
        request.setLoginCode("wx-code");

        assertThatThrownBy(() -> service().openPortfolio("PF001", request))
                .isInstanceOf(BusinessException.class)
                .hasMessage("作品集暂不可访问");
        verify(visitorService, never()).resolveForOpen(any(), any(), any(), any());
    }

    @Test
    void openPortfolioShouldRejectInvalidPublishedConfigWithUnavailableMessage() {
        PortfolioEntity portfolio = publishedPortfolio();
        portfolio.setPublishedConfigJson("{invalid-json");
        when(portfolioEntityMapper.selectOne(any())).thenReturn(portfolio);
        VisitorPortfolioOpenRequest request = new VisitorPortfolioOpenRequest();
        request.setLoginCode("wx-code");

        assertThatThrownBy(() -> service().openPortfolio("PF001", request))
                .isInstanceOf(BusinessException.class)
                .hasMessage(PortfolioMessage.PORTFOLIO_UNAVAILABLE_MESSAGE);
        verify(visitorService, never()).resolveForOpen(any(), any(), any(), any());
        verify(portfolioVisitService, never()).recordOpen(any(), any(Long.class), any(), any(), any());
    }

    @Test
    void openPortfolioShouldCreateGlobalVisitorRecordAndNotExposeOpenidOrVisitorId() {
        PortfolioEntity portfolio = publishedPortfolio();
        VisitRecordEntity record = new VisitRecordEntity();
        record.setId(33L);
        VisitorEntity visitor = new VisitorEntity();
        visitor.setId(1024L);
        visitor.setOpenid("openid-123");
        visitor.setVisitorKey("0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef");
        when(portfolioEntityMapper.selectOne(any())).thenReturn(portfolio);
        when(visitorService.resolveForOpen(eq("wx-code"), isNull(), eq("PERSONAL:88"), any()))
                .thenReturn(new VisitorService.VisitorSession(visitor, true));
        when(portfolioVisitService.recordOpen(
                eq(portfolio),
                eq(1024L),
                eq(visitor.getVisitorKey()),
                eq("WECHAT_SHARE_CARD"),
                eq("open-1")))
                .thenReturn(record);
        when(visitorService.createProfileToken(1024L, 88L, 33L)).thenReturn("profile-token-1");
        PortfolioRenderDto renderData = new PortfolioRenderDto();
        renderData.setVisitRecordId(33L);
        when(portfolioRenderService.render(eq(portfolio), any(), eq(false), eq(false), eq(null), eq(33L)))
                .thenReturn(renderData);
        VisitorPortfolioOpenRequest request = new VisitorPortfolioOpenRequest();
        request.setLoginCode("wx-code");
        request.setSourceType("WECHAT_SHARE_CARD");
        request.setIdempotencyKey("open-1");

        VisitorPortfolioResponse response = service().openPortfolio("PF001", request);

        assertThat(response.getVisitorKey()).isEqualTo(visitor.getVisitorKey());
        assertThat(response.getTokenType()).isEqualTo("Bearer");
        assertThat(response.getToken()).isEqualTo("wf-visitor-v1.test");
        assertThat(response.getExpiresInSeconds()).isEqualTo(30L * 24L * 60L * 60L);
        assertThat(response.isNewVisitor()).isTrue();
        assertThat(response.getVisitorProfileToken()).isEqualTo("profile-token-1");
        assertThat(response.getVisitRecordId()).isEqualTo(33L);
        assertThat(response.getRenderData()).isSameAs(renderData);
        assertThat(JSON.toJSONString(response))
                .contains("\"needVisitorProfile\":true")
                .doesNotContain("openid")
                .doesNotContain("visitorId");
    }

    @Test
    void openPortfolioShouldRecordAnonymousTimelineVisitWithoutProfilePrompt() {
        PortfolioEntity portfolio = publishedPortfolio();
        VisitRecordEntity record = new VisitRecordEntity();
        record.setId(44L);
        VisitorEntity visitor = new VisitorEntity();
        visitor.setId(2048L);
        visitor.setOpenid("timeline:hashed");
        visitor.setVisitorKey("timeline-visitor-key");
        String anonymousSessionId = "timeline-abc123def456ghi789jkl012mno345pqr678";
        when(portfolioEntityMapper.selectOne(any())).thenReturn(portfolio);
        when(visitorService.resolveForOpen(
                isNull(), eq(anonymousSessionId), eq("PERSONAL:88"), any()))
                .thenReturn(new VisitorService.VisitorSession(visitor, true, true));
        when(portfolioVisitService.recordOpen(
                eq(portfolio),
                eq(2048L),
                eq("timeline-visitor-key"),
                eq("WECHAT_SHARE_CARD"),
                eq("timeline-open-1")))
                .thenReturn(record);
        when(portfolioRenderService.render(eq(portfolio), any(), eq(false), eq(false), eq(null), eq(44L)))
                .thenReturn(new PortfolioRenderDto());
        when(visitorAuthTokenService.issueTimelineAnonymousToken(
                2048L, "timeline-visitor-key", "PERSONAL:PF001"))
                .thenReturn(new VisitorAuthTokenService.VisitorLoginToken(
                        "Bearer", "wf-visitor-timeline-v1.test", 7200L));
        VisitorPortfolioOpenRequest request = new VisitorPortfolioOpenRequest();
        request.setAnonymousSessionId(anonymousSessionId);
        request.setSourceType("WECHAT_SHARE_CARD");
        request.setIdempotencyKey("timeline-open-1");

        VisitorPortfolioResponse response = service().openPortfolio("PF001", request);

        assertThat(response.getVisitRecordId()).isEqualTo(44L);
        assertThat(response.getVisitorKey()).isEqualTo("timeline-visitor-key");
        assertThat(response.getToken()).isEqualTo("wf-visitor-timeline-v1.test");
        assertThat(response.isNeedVisitorProfile()).isFalse();
        assertThat(response.getVisitorProfileToken()).isNull();
        verify(visitorService, never()).createProfileToken(any(), any(), any());
        verify(visitorAuthTokenService, never()).issueToken(any(), any());
    }

    @Test
    void openPortfolioShouldReturnMaintenanceWhenRecordOpenFails() {
        PortfolioEntity portfolio = publishedPortfolio();
        VisitorEntity visitor = new VisitorEntity();
        visitor.setId(1024L);
        visitor.setOpenid("openid-123");
        visitor.setVisitorKey("0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef");
        when(portfolioEntityMapper.selectOne(any())).thenReturn(portfolio);
        when(visitorService.resolveForOpen(eq("wx-code"), isNull(), eq("PERSONAL:88"), any()))
                .thenReturn(new VisitorService.VisitorSession(visitor, true));
        when(portfolioVisitService.recordOpen(
                eq(portfolio),
                eq(1024L),
                eq(visitor.getVisitorKey()),
                eq("WECHAT_SHARE_CARD"),
                eq("open-1")))
                .thenThrow(new BusinessException("积分余额不足，请充值后再试"));
        when(visitorService.createProfileToken(1024L, 88L, null)).thenReturn("profile-token-1");
        PortfolioRenderDto renderData = new PortfolioRenderDto();
        renderData.setUnderMaintenance(true);
        when(portfolioRenderService.render(eq(portfolio), any(), eq(false), eq(true), any(), eq(null)))
                .thenReturn(renderData);
        VisitorPortfolioOpenRequest request = new VisitorPortfolioOpenRequest();
        request.setLoginCode("wx-code");
        request.setSourceType("WECHAT_SHARE_CARD");
        request.setIdempotencyKey("open-1");

        VisitorPortfolioResponse response = service().openPortfolio("PF001", request);

        assertThat(response.isUnderMaintenance()).isTrue();
        assertThat(response.getVisitRecordId()).isNull();
        assertThat(response.getVisitorKey()).isEqualTo(visitor.getVisitorKey());
        assertThat(response.getVisitorProfileToken()).isEqualTo("profile-token-1");
        assertThat(response.getRenderData()).isSameAs(renderData);
    }

    /** 计费窗口异常属于服务错误，不得伪装成维护态。 */
    @Test
    void openPortfolioShouldPropagateBillingWindowFailure() {
        PortfolioEntity portfolio = publishedPortfolio();
        VisitorEntity visitor = new VisitorEntity();
        visitor.setId(1024L);
        visitor.setOpenid("openid-123");
        visitor.setVisitorKey("0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef");
        when(portfolioEntityMapper.selectOne(any())).thenReturn(portfolio);
        when(visitorService.resolveForOpen(eq("wx-code"), isNull(), eq("PERSONAL:88"), any()))
                .thenReturn(new VisitorService.VisitorSession(visitor, true));
        when(portfolioVisitService.recordOpen(
                eq(portfolio),
                eq(1024L),
                eq(visitor.getVisitorKey()),
                eq("WECHAT_SHARE_CARD"),
                eq("open-1")))
                .thenThrow(new BusinessException("积分扣费窗口异常，请重试"));
        VisitorPortfolioOpenRequest request = new VisitorPortfolioOpenRequest();
        request.setLoginCode("wx-code");
        request.setSourceType("WECHAT_SHARE_CARD");
        request.setIdempotencyKey("open-1");

        assertThatThrownBy(() -> service().openPortfolio("PF001", request))
                .isInstanceOf(BusinessException.class)
                .hasMessage("积分扣费窗口异常，请重试");

        verify(portfolioRenderService, never()).render(any(), any(), any(Boolean.class),
                any(Boolean.class), any(), any());
    }

    @Test
    void openPortfolioShouldPromptExistingVisitorWhenProfileIsMissing() {
        PortfolioEntity portfolio = publishedPortfolio();
        VisitRecordEntity record = new VisitRecordEntity();
        record.setId(33L);
        VisitorEntity visitor = new VisitorEntity();
        visitor.setId(1024L);
        visitor.setOpenid("openid-123");
        visitor.setVisitorKey("0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef");
        when(portfolioEntityMapper.selectOne(any())).thenReturn(portfolio);
        when(visitorService.resolveForOpen(eq("wx-code"), isNull(), eq("PERSONAL:88"), any()))
                .thenReturn(new VisitorService.VisitorSession(visitor, false));
        when(portfolioVisitService.recordOpen(
                eq(portfolio),
                eq(1024L),
                eq(visitor.getVisitorKey()),
                eq("WECHAT_SHARE_CARD"),
                eq("open-1")))
                .thenReturn(record);
        when(visitorService.createProfileToken(1024L, 88L, 33L)).thenReturn("profile-token-1");
        when(portfolioRenderService.render(eq(portfolio), any(), eq(false), eq(false), eq(null), eq(33L)))
                .thenReturn(new PortfolioRenderDto());
        VisitorPortfolioOpenRequest request = new VisitorPortfolioOpenRequest();
        request.setLoginCode("wx-code");
        request.setSourceType("WECHAT_SHARE_CARD");
        request.setIdempotencyKey("open-1");

        VisitorPortfolioResponse response = service().openPortfolio("PF001", request);

        assertThat(response.isNewVisitor()).isFalse();
        assertThat(response.getVisitorProfileToken()).isEqualTo("profile-token-1");
        assertThat(JSON.toJSONString(response)).contains("\"needVisitorProfile\":true");
    }

    @Test
    void openPortfolioShouldNotPromptExistingVisitorWhenProfileIsComplete() {
        PortfolioEntity portfolio = publishedPortfolio();
        VisitRecordEntity record = new VisitRecordEntity();
        record.setId(33L);
        VisitorEntity visitor = new VisitorEntity();
        visitor.setId(1024L);
        visitor.setOpenid("openid-123");
        visitor.setVisitorKey("0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef");
        visitor.setNickname("小陈");
        visitor.setAvatarUrl("https://cdn.example.com/visit/visitor-avatar-1024-20260705093000-a1b2c3d4.jpg");
        when(portfolioEntityMapper.selectOne(any())).thenReturn(portfolio);
        when(visitorService.resolveForOpen(eq("wx-code"), isNull(), eq("PERSONAL:88"), any()))
                .thenReturn(new VisitorService.VisitorSession(visitor, false));
        when(portfolioVisitService.recordOpen(
                eq(portfolio),
                eq(1024L),
                eq(visitor.getVisitorKey()),
                eq("WECHAT_SHARE_CARD"),
                eq("open-1")))
                .thenReturn(record);
        when(portfolioRenderService.render(eq(portfolio), any(), eq(false), eq(false), eq(null), eq(33L)))
                .thenReturn(new PortfolioRenderDto());
        VisitorPortfolioOpenRequest request = new VisitorPortfolioOpenRequest();
        request.setLoginCode("wx-code");
        request.setSourceType("WECHAT_SHARE_CARD");
        request.setIdempotencyKey("open-1");

        VisitorPortfolioResponse response = service().openPortfolio("PF001", request);

        assertThat(response.getVisitorProfileToken()).isNull();
        assertThat(JSON.toJSONString(response)).contains("\"needVisitorProfile\":false");
        verify(visitorService, never()).createProfileToken(any(), any(), any());
    }

    @Test
    void ownerSelfOpenShouldCreateVisitorButSkipVisitRecordAndProfilePrompt() {
        PortfolioEntity portfolio = publishedPortfolio();
        VisitorEntity visitor = new VisitorEntity();
        visitor.setId(1024L);
        visitor.setOpenid("openid-owner");
        visitor.setVisitorKey("owner-visitor-key");
        when(portfolioEntityMapper.selectOne(any())).thenReturn(portfolio);
        when(visitorService.resolveForOpen(eq("wx-code"), isNull(), eq("PERSONAL:88"), any()))
                .thenReturn(new VisitorService.VisitorSession(visitor, false));
        when(ownerSelfVisitService.isOwnerSelfVisitor(
                eq(7L),
                eq("openid-owner"),
                eq(88L),
                eq(1024L),
                eq(VisitEventTypeDict.PORTFOLIO_OPENED.getCode())
        )).thenReturn(true);
        PortfolioRenderDto renderData = new PortfolioRenderDto();
        when(portfolioRenderService.render(eq(portfolio), any(), eq(false), eq(false), eq(null), eq(null)))
                .thenReturn(renderData);
        VisitorPortfolioOpenRequest request = new VisitorPortfolioOpenRequest();
        request.setLoginCode("wx-code");
        request.setSourceType("WECHAT_SHARE_CARD");
        request.setIdempotencyKey("open-owner-1");

        VisitorPortfolioResponse response = service().openPortfolio("PF001", request);

        assertThat(response.isUnderMaintenance()).isFalse();
        assertThat(response.getVisitRecordId()).isNull();
        assertThat(response.getVisitorKey()).isEqualTo("owner-visitor-key");
        assertThat(response.getVisitorProfileToken()).isNull();
        assertThat(JSON.toJSONString(response)).contains("\"needVisitorProfile\":false");
        assertThat(response.getRenderData()).isSameAs(renderData);
        verify(portfolioVisitService, never()).recordOpen(any(), any(Long.class), any(), any(), any());
        verify(visitorService, never()).createProfileToken(any(), any(), any());
    }

    @Test
    void queryScheduleShouldHideInternalContactAndNoteFields() {
        PortfolioEntity portfolio = publishedPortfolio();
        when(portfolioEntityMapper.selectOne(any())).thenReturn(portfolio);
        ScheduleEntity schedule = new ScheduleEntity();
        schedule.setId(9L);
        schedule.setUserId(7L);
        schedule.setScheduleDate(LocalDate.of(2026, 7, 18));
        schedule.setSlotNameSnapshot("午宴");
        schedule.setStartTimeSnapshot(LocalTime.of(10, 0));
        schedule.setEndTimeSnapshot(LocalTime.of(14, 0));
        schedule.setColorSnapshot("#2d5f9a");
        schedule.setStatus(ScheduleStatusDict.TENTATIVE.getCode());
        schedule.setContactNameCiphertext("内部客户");
        schedule.setContactPhoneCiphertext("13800138000");
        schedule.setNote("内部备注");
        when(scheduleEntityMapper.selectList(any())).thenReturn(List.of(schedule));

        VisitorPortfolioScheduleResponse response = service().querySchedule(
                "PF001",
                LocalDate.of(2026, 7, 18),
                LocalDate.of(2026, 7, 18),
                "ALL",
                "visitor-a",
                "schedule-1"
        );

        assertThat(response.getSchedules()).hasSize(1);
        VisitorPortfolioScheduleResponse.Item item = response.getSchedules().get(0);
        assertThat(item.getSlotName()).isEqualTo("午宴");
        assertThat(item.getStatusText()).isEqualTo("待定");
        assertThat(item.getContactName()).isNull();
        assertThat(item.getContactPhone()).isNull();
        assertThat(item.getNote()).isNull();
        verify(portfolioVisitService).recordScheduleQuery(portfolio, "visitor-a", LocalDate.of(2026, 7, 18), "schedule-1");
    }

    @Test
    void queryScheduleShouldIgnoreRequestVisitorKeyAndUseVisitorContext() {
        VisitorContextHolder.set(new VisitorContext(2048L, "server-key", "Bearer wf-visitor-v1.server"));
        PortfolioEntity portfolio = publishedPortfolio();
        when(portfolioEntityMapper.selectOne(any())).thenReturn(portfolio);
        when(scheduleEntityMapper.selectList(any())).thenReturn(List.of());

        service().querySchedule(
                "PF001",
                LocalDate.of(2026, 7, 18),
                LocalDate.of(2026, 7, 18),
                "ALL",
                "attacker-key",
                "schedule-1"
        );

        verify(portfolioVisitService).recordScheduleQuery(portfolio, "server-key", LocalDate.of(2026, 7, 18), "schedule-1");
    }

    @Test
    void queryScheduleOptionsShouldHideVisitorScheduleMarksWithoutRecordingEvent() {
        PortfolioEntity portfolio = publishedPortfolioWithScheduleComponent();
        when(portfolioEntityMapper.selectOne(any())).thenReturn(portfolio);
        when(slotDefinitionEntityMapper.selectList(any())).thenReturn(List.of(slotDefinition(12L, "午宴")));

        PortfolioScheduleOptionsResponse response = service().queryScheduleOptions("PF001", "2026-07", "c_schedule");

        assertThat(response.getYearMonth()).isEqualTo("2026-07");
        assertThat(response.getSlotDefinitions()).hasSize(1);
        assertThat(response.getSlotDefinitions().get(0).getId()).isEqualTo(12L);
        assertThat(response.getDays()).hasSize(42);
        assertThat(response.getDays()).anySatisfy(day -> {
            assertThat(day.getDate()).isEqualTo("2026-07-18");
            assertThat(day.getColors()).isEmpty();
            assertThat(day.getCount()).isZero();
        });
        assertThat(response.getSchedules()).isEmpty();
        verify(scheduleEntityMapper, never()).selectList(any());
        verify(portfolioVisitService, never()).recordScheduleQuery(any(), any(), any(), any());
    }

    @Test
    void queryScheduleOptionsShouldNotReturnNullableScheduleFieldsToVisitor() {
        PortfolioEntity portfolio = publishedPortfolioWithScheduleComponent();
        when(portfolioEntityMapper.selectOne(any())).thenReturn(portfolio);
        when(slotDefinitionEntityMapper.selectList(any())).thenReturn(List.of(slotDefinition(12L, "午宴")));

        PortfolioScheduleOptionsResponse response = service().queryScheduleOptions("PF001", "2026-07", "c_schedule");

        assertThat(response.getSchedules()).isEmpty();
        assertThat(response.getDays()).allSatisfy(day -> {
            assertThat(day.getColors()).isEmpty();
            assertThat(day.getCount()).isZero();
        });
    }

    @Test
    void submitScheduleQueryShouldRecordSlotMetadataAndReturnAvailability() {
        PortfolioEntity portfolio = publishedPortfolioWithScheduleComponent();
        when(portfolioEntityMapper.selectOne(any())).thenReturn(portfolio);
        when(slotDefinitionEntityMapper.selectById(12L)).thenReturn(slotDefinition(12L, "午宴"));
        when(scheduleEntityMapper.selectOne(any())).thenReturn(schedule(
                9L,
                12L,
                LocalDate.of(2026, 7, 18),
                "午宴",
                ScheduleStatusDict.BOOKED.getCode()
        ));
        PortfolioScheduleQueryRequest request = new PortfolioScheduleQueryRequest();
        request.setVisitorKey("visitor-a");
        request.setComponentKey("c_schedule");
        request.setQueriedDate(LocalDate.of(2026, 7, 18));
        request.setSlotDefinitionId(12L);
        request.setIdempotencyKey("schedule-submit-1");

        PortfolioScheduleQueryResponse response = service().submitScheduleQuery("PF001", request);

        assertThat(response.isAvailable()).isFalse();
        assertThat(response.getMessage()).isEqualTo("该档期已约");
        assertThat(response.getSlotDefinitionId()).isEqualTo(12L);
        verify(portfolioVisitService).recordScheduleQuery(
                eq(portfolio),
                eq("visitor-a"),
                eq(LocalDate.of(2026, 7, 18)),
                org.mockito.ArgumentMatchers.argThat(metadata ->
                        "c_schedule".equals(metadata.get("componentKey"))
                                && Long.valueOf(12L).equals(metadata.get("slotDefinitionId"))
                                && "午宴".equals(metadata.get("slotName"))
                                && Boolean.FALSE.equals(metadata.get("available"))
                ),
                eq("schedule-submit-1")
        );
    }

    @Test
    void submitScheduleQueryShouldPersistButtonQueryRecordSnapshot() {
        PortfolioEntity portfolio = publishedPortfolioWithScheduleComponent();
        VisitRecordEntity visitRecord = new VisitRecordEntity();
        visitRecord.setId(33L);
        visitRecord.setVisitorId(1024L);
        visitRecord.setVisitorKey("visitor-a");
        visitRecord.setSourceType(VisitSourceTypeDict.WECHAT_SHARE_CARD.getCode());
        LocalDateTime queriedAt = LocalDateTime.of(2026, 7, 5, 14, 18);
        when(portfolioEntityMapper.selectOne(any())).thenReturn(portfolio);
        when(slotDefinitionEntityMapper.selectById(12L)).thenReturn(slotDefinition(12L, "午宴"));
        when(scheduleEntityMapper.selectOne(any())).thenReturn(schedule(
                9L,
                12L,
                LocalDate.of(2026, 7, 18),
                "午宴",
                ScheduleStatusDict.BOOKED.getCode()
        ));
        when(portfolioVisitService.recordScheduleQuery(
                eq(portfolio),
                eq("visitor-a"),
                eq(LocalDate.of(2026, 7, 18)),
                any(),
                eq("schedule-submit-1")
        )).thenReturn(PortfolioVisitService.ScheduleQueryRecordResult.recorded(visitRecord, queriedAt));
        PortfolioScheduleQueryRequest request = new PortfolioScheduleQueryRequest();
        request.setVisitorKey("visitor-a");
        request.setComponentKey("c_schedule");
        request.setQueriedDate(LocalDate.of(2026, 7, 18));
        request.setSlotDefinitionId(12L);
        request.setIdempotencyKey("schedule-submit-1");

        service().submitScheduleQuery("PF001", request);

        ArgumentCaptor<ScheduleQueryRecordEntity> recordCaptor = ArgumentCaptor.forClass(ScheduleQueryRecordEntity.class);
        verify(scheduleQueryRecordEntityMapper).insert(recordCaptor.capture());
        ScheduleQueryRecordEntity record = recordCaptor.getValue();
        assertThat(record.getPortfolioId()).isEqualTo(88L);
        assertThat(record.getPortfolioType()).isEqualTo("PERSONAL");
        assertThat(record.getPortfolioTitleSnapshot()).isEqualTo("林安婚礼司仪");
        assertThat(record.getVisitRecordId()).isEqualTo(33L);
        assertThat(record.getVisitorId()).isEqualTo(1024L);
        assertThat(record.getVisitorKey()).isEqualTo("visitor-a");
        assertThat(record.getOwnerType()).isEqualTo("USER");
        assertThat(record.getOwnerId()).isEqualTo(7L);
        assertThat(record.getSourceType()).isEqualTo("WECHAT_SHARE_CARD");
        assertThat(record.getDisplayMode()).isEqualTo("MODAL_CALENDAR");
        assertThat(record.getQueriedDate()).isEqualTo(LocalDate.of(2026, 7, 18));
        assertThat(record.getSlotDefinitionId()).isEqualTo(12L);
        assertThat(record.getSlotNameSnapshot()).isEqualTo("午宴");
        assertThat(record.getStartTimeSnapshot()).isEqualTo(LocalTime.of(10, 0));
        assertThat(record.getEndTimeSnapshot()).isEqualTo(LocalTime.of(14, 0));
        assertThat(record.getColorSnapshot()).isEqualTo("#2d5f9a");
        assertThat(record.getResultStatus()).isEqualTo(ScheduleStatusDict.BOOKED.getCode());
        assertThat(record.getResultStatusText()).isEqualTo("已约");
        assertThat(record.getAvailable()).isEqualTo(0);
        assertThat(record.getResultMessage()).isEqualTo("该档期已约");
        assertThat(record.getQueriedAt()).isEqualTo(queriedAt);
    }

    @Test
    void submitScheduleQueryShouldPersistBusinessRecordWhenScheduleTimeSnapshotsMissing() {
        PortfolioEntity portfolio = publishedPortfolioWithScheduleComponent();
        VisitRecordEntity visitRecord = new VisitRecordEntity();
        visitRecord.setId(33L);
        visitRecord.setVisitorKey("visitor-a");
        LocalDateTime queriedAt = LocalDateTime.of(2026, 7, 5, 14, 18);
        when(portfolioEntityMapper.selectOne(any())).thenReturn(portfolio);
        when(slotDefinitionEntityMapper.selectById(12L)).thenReturn(slotDefinition(12L, "午宴"));
        when(scheduleEntityMapper.selectOne(any())).thenReturn(scheduleWithNullableSnapshots(
                9L,
                12L,
                LocalDate.of(2026, 7, 18)
        ));
        when(portfolioVisitService.recordScheduleQuery(
                eq(portfolio),
                eq("visitor-a"),
                eq(LocalDate.of(2026, 7, 18)),
                any(),
                eq("schedule-submit-1")
        )).thenReturn(PortfolioVisitService.ScheduleQueryRecordResult.recorded(visitRecord, queriedAt));
        PortfolioScheduleQueryRequest request = new PortfolioScheduleQueryRequest();
        request.setVisitorKey("visitor-a");
        request.setComponentKey("c_schedule");
        request.setQueriedDate(LocalDate.of(2026, 7, 18));
        request.setSlotDefinitionId(12L);
        request.setIdempotencyKey("schedule-submit-1");

        service().submitScheduleQuery("PF001", request);

        ArgumentCaptor<ScheduleQueryRecordEntity> recordCaptor = ArgumentCaptor.forClass(ScheduleQueryRecordEntity.class);
        verify(scheduleQueryRecordEntityMapper).insert(recordCaptor.capture());
        ScheduleQueryRecordEntity record = recordCaptor.getValue();
        assertThat(record.getStartTimeSnapshot()).isNull();
        assertThat(record.getEndTimeSnapshot()).isNull();
    }

    @Test
    void submitScheduleQueryShouldPersistBusinessRecordWhenVisitEventInsertHitsConcurrentConflict() {
        PortfolioEntity portfolio = publishedPortfolioWithScheduleComponent();
        VisitRecordEntity visitRecord = new VisitRecordEntity();
        visitRecord.setId(33L);
        visitRecord.setVisitorKey("visitor-a");
        visitRecord.setSourceType(VisitSourceTypeDict.WECHAT_SHARE_CARD.getCode());
        LocalDateTime queriedAt = LocalDateTime.of(2026, 7, 5, 14, 18);
        when(portfolioEntityMapper.selectOne(any())).thenReturn(portfolio);
        when(slotDefinitionEntityMapper.selectById(12L)).thenReturn(slotDefinition(12L, "午宴"));
        when(scheduleEntityMapper.selectOne(any())).thenReturn(null);
        when(portfolioVisitService.recordScheduleQuery(
                eq(portfolio),
                eq("visitor-a"),
                eq(LocalDate.of(2026, 7, 18)),
                any(),
                eq("schedule-submit-1")
        )).thenReturn(PortfolioVisitService.ScheduleQueryRecordResult.concurrentConflict(visitRecord, queriedAt));
        PortfolioScheduleQueryRequest request = new PortfolioScheduleQueryRequest();
        request.setVisitorKey("visitor-a");
        request.setComponentKey("c_schedule");
        request.setQueriedDate(LocalDate.of(2026, 7, 18));
        request.setSlotDefinitionId(12L);
        request.setIdempotencyKey("schedule-submit-1");

        PortfolioScheduleQueryResponse response = service().submitScheduleQuery("PF001", request);

        assertThat(response.isAvailable()).isTrue();
        ArgumentCaptor<ScheduleQueryRecordEntity> recordCaptor = ArgumentCaptor.forClass(ScheduleQueryRecordEntity.class);
        verify(scheduleQueryRecordEntityMapper).insert(recordCaptor.capture());
        ScheduleQueryRecordEntity record = recordCaptor.getValue();
        assertThat(record.getVisitRecordId()).isEqualTo(33L);
        assertThat(record.getVisitorKey()).isEqualTo("visitor-a");
        assertThat(record.getSourceType()).isEqualTo(VisitSourceTypeDict.WECHAT_SHARE_CARD.getCode());
        assertThat(record.getAvailable()).isEqualTo(1);
        assertThat(record.getResultMessage()).isEqualTo(response.getMessage());
        assertThat(record.getQueriedAt()).isEqualTo(queriedAt);
    }

    @Test
    void parseTimeShouldReturnNullWhenSnapshotTextInvalid() {
        LocalTime parsedTime = ReflectionTestUtils.invokeMethod(service(), "parseTime", "10点");

        assertThat(parsedTime).isNull();
    }

    @Test
    void submitScheduleQueryShouldSkipBusinessRecordWhenIdempotencyKeyRepeated() {
        PortfolioEntity portfolio = publishedPortfolioWithScheduleComponent();
        VisitRecordEntity visitRecord = new VisitRecordEntity();
        visitRecord.setId(33L);
        visitRecord.setVisitorKey("visitor-a");
        when(portfolioEntityMapper.selectOne(any())).thenReturn(portfolio);
        when(slotDefinitionEntityMapper.selectById(12L)).thenReturn(slotDefinition(12L, "午宴"));
        when(scheduleEntityMapper.selectOne(any())).thenReturn(null);
        when(portfolioVisitService.recordScheduleQuery(
                eq(portfolio),
                eq("visitor-a"),
                eq(LocalDate.of(2026, 7, 18)),
                any(),
                eq("schedule-submit-1")
        )).thenReturn(PortfolioVisitService.ScheduleQueryRecordResult.skipped(visitRecord));
        PortfolioScheduleQueryRequest request = new PortfolioScheduleQueryRequest();
        request.setVisitorKey("visitor-a");
        request.setComponentKey("c_schedule");
        request.setQueriedDate(LocalDate.of(2026, 7, 18));
        request.setSlotDefinitionId(12L);
        request.setIdempotencyKey("schedule-submit-1");

        PortfolioScheduleQueryResponse response = service().submitScheduleQuery("PF001", request);

        assertThat(response.isAvailable()).isTrue();
        verify(scheduleQueryRecordEntityMapper, never()).insert(any(ScheduleQueryRecordEntity.class));
    }

    @Test
    void submitScheduleQueryShouldRejectEmptyRequestBodyBeforeComponentLookup() {
        assertThatThrownBy(() -> service().submitScheduleQuery("PF001", null))
                .isInstanceOf(BusinessException.class)
                .hasMessage("档期查询请求不能为空");
        verify(portfolioEntityMapper, never()).selectOne(any());
        verify(portfolioVisitService, never()).recordScheduleQuery(any(), any(), any(), any(), any());
    }

    @Test
    void submitScheduleQueryShouldTreatMissingScheduleAsAvailable() {
        PortfolioEntity portfolio = publishedPortfolioWithScheduleComponent();
        when(portfolioEntityMapper.selectOne(any())).thenReturn(portfolio);
        when(slotDefinitionEntityMapper.selectById(12L)).thenReturn(slotDefinition(12L, "午宴"));
        when(scheduleEntityMapper.selectOne(any())).thenReturn(null);
        PortfolioScheduleQueryRequest request = new PortfolioScheduleQueryRequest();
        request.setVisitorKey("visitor-a");
        request.setComponentKey("c_schedule");
        request.setQueriedDate(LocalDate.of(2026, 7, 18));
        request.setSlotDefinitionId(12L);
        request.setIdempotencyKey("schedule-submit-2");

        PortfolioScheduleQueryResponse response = service().submitScheduleQuery("PF001", request);

        assertThat(response.isAvailable()).isTrue();
        assertThat(response.getStatus()).isEqualTo("AVAILABLE");
        assertThat(response.getMessage()).isEqualTo("档期空闲");
    }

    @Test
    void submitScheduleQueryShouldIgnoreRequestVisitorKeyAndUseVisitorContext() {
        VisitorContextHolder.set(new VisitorContext(2048L, "server-key", "Bearer wf-visitor-v1.server"));
        PortfolioEntity portfolio = publishedPortfolioWithScheduleComponent();
        when(portfolioEntityMapper.selectOne(any())).thenReturn(portfolio);
        when(slotDefinitionEntityMapper.selectById(12L)).thenReturn(slotDefinition(12L, "午宴"));
        when(scheduleEntityMapper.selectOne(any())).thenReturn(null);
        PortfolioScheduleQueryRequest request = new PortfolioScheduleQueryRequest();
        request.setVisitorKey("attacker-key");
        request.setComponentKey("c_schedule");
        request.setQueriedDate(LocalDate.of(2026, 7, 18));
        request.setSlotDefinitionId(12L);
        request.setIdempotencyKey("schedule-submit-ctx");

        service().submitScheduleQuery("PF001", request);

        verify(portfolioVisitService).recordScheduleQuery(
                eq(portfolio),
                eq("server-key"),
                eq(LocalDate.of(2026, 7, 18)),
                any(),
                eq("schedule-submit-ctx")
        );
        assertThat(request.getVisitorKey()).isEqualTo("server-key");
    }

    @Test
    void recordEventShouldIgnoreRequestVisitorKeyAndUseVisitorContext() {
        VisitorContextHolder.set(new VisitorContext(2048L, "server-key", "Bearer wf-visitor-v1.server"));
        PortfolioEntity portfolio = publishedPortfolio();
        when(portfolioEntityMapper.selectOne(any())).thenReturn(portfolio);
        VisitorPortfolioEventRequest request = new VisitorPortfolioEventRequest();
        request.setVisitorKey("attacker-key");
        request.setEventType("WORK_VIEWED");
        request.setIdempotencyKey("event-ctx");

        service().recordEvent("PF001", request);

        assertThat(request.getVisitorKey()).isEqualTo("server-key");
        verify(portfolioVisitService).recordEvent(portfolio, 2048L, request);
    }

    @Test
    void ownerSelfRecordEventShouldSkipVisitStatistics() {
        VisitorContextHolder.set(new VisitorContext(2048L, "server-key", "Bearer wf-visitor-v1.server"));
        PortfolioEntity portfolio = publishedPortfolio();
        when(portfolioEntityMapper.selectOne(any())).thenReturn(portfolio);
        VisitorEntity visitor = new VisitorEntity();
        visitor.setId(2048L);
        visitor.setOpenid("openid-owner");
        when(visitorService.findById(2048L)).thenReturn(visitor);
        when(ownerSelfVisitService.isOwnerSelfVisitor(
                eq(7L),
                eq("openid-owner"),
                eq(88L),
                eq(2048L),
                eq(VisitEventTypeDict.WORK_VIEWED.getCode())
        )).thenReturn(true);
        VisitorPortfolioEventRequest request = new VisitorPortfolioEventRequest();
        request.setVisitorKey("attacker-key");
        request.setEventType(VisitEventTypeDict.WORK_VIEWED.getCode());
        request.setIdempotencyKey("event-owner-1");

        service().recordEvent("PF001", request);

        assertThat(request.getVisitorKey()).isEqualTo("server-key");
        verify(portfolioVisitService, never()).recordEvent(any(), any(Long.class), any());
    }

    private VisitorPortfolioService service() {
        return new VisitorPortfolioService(
                portfolioEntityMapper,
                scheduleEntityMapper,
                slotDefinitionEntityMapper,
                portfolioVisitService,
                portfolioRenderService,
                visitorService,
                visitorAuthTokenService,
                scheduleQueryRecordEntityMapper,
                ownerSelfVisitService,
                org.mockito.Mockito.mock(PointBalanceGateService.class),
                performanceLogger()
        );
    }

    /** 创建关闭正常采样的测试耗时日志器。 */
    private PortfolioOpenPerformanceLogger performanceLogger() {
        com.jxc.wefolio.config.PortfolioOpenPerformanceProperties properties =
                new com.jxc.wefolio.config.PortfolioOpenPerformanceProperties();
        properties.setNormalSampleRate(0D);
        return new PortfolioOpenPerformanceLogger(properties);
    }

    private PortfolioEntity publishedPortfolio() {
        PortfolioEntity portfolio = new PortfolioEntity();
        portfolio.setId(88L);
        portfolio.setShareCode("PF001");
        portfolio.setOwnerType(PortfolioOwnerTypeDict.USER.getCode());
        portfolio.setOwnerId(7L);
        portfolio.setTemplateType(PortfolioTemplateTypeDict.STANDARD.getCode());
        portfolio.setStatus(PortfolioStatusDict.ACTIVE.getCode());
        portfolio.setPublicationStatus(PortfolioPublicationStatusDict.PUBLISHED.getCode());
        portfolio.setPublishedRevision(3);
        portfolio.setPublishedConfigJson("{\"schemaVersion\":\"standard-personal-v1\",\"share\":{\"title\":\"林安婚礼司仪\"},\"components\":[]}");
        return portfolio;
    }

    private PortfolioEntity publishedPortfolioWithScheduleComponent() {
        PortfolioEntity portfolio = publishedPortfolio();
        portfolio.setPublishedConfigJson("""
                {"schemaVersion":"standard-personal-v1","share":{"title":"林安婚礼司仪"},"components":[
                  {"componentKey":"c_schedule","componentType":"SCHEDULE_QUERY","sortOrder":1000,"enabled":true,
                   "config":{"displayMode":"MODAL_CALENDAR","queryRange":{"type":"UNLIMITED"}}}
                ]}
                """);
        return portfolio;
    }

    private SlotDefinitionEntity slotDefinition(Long id, String name) {
        SlotDefinitionEntity entity = new SlotDefinitionEntity();
        entity.setId(id);
        entity.setUserId(7L);
        entity.setName(name);
        entity.setStartTime(LocalTime.of(10, 0));
        entity.setEndTime(LocalTime.of(14, 0));
        entity.setColor("#2d5f9a");
        entity.setStatus(SlotDefinitionStatusDict.ACTIVE.getCode());
        return entity;
    }

    private ScheduleEntity schedule(
            Long id,
            Long slotDefinitionId,
            LocalDate scheduleDate,
            String slotName,
            String status
    ) {
        ScheduleEntity entity = new ScheduleEntity();
        entity.setId(id);
        entity.setUserId(7L);
        entity.setScheduleDate(scheduleDate);
        entity.setSlotDefinitionId(slotDefinitionId);
        entity.setSlotNameSnapshot(slotName);
        entity.setStartTimeSnapshot(LocalTime.of(10, 0));
        entity.setEndTimeSnapshot(LocalTime.of(14, 0));
        entity.setColorSnapshot("#2d5f9a");
        entity.setStatus(status);
        return entity;
    }

    private ScheduleEntity scheduleWithNullableSnapshots(Long id, Long slotDefinitionId, LocalDate scheduleDate) {
        ScheduleEntity entity = new ScheduleEntity();
        entity.setId(id);
        entity.setUserId(7L);
        entity.setScheduleDate(scheduleDate);
        entity.setSlotDefinitionId(slotDefinitionId);
        return entity;
    }
}
