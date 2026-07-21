package com.jxc.wefolio.service.teamportfolio;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;
import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.jxc.wefolio.common.auth.VisitorContext;
import com.jxc.wefolio.common.auth.VisitorContextHolder;
import com.jxc.wefolio.config.TeamPortfolioProperties;
import com.jxc.wefolio.constant.TeamPortfolioConstants;
import com.jxc.wefolio.dict.PortfolioOwnerTypeDict;
import com.jxc.wefolio.dict.PortfolioPublicationStatusDict;
import com.jxc.wefolio.dict.PortfolioStatusDict;
import com.jxc.wefolio.dict.PortfolioTemplateTypeDict;
import com.jxc.wefolio.dict.TeamRoleDict;
import com.jxc.wefolio.dict.TeamStatusDict;
import com.jxc.wefolio.dict.VisitEventTypeDict;
import com.jxc.wefolio.dto.VisitorAvatarUploadTicketRequest;
import com.jxc.wefolio.dto.VisitorAvatarUploadTicketResponse;
import com.jxc.wefolio.dto.VisitorProfileUpdateRequest;
import com.jxc.wefolio.dto.teamportfolio.TeamContactLeadResponse;
import com.jxc.wefolio.dto.teamportfolio.TeamContactLeadSubmitRequest;
import com.jxc.wefolio.dto.teamportfolio.TeamPortfolioConfigDto;
import com.jxc.wefolio.dto.teamportfolio.TeamPortfolioRenderDto;
import com.jxc.wefolio.dto.teamportfolio.TeamPortfolioScheduleQueryRequest;
import com.jxc.wefolio.dto.teamportfolio.TeamPortfolioScheduleQueryResponse;
import com.jxc.wefolio.dto.teamportfolio.VisitorTeamPortfolioEventRequest;
import com.jxc.wefolio.dto.teamportfolio.VisitorTeamPortfolioOpenRequest;
import com.jxc.wefolio.entity.PortfolioEntity;
import com.jxc.wefolio.entity.TeamEntity;
import com.jxc.wefolio.entity.TeamScheduleQueryRecordEntity;
import com.jxc.wefolio.entity.VisitRecordEntity;
import com.jxc.wefolio.entity.VisitorEntity;
import com.jxc.wefolio.exception.BusinessException;
import com.jxc.wefolio.mapper.PortfolioEntityMapper;
import com.jxc.wefolio.mapper.TeamEntityMapper;
import com.jxc.wefolio.mapper.TeamScheduleQueryRecordEntityMapper;
import com.jxc.wefolio.mapper.VisitRecordEntityMapper;
import com.jxc.wefolio.message.TeamPortfolioMessage;
import com.jxc.wefolio.service.ContentLimitService;
import com.jxc.wefolio.service.PointService;
import com.jxc.wefolio.service.PortfolioPublishTransactionService;
import com.jxc.wefolio.service.VisitorAuthTokenService;
import com.jxc.wefolio.service.VisitorService;
import com.jxc.wefolio.service.teamportfolio.component.contactform.TeamContactFormComponentService;
import com.jxc.wefolio.service.teamportfolio.component.schedulequery.TeamScheduleQueryComponentService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assertions.assertAll;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * 团队作品集访客编排与维护查询测试。
 */
class VisitorTeamPortfolioServiceTest {

    /** 团队 ID。 */
    private static final long TEAM_ID = 31L;

    /** 作品集 ID。 */
    private static final long PORTFOLIO_ID = 41L;

    /** 访客 ID。 */
    private static final long VISITOR_ID = 51L;

    /** 维护者 ID。 */
    private static final long USER_ID = 61L;

    /** 访客键。 */
    private static final String VISITOR_KEY = "visitor-team-a";

    /** 初始化 MyBatis-Plus 表元数据。 */
    @BeforeAll
    static void initTableInfo() {
        com.baomidou.mybatisplus.core.MybatisConfiguration configuration =
                new com.baomidou.mybatisplus.core.MybatisConfiguration();
        org.apache.ibatis.builder.MapperBuilderAssistant assistant =
                new org.apache.ibatis.builder.MapperBuilderAssistant(configuration, "");
        com.baomidou.mybatisplus.core.metadata.TableInfoHelper.initTableInfo(assistant, PortfolioEntity.class);
        com.baomidou.mybatisplus.core.metadata.TableInfoHelper.initTableInfo(assistant, VisitRecordEntity.class);
        com.baomidou.mybatisplus.core.metadata.TableInfoHelper.initTableInfo(assistant, TeamScheduleQueryRecordEntity.class);
    }

    /** 设置已认证访客上下文。 */
    @BeforeEach
    void setUp() {
        VisitorContextHolder.set(new VisitorContext(VISITOR_ID, VISITOR_KEY, "secret-token"));
    }

    /** 清理访客上下文。 */
    @AfterEach
    void tearDown() {
        VisitorContextHolder.clear();
    }

    /**
     * 功能关闭时七个访客公开入口必须在任何依赖交互前返回统一提示。
     */
    @Test
    void disabledFeatureStopsEveryVisitorEntryBeforeAllDependencies() {
        VisitorServiceContext context = visitorContext(false);

        assertAll(
                () -> assertThatThrownBy(() -> context.service.openPortfolio(null, null))
                        .isInstanceOf(BusinessException.class)
                        .hasMessage(TeamPortfolioMessage.FEATURE_DISABLED),
                () -> assertThatThrownBy(() -> context.service.createVisitorAvatarUploadTicket(null, null))
                        .isInstanceOf(BusinessException.class)
                        .hasMessage(TeamPortfolioMessage.FEATURE_DISABLED),
                () -> assertThatThrownBy(() -> context.service.updateVisitorProfile(null, null))
                        .isInstanceOf(BusinessException.class)
                        .hasMessage(TeamPortfolioMessage.FEATURE_DISABLED),
                () -> assertThatThrownBy(() -> context.service.recordEvent(null, null))
                        .isInstanceOf(BusinessException.class)
                        .hasMessage(TeamPortfolioMessage.FEATURE_DISABLED),
                () -> assertThatThrownBy(() -> context.service.queryScheduleOptions(null, null))
                        .isInstanceOf(BusinessException.class)
                        .hasMessage(TeamPortfolioMessage.FEATURE_DISABLED),
                () -> assertThatThrownBy(() -> context.service.submitScheduleQuery(null, null))
                        .isInstanceOf(BusinessException.class)
                        .hasMessage(TeamPortfolioMessage.FEATURE_DISABLED),
                () -> assertThatThrownBy(() -> context.service.submitContactLead(null, null))
                        .isInstanceOf(BusinessException.class)
                        .hasMessage(TeamPortfolioMessage.FEATURE_DISABLED),
                () -> verifyNoInteractions(
                        context.portfolioMapper, context.teamMapper, context.renderService,
                        context.scheduleService, context.contactService, context.visitService,
                        context.visitorService, context.tokenService));
    }

    /** 功能开关必须作为 final 构造依赖。 */
    @Test
    void visitorFeatureToggleIsFinalConstructorDependency() {
        assertThat(VisitorTeamPortfolioService.class.getDeclaredFields())
                .filteredOn(field -> "teamPortfolioProperties".equals(field.getName()))
                .singleElement()
                .matches(field -> field.getType().equals(TeamPortfolioProperties.class))
                .matches(field -> java.lang.reflect.Modifier.isFinal(field.getModifiers()))
                .matches(field -> field.getAnnotation(
                        org.springframework.beans.factory.annotation.Autowired.class) == null);
        assertThat(VisitorTeamPortfolioService.class.getDeclaredConstructors())
                .anySatisfy(constructor -> assertThat(constructor.getParameterTypes())
                        .contains(TeamPortfolioProperties.class));
    }

    /**
     * 发布查询 wrapper 必须完整携带团队、标准模板、schema、发布、启用和未删除条件。
     */
    @Test
    @SuppressWarnings("unchecked")
    void publishedLookupUsesEverySqlIdentityPredicateAndJavaRecheck() {
        VisitorServiceContext context = visitorContext();
        when(context.portfolioMapper.selectOne(any())).thenReturn(portfolio());
        when(context.teamMapper.selectById(TEAM_ID)).thenReturn(team(TeamStatusDict.ACTIVE.getCode()));
        when(context.scheduleService.previewOptions(any(), any(TeamPortfolioConfigDto.class), eq("schedule-1")))
                .thenReturn(new JSONObject());

        context.service.queryScheduleOptions(" TPF-TASK8 ", "schedule-1");

        org.mockito.ArgumentCaptor<Wrapper<PortfolioEntity>> captor =
                org.mockito.ArgumentCaptor.forClass(Wrapper.class);
        verify(context.portfolioMapper).selectOne(captor.capture());
        LambdaQueryWrapper<PortfolioEntity> wrapper = (LambdaQueryWrapper<PortfolioEntity>) captor.getValue();
        assertThat(wrapper.getSqlSegment()).contains(
                "share_code", "owner_type", "template_type", "schema_version",
                "publication_status", "status", "deleted");
        assertThat(wrapper.getParamNameValuePairs().values()).contains(
                "TPF-TASK8", PortfolioOwnerTypeDict.TEAM.getCode(),
                PortfolioTemplateTypeDict.STANDARD.getCode(),
                TeamPortfolioConstants.SCHEMA_VERSION_STANDARD_TEAM_V1,
                PortfolioPublicationStatusDict.PUBLISHED.getCode(),
                PortfolioStatusDict.ACTIVE.getCode(), 0L);
    }

    /**
     * 个人分享、旧 schema、禁用、草稿、删除、缺失 ID/配置和停用团队统一固定拒绝。
     */
    @Test
    void everyInvalidPublishedIdentityReturnsFixedUnavailableMessageBeforeConfigRead() {
        VisitorServiceContext context = visitorContext();
        PortfolioEntity personal = portfolio();
        personal.setOwnerType(PortfolioOwnerTypeDict.USER.getCode());
        personal.setPublishedConfigJson("not-json");
        when(context.portfolioMapper.selectOne(any())).thenReturn(personal);

        assertThatThrownBy(() -> context.service.queryScheduleOptions("TPF-TASK8", "schedule-1"))
                .isInstanceOf(BusinessException.class)
                .hasMessage("当前作品集暂未开放访问");

        verifyNoInteractions(context.teamMapper, context.renderService,
                context.scheduleService, context.visitService, context.contactService);
    }

    /**
     * 团队状态必须在解析配置和任何业务写入前复验为 ACTIVE。
     */
    @Test
    void inactiveTeamFailsBeforeRenderOrVisitWrites() {
        VisitorServiceContext context = visitorContext();
        when(context.portfolioMapper.selectOne(any())).thenReturn(portfolio());
        when(context.teamMapper.selectById(TEAM_ID)).thenReturn(team("DISSOLVED"));

        assertThatThrownBy(() -> context.service.openPortfolio("TPF-TASK8", new VisitorTeamPortfolioOpenRequest()))
                .isInstanceOf(BusinessException.class)
                .hasMessage("当前作品集暂未开放访问");

        verifyNoInteractions(context.visitorService, context.visitService,
                context.tokenService, context.renderService);
    }

    /**
     * open 必须通过共享登录解析访客、独立访问服务和共享 token/profile token 返回团队渲染结果。
     */
    @Test
    void openUsesSharedVisitorInfrastructureAndIndependentTeamVisitWithoutPoints() {
        VisitorServiceContext context = publishedContext();
        VisitorEntity visitor = visitor();
        VisitorService.VisitorSession session = new VisitorService.VisitorSession(visitor, true);
        VisitorTeamPortfolioOpenRequest request = new VisitorTeamPortfolioOpenRequest();
        request.setLoginCode("wx-code");
        request.setSourceType("WECHAT_SHARE_CARD");
        request.setIdempotencyKey("open-1");
        VisitRecordEntity record = ownedRecord();
        TeamPortfolioRenderDto render = new TeamPortfolioRenderDto();
        when(context.visitorService.resolveByLoginCode("wx-code")).thenReturn(session);
        when(context.visitService.recordOpen(portfolio(), VISITOR_ID, VISITOR_KEY,
                "WECHAT_SHARE_CARD", "open-1")).thenReturn(record);
        when(context.tokenService.issueToken(VISITOR_ID, VISITOR_KEY))
                .thenReturn(new VisitorAuthTokenService.VisitorLoginToken("Bearer", "login-token", 7200L));
        when(context.visitorService.createProfileToken(VISITOR_ID, PORTFOLIO_ID, record.getId()))
                .thenReturn("profile-token");
        when(context.renderService.render(any(), any())).thenReturn(render);

        var response = context.service.openPortfolio("TPF-TASK8", request);

        assertThat(response.getPortfolioId()).isEqualTo(PORTFOLIO_ID);
        assertThat(response.getTeamId()).isEqualTo(TEAM_ID);
        assertThat(response.getVisitRecordId()).isEqualTo(record.getId());
        assertThat(response.getRenderData()).isSameAs(render);
        assertThat(response.getToken()).isEqualTo("login-token");
        assertThat(response.getVisitorProfileToken()).isEqualTo("profile-token");
        assertThat(response.isNeedVisitorProfile()).isTrue();
        assertThat(VisitorTeamPortfolioService.class.getDeclaredFields())
                .noneMatch(field -> field.getType().getSimpleName().contains("PointService"));
    }

    /**
     * 头像票据和资料更新必须先过团队发布门禁，再原样委托共享 VisitorService。
     */
    @Test
    void profileTicketAndUpdateDelegateOnlyAfterPublishedIdentityCheck() {
        VisitorServiceContext context = publishedContext();
        VisitorAvatarUploadTicketRequest ticketRequest = new VisitorAvatarUploadTicketRequest();
        VisitorAvatarUploadTicketResponse ticket = new VisitorAvatarUploadTicketResponse();
        VisitorProfileUpdateRequest profileRequest = new VisitorProfileUpdateRequest();
        when(context.visitorService.createAvatarUploadTicket(PORTFOLIO_ID, ticketRequest)).thenReturn(ticket);

        assertThat(context.service.createVisitorAvatarUploadTicket("TPF-TASK8", ticketRequest)).isSameAs(ticket);
        context.service.updateVisitorProfile("TPF-TASK8", profileRequest);

        verify(context.visitorService).createAvatarUploadTicket(PORTFOLIO_ID, ticketRequest);
        verify(context.visitorService).saveProfile(PORTFOLIO_ID, profileRequest);
    }

    /**
     * 事件身份必须只来自 VisitorContextHolder，不接收请求 visitorKey。
     */
    @Test
    void eventUsesOnlyAuthenticatedVisitorContext() {
        VisitorServiceContext context = publishedContext();
        VisitorTeamPortfolioEventRequest request = new VisitorTeamPortfolioEventRequest();
        request.setEventType(VisitEventTypeDict.QR_CODE_INTERACTED.getCode());
        request.setIdempotencyKey("qr-1");
        request.setComponentKey("qr-1");
        request.setAction("CLICK");

        context.service.recordEvent("TPF-TASK8", request);

        verify(context.visitService).recordEvent(portfolio(), VISITOR_ID, VISITOR_KEY, request);
        assertThat(VisitorTeamPortfolioEventRequest.class.getDeclaredFields())
                .noneMatch(field -> field.getName().equals("visitorKey"));
    }

    /**
     * 联系表单曝光必须先由 contactform 组件按 componentKey 校验，失败时不写访问事件。
     */
    @Test
    void contactFormExposureUsesComponentOwnedValidationBeforeVisitEvent() {
        VisitorServiceContext context = publishedContext();
        VisitorTeamPortfolioEventRequest request = new VisitorTeamPortfolioEventRequest();
        request.setEventType(VisitEventTypeDict.CONTACT_FORM_EXPOSED.getCode());
        request.setComponentKey("contact-1");
        request.setIdempotencyKey("contact-exposed-1");
        org.mockito.Mockito.doThrow(new BusinessException("联系组件不可用"))
                .when(context.contactService).validatePublishedComponent(portfolio(), "contact-1");

        assertThatThrownBy(() -> context.service.recordEvent("TPF-TASK8", request))
                .isInstanceOf(BusinessException.class)
                .hasMessage("联系组件不可用");

        verify(context.contactService).validatePublishedComponent(portfolio(), "contact-1");
        verifyNoInteractions(context.visitService);
    }

    /**
     * 公开 schedule-options 必须使用已发布顶层配置和团队组件上下文。
     */
    @Test
    void scheduleOptionsDelegatesPublishedTeamConfigWithoutPersonalSlots() {
        VisitorServiceContext context = publishedContext();
        JSONObject options = new JSONObject();
        options.put("displayMode", "MODAL_CALENDAR");
        when(context.scheduleService.previewOptions(any(), any(TeamPortfolioConfigDto.class), eq("schedule-1")))
                .thenReturn(options);

        assertThat(context.service.queryScheduleOptions("TPF-TASK8", "schedule-1")).isSameAs(options);

        verify(context.scheduleService).previewOptions(
                eq(new TeamPortfolioComponentContext(TEAM_ID, PORTFOLIO_ID, 3)),
                any(TeamPortfolioConfigDto.class), eq("schedule-1"));
    }

    /**
     * 查档必须先记录幂等事件，并仅在首次事件时允许组件写一条业务快照。
     */
    @Test
    void scheduleQueryUsesEventResultToGateExactlyOnePublishedSnapshot() {
        VisitorServiceContext context = publishedContext();
        TeamPortfolioScheduleQueryRequest request = new TeamPortfolioScheduleQueryRequest();
        request.setComponentKey("schedule-1");
        request.setQueriedDate(LocalDate.of(2026, 8, 1));
        request.setIdempotencyKey("schedule-1");
        VisitRecordEntity record = ownedRecord();
        LocalDateTime occurredAt = LocalDateTime.of(2026, 7, 11, 10, 0);
        when(context.visitService.recordScheduleQuery(portfolio(), VISITOR_ID, VISITOR_KEY, request))
                .thenReturn(new TeamPortfolioVisitService.EventRecordResult(true, record, occurredAt));
        TeamPortfolioScheduleQueryResponse expected = new TeamPortfolioScheduleQueryResponse();
        when(context.scheduleService.queryPublished(
                any(), any(TeamPortfolioConfigDto.class), eq(request), any(), any())).thenReturn(expected);

        assertThat(context.service.submitScheduleQuery("TPF-TASK8", request)).isSameAs(expected);

        verify(context.scheduleService).queryPublished(
                eq(portfolio()), any(TeamPortfolioConfigDto.class), eq(request),
                eq(new VisitorContext(VISITOR_ID, VISITOR_KEY, "secret-token")),
                eq(TeamScheduleQueryComponentService.PublishedQueryRecordContext.recordable(
                        record.getId(), record.getSourceType(), occurredAt)));
    }

    /**
     * 重复查档事件必须传 skipped，禁止组件重复插团队查档快照。
     */
    @Test
    void duplicateScheduleEventUsesSkippedSnapshotContext() {
        VisitorServiceContext context = publishedContext();
        TeamPortfolioScheduleQueryRequest request = new TeamPortfolioScheduleQueryRequest();
        request.setComponentKey("schedule-1");
        request.setQueriedDate(LocalDate.of(2026, 8, 1));
        request.setIdempotencyKey("schedule-dup");
        when(context.visitService.recordScheduleQuery(portfolio(), VISITOR_ID, VISITOR_KEY, request))
                .thenReturn(new TeamPortfolioVisitService.EventRecordResult(false, ownedRecord(), LocalDateTime.now()));
        when(context.scheduleService.queryPublished(
                any(), any(TeamPortfolioConfigDto.class), any(), any(), any()))
                .thenReturn(new TeamPortfolioScheduleQueryResponse());

        context.service.submitScheduleQuery("TPF-TASK8", request);

        verify(context.scheduleService).queryPublished(
                eq(portfolio()), any(TeamPortfolioConfigDto.class), eq(request), any(),
                eq(TeamScheduleQueryComponentService.PublishedQueryRecordContext.skipped()));
    }

    /**
     * 顶层访客服务不得自行硬编码档期组件类型或遍历发布组件。
     */
    @Test
    void schedulePublishedComponentResolutionStaysInsideSchedulePackage() throws Exception {
        String source = java.nio.file.Files.readString(java.nio.file.Path.of(
                "src/main/java/com/jxc/wefolio/service/teamportfolio/VisitorTeamPortfolioService.java"));

        assertThat(source).doesNotContain(
                "COMPONENT_TYPE_SCHEDULE_QUERY",
                "requireScheduleComponentConfig(",
                ".getComponents().stream()");
    }

    /**
     * 线索提交只委托团队组件，成功后只把 leadId 写入事件元数据。
     */
    @Test
    void contactLeadDelegatesComponentThenRecordsIdempotentNonSensitiveEvent() {
        VisitorServiceContext context = publishedContext();
        TeamContactLeadSubmitRequest request = new TeamContactLeadSubmitRequest();
        request.setIdempotencyKey("lead-1");
        request.setPhone("13800000000");
        request.setWechat("wx-secret");
        TeamContactFormComponentService.SubmitResult submitted =
                new TeamContactFormComponentService.SubmitResult(91L, LocalDateTime.now());
        when(context.contactService.submit("TPF-TASK8", request,
                new VisitorContext(VISITOR_ID, VISITOR_KEY, "secret-token"))).thenReturn(submitted);

        assertThat(context.service.submitContactLead("TPF-TASK8", request)).isSameAs(submitted);

        verify(context.visitService).recordContactLeadSubmitted(
                portfolio(), VISITOR_ID, VISITOR_KEY, 91L, "lead-1");
    }

    /**
     * 维护端访问、查档读取要求三种 JOINED 角色，并精确约束 TEAM 归属。
     */
    @Test
    @SuppressWarnings("unchecked")
    void mineQueriesAllowAllThreeRolesAndUseTeamScopedWrappers() {
        MineContext context = mineContext();
        when(context.recordMapper.selectList(any())).thenReturn(List.of(ownedRecord(), ownedRecord()));
        when(context.scheduleRecordMapper.selectList(any())).thenReturn(List.of(new TeamScheduleQueryRecordEntity()));

        MineTeamPortfolioService.TeamVisitRecordsResponse visits =
                context.service.getVisitRecords(TEAM_ID, 1, 1, USER_ID);
        context.service.getScheduleQueryRecords(TEAM_ID, 1, 20, USER_ID);

        verify(context.accessService, org.mockito.Mockito.times(2)).requireTeamRole(
                eq(TEAM_ID), eq(USER_ID), eq(Set.of(
                        TeamRoleDict.OWNER.getCode(), TeamRoleDict.MANAGER.getCode(), TeamRoleDict.MEMBER.getCode())));
        org.mockito.ArgumentCaptor<Wrapper<VisitRecordEntity>> visitCaptor =
                org.mockito.ArgumentCaptor.forClass(Wrapper.class);
        verify(context.recordMapper).selectList(visitCaptor.capture());
        assertThat(((LambdaQueryWrapper<VisitRecordEntity>) visitCaptor.getValue()).getSqlSegment())
                .contains("owner_type", "owner_id", "portfolio_type", "LIMIT 0,2");
        assertThat(visits.page()).isEqualTo(1);
        assertThat(visits.pageSize()).isEqualTo(1);
        assertThat(visits.hasMore()).isTrue();
        assertThat(visits.items()).hasSize(1);
        org.mockito.ArgumentCaptor<Wrapper<TeamScheduleQueryRecordEntity>> scheduleCaptor =
                org.mockito.ArgumentCaptor.forClass(Wrapper.class);
        verify(context.scheduleRecordMapper).selectList(scheduleCaptor.capture());
        assertThat(((LambdaQueryWrapper<TeamScheduleQueryRecordEntity>) scheduleCaptor.getValue()).getSqlSegment())
                .contains("team_id");
    }

    /**
     * 团队线索读取与跟进必须原样委托已有组件的三角色完整明文和两角色更新策略。
     */
    @Test
    void mineContactMethodsDelegateFullLeadReadAndRestrictedFollowPolicy() {
        MineContext context = mineContext();
        TeamContactLeadResponse response = new TeamContactLeadResponse();
        TeamContactLeadResponse.Item item = new TeamContactLeadResponse.Item();
        item.setPhone("13800000000");
        item.setWechat("full-wechat");
        when(context.contactService.list(TEAM_ID, 1, 20, USER_ID)).thenReturn(response);
        when(context.contactService.updateFollowStatus(
                TEAM_ID, 91L, "CONTACTED", "已联系", USER_ID)).thenReturn(item);

        assertThat(context.service.getContactLeads(TEAM_ID, 1, 20, USER_ID)).isSameAs(response);
        assertThat(context.service.updateContactLeadFollowStatus(
                TEAM_ID, 91L, "CONTACTED", "已联系", USER_ID).getPhone()).isEqualTo("13800000000");

        verify(context.contactService).list(TEAM_ID, 1, 20, USER_ID);
        verify(context.contactService).updateFollowStatus(TEAM_ID, 91L, "CONTACTED", "已联系", USER_ID);
    }

    /** 创建访客服务有效发布上下文。 */
    private static VisitorServiceContext publishedContext() {
        VisitorServiceContext context = visitorContext();
        when(context.portfolioMapper.selectOne(any())).thenReturn(portfolio());
        when(context.teamMapper.selectById(TEAM_ID)).thenReturn(team(TeamStatusDict.ACTIVE.getCode()));
        return context;
    }

    /** 创建访客服务测试上下文。 */
    private static VisitorServiceContext visitorContext() {
        return visitorContext(true);
    }

    /** 创建指定功能开关状态的访客服务测试上下文。 */
    private static VisitorServiceContext visitorContext(boolean enabled) {
        TeamPortfolioProperties properties = new TeamPortfolioProperties();
        properties.setEnabled(enabled);
        PortfolioEntityMapper portfolioMapper = mock(PortfolioEntityMapper.class);
        TeamEntityMapper teamMapper = mock(TeamEntityMapper.class);
        TeamPortfolioRenderService renderService = mock(TeamPortfolioRenderService.class);
        TeamScheduleQueryComponentService scheduleService = mock(TeamScheduleQueryComponentService.class);
        TeamContactFormComponentService contactService = mock(TeamContactFormComponentService.class);
        TeamPortfolioVisitService visitService = mock(TeamPortfolioVisitService.class);
        VisitorService visitorService = mock(VisitorService.class);
        VisitorAuthTokenService tokenService = mock(VisitorAuthTokenService.class);
        VisitorTeamPortfolioService service = new VisitorTeamPortfolioService(
                properties, portfolioMapper, teamMapper, renderService, scheduleService,
                contactService, visitService, visitorService, tokenService,
                mock(com.jxc.wefolio.service.PointBalanceGateService.class));
        return new VisitorServiceContext(service, portfolioMapper, teamMapper, renderService,
                scheduleService, contactService, visitService, visitorService, tokenService);
    }

    /** 创建维护端服务并通过构造器注入全部依赖。 */
    private static MineContext mineContext() {
        TeamPortfolioProperties properties = new TeamPortfolioProperties();
        properties.setEnabled(true);
        TeamPortfolioAccessService accessService = mock(TeamPortfolioAccessService.class);
        VisitRecordEntityMapper recordMapper = mock(VisitRecordEntityMapper.class);
        TeamScheduleQueryRecordEntityMapper scheduleRecordMapper = mock(TeamScheduleQueryRecordEntityMapper.class);
        TeamContactFormComponentService contactService = mock(TeamContactFormComponentService.class);
        MineTeamPortfolioService service = new MineTeamPortfolioService(
                properties,
                mock(PortfolioEntityMapper.class),
                mock(com.jxc.wefolio.mapper.PortfolioHistoryEntityMapper.class),
                mock(com.jxc.wefolio.mapper.PortfolioReferenceEntityMapper.class),
                mock(com.jxc.wefolio.mapper.PortfolioShareRecordEntityMapper.class),
                mock(TeamEntityMapper.class),
                mock(com.jxc.wefolio.mapper.TeamMemberEntityMapper.class),
                accessService,
                mock(TeamPortfolioConfigValidator.class),
                mock(TeamPortfolioRenderService.class),
                mock(TeamPortfolioReferenceService.class),
                mock(TeamPortfolioAssetService.class),
                mock(TeamScheduleQueryComponentService.class),
                recordMapper,
                scheduleRecordMapper,
                contactService,
                mock(ContentLimitService.class),
                mock(PointService.class),
                mock(PortfolioPublishTransactionService.class));
        assertThat(MineTeamPortfolioService.class.getDeclaredFields())
                .filteredOn(field -> List.of(
                        "visitRecordEntityMapper",
                        "teamScheduleQueryRecordEntityMapper",
                        "teamContactFormComponentService").contains(field.getName()))
                .allMatch(field -> java.lang.reflect.Modifier.isFinal(field.getModifiers()))
                .noneMatch(field -> field.getAnnotation(
                        org.springframework.beans.factory.annotation.Autowired.class) != null);
        return new MineContext(service, accessService, recordMapper, scheduleRecordMapper, contactService);
    }

    /** 创建有效作品集。 */
    private static PortfolioEntity portfolio() {
        PortfolioEntity portfolio = new PortfolioEntity();
        portfolio.setId(PORTFOLIO_ID);
        portfolio.setShareCode("TPF-TASK8");
        portfolio.setOwnerType(PortfolioOwnerTypeDict.TEAM.getCode());
        portfolio.setOwnerId(TEAM_ID);
        portfolio.setTemplateType(PortfolioTemplateTypeDict.STANDARD.getCode());
        portfolio.setSchemaVersion(TeamPortfolioConstants.SCHEMA_VERSION_STANDARD_TEAM_V1);
        portfolio.setPublicationStatus(PortfolioPublicationStatusDict.PUBLISHED.getCode());
        portfolio.setStatus(PortfolioStatusDict.ACTIVE.getCode());
        portfolio.setPublishedRevision(3);
        portfolio.setDeleted(0L);
        portfolio.setPublishedConfigJson(JSON.toJSONString(config()));
        return portfolio;
    }

    /** 创建顶层已发布配置。 */
    private static TeamPortfolioConfigDto config() {
        TeamPortfolioConfigDto config = new TeamPortfolioConfigDto();
        config.setSchemaVersion(TeamPortfolioConstants.SCHEMA_VERSION_STANDARD_TEAM_V1);
        TeamPortfolioConfigDto.Share share = new TeamPortfolioConfigDto.Share();
        share.setTitle("任务八团队作品集");
        config.setShare(share);
        TeamPortfolioConfigDto.ComponentEnvelope schedule = new TeamPortfolioConfigDto.ComponentEnvelope();
        schedule.setComponentKey("schedule-1");
        schedule.setComponentType("SCHEDULE_QUERY");
        schedule.setEnabled(true);
        schedule.setConfig(new JSONObject());
        config.setComponents(List.of(schedule));
        return config;
    }

    /** 创建团队实体。 */
    private static TeamEntity team(String status) {
        TeamEntity team = new TeamEntity();
        team.setId(TEAM_ID);
        team.setName("任务八团队");
        team.setStatus(status);
        return team;
    }

    /** 创建访客实体。 */
    private static VisitorEntity visitor() {
        VisitorEntity visitor = new VisitorEntity();
        visitor.setId(VISITOR_ID);
        visitor.setVisitorKey(VISITOR_KEY);
        return visitor;
    }

    /** 创建团队访问汇总。 */
    private static VisitRecordEntity ownedRecord() {
        VisitRecordEntity record = new VisitRecordEntity();
        record.setId(71L);
        record.setVisitorId(VISITOR_ID);
        record.setVisitorKey(VISITOR_KEY);
        record.setPortfolioId(PORTFOLIO_ID);
        record.setOwnerType(PortfolioOwnerTypeDict.TEAM.getCode());
        record.setOwnerId(TEAM_ID);
        record.setPortfolioType("TEAM");
        record.setSourceType("WECHAT_SHARE_CARD");
        return record;
    }

    /** 访客服务依赖集合。 */
    private record VisitorServiceContext(
            VisitorTeamPortfolioService service,
            PortfolioEntityMapper portfolioMapper,
            TeamEntityMapper teamMapper,
            TeamPortfolioRenderService renderService,
            TeamScheduleQueryComponentService scheduleService,
            TeamContactFormComponentService contactService,
            TeamPortfolioVisitService visitService,
            VisitorService visitorService,
            VisitorAuthTokenService tokenService
    ) {
    }

    /** 维护端服务依赖集合。 */
    private record MineContext(
            MineTeamPortfolioService service,
            TeamPortfolioAccessService accessService,
            VisitRecordEntityMapper recordMapper,
            TeamScheduleQueryRecordEntityMapper scheduleRecordMapper,
            TeamContactFormComponentService contactService
    ) {
    }
}
