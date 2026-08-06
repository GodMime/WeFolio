package com.jxc.wefolio.controller;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.jxc.wefolio.annotation.MaintainerAccess;
import com.jxc.wefolio.common.auth.AuthContext;
import com.jxc.wefolio.common.auth.AuthContextHolder;
import com.jxc.wefolio.service.teamportfolio.MineTeamPortfolioService;
import com.jxc.wefolio.service.teamportfolio.TeamPortfolioAssetService;
import com.jxc.wefolio.service.teamportfolio.TeamMemberPortfolioPreviewService;
import com.jxc.wefolio.service.teamportfolio.component.carousel.TeamCarouselComponentService;
import com.jxc.wefolio.service.teamportfolio.component.memberportfoliogrid.TeamMemberPortfolioGridComponentService;
import com.jxc.wefolio.service.teamportfolio.component.memberportfoliolist.TeamMemberPortfolioListComponentService;
import com.jxc.wefolio.service.teamportfolio.component.singlework.TeamSingleWorkComponentService;
import com.jxc.wefolio.service.teamportfolio.component.videocarousel.TeamVideoCarouselComponentService;
import com.jxc.wefolio.dto.teamportfolio.TeamVideoCarouselWorkPageResponse;
import com.jxc.wefolio.dto.teamportfolio.TeamContactLeadResponse;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.lang.reflect.Method;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** 团队作品集维护控制器契约测试。 */
class MineTeamPortfolioControllerTest {

    private static final long USER_ID = 7L;

    @BeforeEach
    void setUp() {
        AuthContextHolder.set(new AuthContext(USER_ID, "test-token"));
    }

    @AfterEach
    void tearDown() {
        AuthContextHolder.clear();
    }

    @Test
    void controllerHasMaintainerAndRestAnnotations() {
        assertThat(MineTeamPortfolioController.class).hasAnnotation(MaintainerAccess.class);
        assertThat(MineTeamPortfolioController.class).hasAnnotation(RestController.class);
    }

    @Test
    void allSpecificationMaintenanceEndpointsUseExactMethodAndUrl() {
        Map<String, String> expected = new LinkedHashMap<>();
        expected.put("GET /api/mine/team-portfolios", "list");
        expected.put("GET /api/mine/team-portfolios/maintainable-teams", "maintainableTeams");
        expected.put("GET /api/mine/team-portfolios/component-library", "componentLibrary");
        expected.put("POST /api/mine/teams/{teamId}/portfolios/standard", "createStandard");
        expected.put("GET /api/mine/team-portfolios/{portfolioId}", "detail");
        expected.put("POST /api/mine/team-portfolios/{portfolioId}/draft", "saveDraft");
        expected.put("POST /api/mine/team-portfolios/{portfolioId}/publish", "publish");
        expected.put("POST /api/mine/team-portfolios/{portfolioId}/delete", "deletePortfolio");
        expected.put("GET /api/mine/team-portfolios/{portfolioId}/preview", "preview");
        expected.put("GET /api/mine/team-portfolios/{portfolioId}/published-preview", "publishedPreview");
        expected.put("GET /api/mine/team-portfolios/{teamPortfolioId}/member-portfolios/{memberPortfolioId}/published-preview", "memberPortfolioPreview");
        expected.put("GET /api/mine/team-portfolios/{teamPortfolioId}/member-portfolios/{memberPortfolioId}/schedule-options", "memberPortfolioScheduleOptions");
        expected.put("POST /api/mine/team-portfolios/{teamPortfolioId}/member-portfolios/{memberPortfolioId}/schedule-query-preview", "memberPortfolioScheduleQueryPreview");
        expected.put("POST /api/mine/team-portfolios/{portfolioId}/asset/upload-ticket", "assetUploadTicket");
        expected.put("GET /api/mine/team-portfolios/{portfolioId}/schedule-options", "scheduleOptions");
        expected.put("POST /api/mine/team-portfolios/{portfolioId}/schedule-query-preview", "scheduleQueryPreview");
        expected.put("POST /api/mine/team-portfolios/{portfolioId}/share-records", "shareRecord");
        expected.put("GET /api/mine/team-portfolios/{portfolioId}/components/carousel/members", "carouselMembers");
        expected.put("GET /api/mine/team-portfolios/{portfolioId}/components/carousel/members/{memberUserId}/works", "carouselWorks");
        expected.put("GET /api/mine/team-portfolios/{portfolioId}/components/video-carousel/members/{memberUserId}/works", "videoCarouselWorks");
        expected.put("GET /api/mine/team-portfolios/{portfolioId}/components/single-work/members", "singleWorkMembers");
        expected.put("GET /api/mine/team-portfolios/{portfolioId}/components/single-work/members/{memberUserId}/works", "singleWorkWorks");
        expected.put("GET /api/mine/teams/{teamId}/portfolio-components/single-work/members", "teamSingleWorkMembers");
        expected.put("GET /api/mine/teams/{teamId}/portfolio-components/single-work/members/{memberUserId}/works", "teamSingleWorkWorks");
        expected.put("GET /api/mine/teams/{teamId}/portfolio-components/single-work/members/{memberUserId}/works/page", "teamSingleWorkWorksPage");
        expected.put("GET /api/mine/team-portfolios/{portfolioId}/components/member-portfolio-grid/members", "gridMembers");
        expected.put("GET /api/mine/team-portfolios/{portfolioId}/components/member-portfolio-grid/members/{memberUserId}/portfolios", "gridPortfolios");
        expected.put("GET /api/mine/team-portfolios/{portfolioId}/components/member-portfolio-list/members", "listMembers");
        expected.put("GET /api/mine/team-portfolios/{portfolioId}/components/member-portfolio-list/members/{memberUserId}/portfolios", "listPortfolios");
        expected.put("GET /api/mine/teams/{teamId}/visits", "visitRecords");
        expected.put("GET /api/mine/teams/{teamId}/schedule-queries", "scheduleQueries");
        expected.put("GET /api/mine/teams/{teamId}/contact-leads", "contactLeads");
        expected.put("POST /api/mine/teams/{teamId}/contact-leads/{leadId}/follow-status", "followStatus");

        assertThat(endpointMap()).containsExactlyInAnyOrderEntriesOf(expected);
    }

    @Test
    void endpointParametersKeepExactPathBodyAndQueryBindings() {
        Method library = method("componentLibrary");
        assertRequestParam(
                library,
                0,
                "editorSchemaRevision",
                org.springframework.web.bind.annotation.ValueConstants.DEFAULT_NONE);

        Method create = method("createStandard");
        assertThat(create.getParameters()[0].getAnnotation(PathVariable.class)).isNotNull();
        RequestBody optionalCreateBody = create.getParameters()[1].getAnnotation(RequestBody.class);
        assertThat(optionalCreateBody).isNotNull();
        assertThat(optionalCreateBody.required()).isFalse();

        Method save = method("saveDraft");
        assertThat(save.getParameters()[0].getAnnotation(PathVariable.class)).isNotNull();
        assertThat(save.getParameters()[1].getAnnotation(RequestBody.class)).isNotNull();

        Method options = method("scheduleOptions");
        assertThat(options.getParameters()[0].getAnnotation(PathVariable.class)).isNotNull();
        RequestParam componentKey = options.getParameters()[1].getAnnotation(RequestParam.class);
        RequestParam optionsScope = options.getParameters()[2].getAnnotation(RequestParam.class);
        assertThat(componentKey.value()).isEqualTo("componentKey");
        assertThat(componentKey.required()).isTrue();
        assertThat(optionsScope.value()).isEqualTo("scope");
        assertThat(optionsScope.required()).isFalse();

        Method query = method("scheduleQueryPreview");
        assertThat(query.getParameters()[0].getAnnotation(PathVariable.class)).isNotNull();
        assertThat(query.getParameters()[1].getAnnotation(RequestBody.class)).isNotNull();
        RequestParam queryScope = query.getParameters()[2].getAnnotation(RequestParam.class);
        assertThat(queryScope.value()).isEqualTo("scope");
        assertThat(queryScope.required()).isFalse();

        Method carouselWorks = method("carouselWorks");
        assertThat(carouselWorks.getParameters()[0].getAnnotation(PathVariable.class)).isNotNull();
        assertThat(carouselWorks.getParameters()[1].getAnnotation(PathVariable.class)).isNotNull();
        Method videoCarouselWorks = method("videoCarouselWorks");
        assertThat(videoCarouselWorks.getParameters()[0].getAnnotation(PathVariable.class)).isNotNull();
        assertThat(videoCarouselWorks.getParameters()[1].getAnnotation(PathVariable.class)).isNotNull();
        assertRequestParam(
                videoCarouselWorks,
                2,
                "keyword",
                org.springframework.web.bind.annotation.ValueConstants.DEFAULT_NONE);
        assertRequestParam(videoCarouselWorks, 3, "page", "1");
        assertRequestParam(videoCarouselWorks, 4, "pageSize", "20");
        assertThat(method("singleWorkMembers").getAnnotation(Deprecated.class)).isNotNull();
        assertThat(method("singleWorkWorks").getAnnotation(Deprecated.class)).isNotNull();
        Method teamSingleWorkWorksPage = method("teamSingleWorkWorksPage");
        assertThat(teamSingleWorkWorksPage.getParameters()[0].getAnnotation(PathVariable.class)).isNotNull();
        assertThat(teamSingleWorkWorksPage.getParameters()[1].getAnnotation(PathVariable.class)).isNotNull();
        assertRequestParam(teamSingleWorkWorksPage, 2, "page", "1");
        assertRequestParam(teamSingleWorkWorksPage, 3, "pageSize", "20");
        assertRequestParam(
                teamSingleWorkWorksPage,
                4,
                "selectedWorkId",
                org.springframework.web.bind.annotation.ValueConstants.DEFAULT_NONE);

        Method visits = method("visitRecords");
        assertTask8PageParameters(visits);
        Method scheduleQueries = method("scheduleQueries");
        assertTask8PageParameters(scheduleQueries);
        Method contactLeads = method("contactLeads");
        assertTask8PageParameters(contactLeads);
        Method follow = method("followStatus");
        assertThat(follow.getDeclaringClass()).isEqualTo(MineTeamPortfolioController.class);
        assertThat(follow.getAnnotation(PostMapping.class)).isNotNull();
        assertThat(follow.getParameters()[0].getAnnotation(PathVariable.class)).isNotNull();
        assertThat(follow.getParameters()[1].getAnnotation(PathVariable.class)).isNotNull();
        assertRequestParam(follow, 2, "followStatus", org.springframework.web.bind.annotation.ValueConstants.DEFAULT_NONE);
        assertRequestParam(follow, 3, "followNote", "");
    }

    @Test
    void componentLibraryDelegatesNullableEditorRevisionWithoutBusinessRules() {
        MineTeamPortfolioService mineService = mock(MineTeamPortfolioService.class);
        MineTeamPortfolioController controller = new MineTeamPortfolioController(
                mineService,
                mock(TeamPortfolioAssetService.class),
                mock(TeamCarouselComponentService.class),
                mock(TeamVideoCarouselComponentService.class),
                mock(TeamSingleWorkComponentService.class),
                mock(TeamMemberPortfolioGridComponentService.class),
                mock(TeamMemberPortfolioListComponentService.class),
                mock(TeamMemberPortfolioPreviewService.class));

        controller.componentLibrary(null);
        controller.componentLibrary(3);

        verify(mineService).getComponentLibrary(null);
        verify(mineService).getComponentLibrary(3);
    }

    @Test
    void sourceEndpointsDelegateToTheirOwnComponentServices() {
        MineTeamPortfolioService mineService = mock(MineTeamPortfolioService.class);
        TeamPortfolioAssetService assetService = mock(TeamPortfolioAssetService.class);
        TeamCarouselComponentService carousel = mock(TeamCarouselComponentService.class);
        TeamVideoCarouselComponentService videoCarousel = mock(TeamVideoCarouselComponentService.class);
        TeamSingleWorkComponentService singleWork = mock(TeamSingleWorkComponentService.class);
        TeamMemberPortfolioGridComponentService grid = mock(TeamMemberPortfolioGridComponentService.class);
        TeamMemberPortfolioListComponentService list = mock(TeamMemberPortfolioListComponentService.class);
        MineTeamPortfolioController controller = new MineTeamPortfolioController(
                mineService, assetService, carousel, videoCarousel, singleWork, grid, list,
                mock(TeamMemberPortfolioPreviewService.class));

        controller.carouselMembers(13L);
        controller.carouselWorks(13L, 17L);
        TeamVideoCarouselWorkPageResponse videoPage = new TeamVideoCarouselWorkPageResponse();
        when(videoCarousel.pageWorks(13L, 17L, USER_ID, "婚礼", 2, 30)).thenReturn(videoPage);
        assertThat(controller.videoCarouselWorks(13L, 17L, "婚礼", 2, 30).getData()).isSameAs(videoPage);
        controller.singleWorkMembers(13L);
        controller.singleWorkWorks(13L, 17L);
        controller.teamSingleWorkMembers(23L);
        controller.teamSingleWorkWorks(23L, 17L);
        controller.teamSingleWorkWorksPage(23L, 17L, 2, 30, 19L);
        controller.gridMembers(13L);
        controller.gridPortfolios(13L, 17L);
        controller.listMembers(13L);
        controller.listPortfolios(13L, 17L);

        verify(carousel).listMembers(13L, USER_ID);
        verify(carousel).listWorks(13L, 17L, USER_ID);
        verify(videoCarousel).pageWorks(13L, 17L, USER_ID, "婚礼", 2, 30);
        verify(singleWork).listMembers(13L, USER_ID);
        verify(singleWork).listWorks(13L, 17L, USER_ID);
        verify(singleWork).listTeamMembers(23L, USER_ID);
        verify(singleWork).listTeamWorks(23L, 17L, USER_ID);
        verify(singleWork).pageTeamWorks(23L, 17L, USER_ID, 2, 30, 19L);
        verify(grid).listMembers(13L, USER_ID);
        verify(grid).listPortfolios(13L, 17L, USER_ID);
        verify(list).listMembers(13L, USER_ID);
        verify(list).listPortfolios(13L, 17L, USER_ID);
    }

    /**
     * Task 8 四接口必须直接声明并委托认证用户，不允许继承支持层。
     */
    @Test
    void task8EndpointsAreDirectAndDelegateAuthenticatedUser() {
        MineTeamPortfolioService mineService = mock(MineTeamPortfolioService.class);
        MineTeamPortfolioController controller = new MineTeamPortfolioController(
                mineService,
                mock(TeamPortfolioAssetService.class),
                mock(TeamCarouselComponentService.class),
                mock(TeamVideoCarouselComponentService.class),
                mock(TeamSingleWorkComponentService.class),
                mock(TeamMemberPortfolioGridComponentService.class),
                mock(TeamMemberPortfolioListComponentService.class),
                mock(TeamMemberPortfolioPreviewService.class));
        MineTeamPortfolioService.TeamVisitRecordsResponse visits =
                new MineTeamPortfolioService.TeamVisitRecordsResponse(1, 20, false, java.util.List.of());
        MineTeamPortfolioService.TeamScheduleQueryRecordsResponse schedules =
                new MineTeamPortfolioService.TeamScheduleQueryRecordsResponse(1, 20, false, java.util.List.of());
        TeamContactLeadResponse leads = new TeamContactLeadResponse();
        TeamContactLeadResponse.Item lead = new TeamContactLeadResponse.Item();
        when(mineService.getVisitRecords(31L, 1, 20, USER_ID)).thenReturn(visits);
        when(mineService.getScheduleQueryRecords(31L, 1, 20, USER_ID)).thenReturn(schedules);
        when(mineService.getContactLeads(31L, 1, 20, USER_ID)).thenReturn(leads);
        when(mineService.updateContactLeadFollowStatus(
                31L, 91L, "CONTACTED", "已联系", USER_ID)).thenReturn(lead);

        assertThat(controller.visitRecords(31L, 1, 20).getData()).isSameAs(visits);
        assertThat(controller.scheduleQueries(31L, 1, 20).getData()).isSameAs(schedules);
        assertThat(controller.contactLeads(31L, 1, 20).getData()).isSameAs(leads);
        assertThat(controller.followStatus(31L, 91L, "CONTACTED", "已联系").getData()).isSameAs(lead);

        for (String methodName : java.util.List.of(
                "visitRecords", "scheduleQueries", "contactLeads", "followStatus")) {
            assertThat(method(methodName).getDeclaringClass()).isEqualTo(MineTeamPortfolioController.class);
        }
        assertThat(MineTeamPortfolioController.class.getSuperclass()).isEqualTo(Object.class);
    }

    @Test
    void unwiredEndpointsDeclareNonRemovalDeprecation() {
        for (String methodName : java.util.List.of("scheduleOptions", "visitRecords", "scheduleQueries")) {
            Deprecated deprecated = method(methodName).getAnnotation(Deprecated.class);

            assertThat(deprecated).as(methodName + " 弃用标记").isNotNull();
            assertThat(deprecated.since()).as(methodName + " 弃用版本").isEqualTo("2026-07");
            assertThat(deprecated.forRemoval()).as(methodName + " 暂不删除").isFalse();
        }
    }

    @Test
    void teamSingleWorkFullListKeepsCompatibilityDeprecationMetadata() {
        Deprecated teamScoped = method("teamSingleWorkWorks").getAnnotation(Deprecated.class);
        Deprecated portfolioScoped = method("singleWorkWorks").getAnnotation(Deprecated.class);

        assertThat(teamScoped).isNotNull();
        assertThat(teamScoped.since()).isEqualTo("2026-08");
        assertThat(teamScoped.forRemoval()).isFalse();
        assertThat(portfolioScoped).isNotNull();
        assertThat(portfolioScoped.since()).isEqualTo("2026-07");
        assertThat(portfolioScoped.forRemoval()).isFalse();
    }

    @Test
    void teamSingleWorkFullListWritesOneMigrationWarning() {
        MineTeamPortfolioController controller = new MineTeamPortfolioController(
                mock(MineTeamPortfolioService.class),
                mock(TeamPortfolioAssetService.class),
                mock(TeamCarouselComponentService.class),
                mock(TeamVideoCarouselComponentService.class),
                mock(TeamSingleWorkComponentService.class),
                mock(TeamMemberPortfolioGridComponentService.class),
                mock(TeamMemberPortfolioListComponentService.class),
                mock(TeamMemberPortfolioPreviewService.class));
        Logger logger = (Logger) LoggerFactory.getLogger(MineTeamPortfolioController.class);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        try {
            controller.teamSingleWorkWorks(23L, 17L);

            assertThat(appender.list).singleElement().satisfies(event -> {
                assertThat(event.getLevel()).isEqualTo(Level.WARN);
                assertThat(event.getFormattedMessage()).isEqualTo(
                        "调用已弃用团队单个作品全量候选接口: teamId=23, memberUserId=17");
            });
        } finally {
            logger.detachAppender(appender);
            appender.stop();
        }
    }

    @Test
    void unwiredEndpointsWriteDeprecationWarnings() {
        MineTeamPortfolioController controller = new MineTeamPortfolioController(
                mock(MineTeamPortfolioService.class),
                mock(TeamPortfolioAssetService.class),
                mock(TeamCarouselComponentService.class),
                mock(TeamVideoCarouselComponentService.class),
                mock(TeamSingleWorkComponentService.class),
                mock(TeamMemberPortfolioGridComponentService.class),
                mock(TeamMemberPortfolioListComponentService.class),
                mock(TeamMemberPortfolioPreviewService.class));
        Logger logger = (Logger) LoggerFactory.getLogger(MineTeamPortfolioController.class);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        try {
            controller.scheduleOptions(13L, "schedule-1", "draft");
            controller.visitRecords(31L, 1, 20);
            controller.scheduleQueries(31L, 2, 10);

            assertThat(appender.list)
                    .extracting(ILoggingEvent::getLevel)
                    .containsExactly(Level.WARN, Level.WARN, Level.WARN);
            assertThat(appender.list)
                    .extracting(ILoggingEvent::getFormattedMessage)
                    .containsExactly(
                            "调用已弃用团队作品集档期配置接口: portfolioId=13, componentKey=schedule-1, scope=draft",
                            "调用已弃用团队作品集访问记录接口: teamId=31, page=1, pageSize=20",
                            "调用已弃用团队作品集查档历史接口: teamId=31, page=2, pageSize=10");
        } finally {
            logger.detachAppender(appender);
            appender.stop();
        }
    }

    /** 断言分页接口的直接声明、GET 映射及默认参数。 */
    private static void assertTask8PageParameters(Method method) {
        assertThat(method.getDeclaringClass()).isEqualTo(MineTeamPortfolioController.class);
        assertThat(method.getAnnotation(GetMapping.class)).isNotNull();
        assertThat(method.getParameters()[0].getAnnotation(PathVariable.class)).isNotNull();
        assertRequestParam(method, 1, "page", "1");
        assertRequestParam(method, 2, "pageSize", "20");
    }

    /** 断言请求参数名称与默认值。 */
    private static void assertRequestParam(Method method, int index, String name, String defaultValue) {
        RequestParam requestParam = method.getParameters()[index].getAnnotation(RequestParam.class);
        assertThat(requestParam).isNotNull();
        assertThat(requestParam.value()).isEqualTo(name);
        assertThat(requestParam.defaultValue()).isEqualTo(defaultValue);
    }

    private static Map<String, String> endpointMap() {
        Map<String, String> endpoints = new LinkedHashMap<>();
        for (Method method : MineTeamPortfolioController.class.getDeclaredMethods()) {
            GetMapping get = method.getAnnotation(GetMapping.class);
            if (get != null) {
                for (String path : get.value()) {
                    endpoints.put("GET " + path, method.getName());
                }
            }
            PostMapping post = method.getAnnotation(PostMapping.class);
            if (post != null) {
                for (String path : post.value()) {
                    endpoints.put("POST " + path, method.getName());
                }
            }
        }
        return endpoints;
    }

    private static Method method(String name) {
        return java.util.Arrays.stream(MineTeamPortfolioController.class.getDeclaredMethods())
                .filter(method -> method.getName().equals(name))
                .findFirst()
                .orElseThrow();
    }
}
