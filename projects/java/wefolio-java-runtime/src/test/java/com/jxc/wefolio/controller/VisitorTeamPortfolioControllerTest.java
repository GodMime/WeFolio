package com.jxc.wefolio.controller;

import com.alibaba.fastjson2.JSONObject;
import com.jxc.wefolio.annotation.LoginAccess;
import com.jxc.wefolio.annotation.MaintainerAccess;
import com.jxc.wefolio.annotation.VisitorAccess;
import com.jxc.wefolio.common.auth.AuthContext;
import com.jxc.wefolio.common.auth.AuthContextHolder;
import com.jxc.wefolio.aspect.AuthAspect;
import com.jxc.wefolio.dto.VisitorAvatarUploadTicketRequest;
import com.jxc.wefolio.dto.VisitorAvatarUploadTicketResponse;
import com.jxc.wefolio.dto.VisitorProfileUpdateRequest;
import com.jxc.wefolio.dto.teamportfolio.TeamContactLeadResponse;
import com.jxc.wefolio.dto.teamportfolio.TeamContactLeadSubmitRequest;
import com.jxc.wefolio.dto.teamportfolio.TeamPortfolioScheduleQueryRequest;
import com.jxc.wefolio.dto.teamportfolio.TeamPortfolioScheduleQueryResponse;
import com.jxc.wefolio.dto.teamportfolio.VisitorTeamPortfolioEventRequest;
import com.jxc.wefolio.dto.teamportfolio.VisitorTeamPortfolioOpenRequest;
import com.jxc.wefolio.dto.teamportfolio.VisitorTeamPortfolioResponse;
import com.jxc.wefolio.service.teamportfolio.MineTeamPortfolioService;
import com.jxc.wefolio.service.teamportfolio.TeamPortfolioAssetService;
import com.jxc.wefolio.service.teamportfolio.TeamMemberPortfolioPreviewService;
import com.jxc.wefolio.service.teamportfolio.VisitorTeamPortfolioService;
import com.jxc.wefolio.service.teamportfolio.component.carousel.TeamCarouselComponentService;
import com.jxc.wefolio.service.teamportfolio.component.contactform.TeamContactFormComponentService;
import com.jxc.wefolio.service.teamportfolio.component.memberportfoliogrid.TeamMemberPortfolioGridComponentService;
import com.jxc.wefolio.service.teamportfolio.component.memberportfoliolist.TeamMemberPortfolioListComponentService;
import com.jxc.wefolio.service.teamportfolio.component.singlework.TeamSingleWorkComponentService;
import com.jxc.wefolio.service.AuthTokenService;
import com.jxc.wefolio.service.VisitorAuthTokenService;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.reflect.MethodSignature;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.lang.reflect.Method;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 团队作品集访客端与 Task 8 维护端控制器契约测试。
 */
class VisitorTeamPortfolioControllerTest {

    /** 当前维护者 ID。 */
    private static final long USER_ID = 61L;

    /** 设置维护者上下文。 */
    @BeforeEach
    void setUp() {
        AuthContextHolder.set(new AuthContext(USER_ID, "maintainer-token"));
    }

    /** 清理维护者上下文。 */
    @AfterEach
    void tearDown() {
        AuthContextHolder.clear();
        RequestContextHolder.resetRequestAttributes();
    }

    /**
     * 团队访客 Controller 必须类级 VisitorAccess，且仅 open 覆盖 LoginAccess。
     */
    @Test
    void visitorControllerUsesClassVisitorAccessAndOpenLoginAccess() throws Exception {
        assertThat(VisitorTeamPortfolioController.class).hasAnnotation(RestController.class);
        assertThat(VisitorTeamPortfolioController.class).hasAnnotation(VisitorAccess.class);
        assertThat(method(VisitorTeamPortfolioController.class, "open").getAnnotation(LoginAccess.class)).isNotNull();
        assertThat(VisitorTeamPortfolioController.class.getDeclaredMethods())
                .filteredOn(candidate -> !candidate.getName().equals("open"))
                .noneMatch(candidate -> candidate.getAnnotation(LoginAccess.class) != null);
    }

    /**
     * 团队访客端必须精确暴露规格中的七个独立 URL。
     */
    @Test
    void visitorEndpointsUseExactMethodsAndUrls() {
        Map<String, String> expected = new LinkedHashMap<>();
        expected.put("POST /api/visitor/team-portfolios/{shareCode}/open", "open");
        expected.put("POST /api/visitor/team-portfolios/{shareCode}/visitor-avatar/upload-ticket",
                "createVisitorAvatarUploadTicket");
        expected.put("PUT /api/visitor/team-portfolios/{shareCode}/visitor-profile", "updateVisitorProfile");
        expected.put("POST /api/visitor/team-portfolios/{shareCode}/events", "event");
        expected.put("GET /api/visitor/team-portfolios/{shareCode}/schedule-options", "scheduleOptions");
        expected.put("POST /api/visitor/team-portfolios/{shareCode}/schedule-query", "scheduleQuery");
        expected.put("POST /api/visitor/team-portfolios/{shareCode}/contact-leads", "contactLead");

        assertThat(endpointMap(VisitorTeamPortfolioController.class)).containsExactlyInAnyOrderEntriesOf(expected);
    }

    /**
     * 团队访客参数必须使用独立 DTO，且不暴露 visitorKey 查询参数。
     */
    @Test
    void visitorEndpointParametersUsePathBodyAndComponentKeyOnly() {
        Method open = method(VisitorTeamPortfolioController.class, "open");
        assertPathAndBody(open, VisitorTeamPortfolioOpenRequest.class);
        Method event = method(VisitorTeamPortfolioController.class, "event");
        assertPathAndBody(event, VisitorTeamPortfolioEventRequest.class);
        Method query = method(VisitorTeamPortfolioController.class, "scheduleQuery");
        assertPathAndBody(query, TeamPortfolioScheduleQueryRequest.class);
        Method lead = method(VisitorTeamPortfolioController.class, "contactLead");
        assertPathAndBody(lead, TeamContactLeadSubmitRequest.class);
        Method options = method(VisitorTeamPortfolioController.class, "scheduleOptions");
        assertThat(options.getParameters()[0].getAnnotation(PathVariable.class)).isNotNull();
        RequestParam componentKey = options.getParameters()[1].getAnnotation(RequestParam.class);
        assertThat(componentKey).isNotNull();
        assertThat(componentKey.value()).isEqualTo("componentKey");
        assertThat(options.getParameters()).noneMatch(parameter -> parameter.getName().equals("visitorKey"));
    }

    /**
     * 访客控制器必须逐接口委托独立团队服务。
     */
    @Test
    void visitorControllerDelegatesEveryEndpointToIndependentTeamService() {
        VisitorTeamPortfolioService service = mock(VisitorTeamPortfolioService.class);
        VisitorTeamPortfolioController controller = new VisitorTeamPortfolioController(service);
        VisitorTeamPortfolioOpenRequest openRequest = new VisitorTeamPortfolioOpenRequest();
        VisitorTeamPortfolioResponse openResponse = new VisitorTeamPortfolioResponse();
        VisitorAvatarUploadTicketRequest ticketRequest = new VisitorAvatarUploadTicketRequest();
        VisitorAvatarUploadTicketResponse ticketResponse = new VisitorAvatarUploadTicketResponse();
        VisitorProfileUpdateRequest profileRequest = new VisitorProfileUpdateRequest();
        VisitorTeamPortfolioEventRequest eventRequest = new VisitorTeamPortfolioEventRequest();
        TeamPortfolioScheduleQueryRequest queryRequest = new TeamPortfolioScheduleQueryRequest();
        TeamPortfolioScheduleQueryResponse queryResponse = new TeamPortfolioScheduleQueryResponse();
        TeamContactLeadSubmitRequest leadRequest = new TeamContactLeadSubmitRequest();
        TeamContactFormComponentService.SubmitResult leadResponse =
                new TeamContactFormComponentService.SubmitResult(91L, java.time.LocalDateTime.now());
        JSONObject options = new JSONObject();
        when(service.openPortfolio("TPF-TASK8", openRequest)).thenReturn(openResponse);
        when(service.createVisitorAvatarUploadTicket("TPF-TASK8", ticketRequest)).thenReturn(ticketResponse);
        when(service.queryScheduleOptions("TPF-TASK8", "schedule-1")).thenReturn(options);
        when(service.submitScheduleQuery("TPF-TASK8", queryRequest)).thenReturn(queryResponse);
        when(service.submitContactLead("TPF-TASK8", leadRequest)).thenReturn(leadResponse);

        assertThat(controller.open("TPF-TASK8", openRequest).getData()).isSameAs(openResponse);
        assertThat(controller.createVisitorAvatarUploadTicket("TPF-TASK8", ticketRequest).getData())
                .isSameAs(ticketResponse);
        assertThat(controller.updateVisitorProfile("TPF-TASK8", profileRequest).isSuccess()).isTrue();
        assertThat(controller.event("TPF-TASK8", eventRequest).isSuccess()).isTrue();
        assertThat(controller.scheduleOptions("TPF-TASK8", "schedule-1").getData()).isSameAs(options);
        assertThat(controller.scheduleQuery("TPF-TASK8", queryRequest).getData()).isSameAs(queryResponse);
        assertThat(controller.contactLead("TPF-TASK8", leadRequest).getData()).isSameAs(leadResponse);
        verify(service).updateVisitorProfile("TPF-TASK8", profileRequest);
        verify(service).recordEvent("TPF-TASK8", eventRequest);
    }

    /**
     * 维护端 Controller 必须保留类级 MaintainerAccess 并精确新增四个 POST/GET URL。
     */
    @Test
    void mineControllerAddsExactlyFourTask8Endpoints() {
        assertThat(MineTeamPortfolioController.class).hasAnnotation(MaintainerAccess.class);
        Map<String, String> endpoints = endpointMap(MineTeamPortfolioController.class);
        assertThat(endpoints).containsEntry("GET /api/mine/teams/{teamId}/visits", "visitRecords");
        assertThat(endpoints).containsEntry("GET /api/mine/teams/{teamId}/schedule-queries", "scheduleQueries");
        assertThat(endpoints).containsEntry("GET /api/mine/teams/{teamId}/contact-leads", "contactLeads");
        assertThat(endpoints).containsEntry(
                "POST /api/mine/teams/{teamId}/contact-leads/{leadId}/follow-status", "followStatus");
        for (String methodName : java.util.List.of(
                "visitRecords", "scheduleQueries", "contactLeads", "followStatus")) {
            assertThat(method(MineTeamPortfolioController.class, methodName).getDeclaringClass())
                    .isEqualTo(MineTeamPortfolioController.class);
        }
        assertThat(MineTeamPortfolioController.class.getSuperclass()).isEqualTo(Object.class);
    }

    /**
     * 维护端四接口必须从认证上下文传 userId，并精确绑定分页和跟进参数。
     */
    @Test
    void mineTask8EndpointsBindExactParametersAndDelegateAuthenticatedUser() {
        MineTeamPortfolioService mineService = mock(MineTeamPortfolioService.class);
        MineTeamPortfolioController controller = mineController(mineService);
        MineTeamPortfolioService.TeamVisitRecordsResponse visits =
                new MineTeamPortfolioService.TeamVisitRecordsResponse(1, 20, false, java.util.List.of());
        MineTeamPortfolioService.TeamScheduleQueryRecordsResponse queries =
                new MineTeamPortfolioService.TeamScheduleQueryRecordsResponse(1, 20, false, java.util.List.of());
        TeamContactLeadResponse leads = new TeamContactLeadResponse();
        TeamContactLeadResponse.Item lead = new TeamContactLeadResponse.Item();
        when(mineService.getVisitRecords(31L, 1, 20, USER_ID)).thenReturn(visits);
        when(mineService.getScheduleQueryRecords(31L, 1, 20, USER_ID)).thenReturn(queries);
        when(mineService.getContactLeads(31L, 1, 20, USER_ID)).thenReturn(leads);
        when(mineService.updateContactLeadFollowStatus(
                31L, 91L, "CONTACTED", "已联系", USER_ID)).thenReturn(lead);

        assertThat(controller.visitRecords(31L, 1, 20).getData()).isSameAs(visits);
        assertThat(controller.scheduleQueries(31L, 1, 20).getData()).isSameAs(queries);
        assertThat(controller.contactLeads(31L, 1, 20).getData()).isSameAs(leads);
        assertThat(controller.followStatus(31L, 91L, "CONTACTED", "已联系").getData()).isSameAs(lead);

        Method visitsMethod = method(MineTeamPortfolioController.class, "visitRecords");
        assertThat(visitsMethod.getAnnotation(GetMapping.class)).isNotNull();
        assertRequestParam(visitsMethod, 1, "page", "1");
        assertRequestParam(visitsMethod, 2, "pageSize", "20");
        Method schedule = method(MineTeamPortfolioController.class, "scheduleQueries");
        assertThat(schedule.getParameters()[0].getAnnotation(PathVariable.class)).isNotNull();
        assertRequestParam(schedule, 1, "page", "1");
        assertRequestParam(schedule, 2, "pageSize", "20");
        Method contact = method(MineTeamPortfolioController.class, "contactLeads");
        assertRequestParam(contact, 1, "page", "1");
        assertRequestParam(contact, 2, "pageSize", "20");
        Method follow = method(MineTeamPortfolioController.class, "followStatus");
        assertThat(follow.getParameters()[0].getAnnotation(PathVariable.class)).isNotNull();
        assertThat(follow.getParameters()[1].getAnnotation(PathVariable.class)).isNotNull();
        assertRequestParam(follow, 2, "followStatus", org.springframework.web.bind.annotation.ValueConstants.DEFAULT_NONE);
        assertRequestParam(follow, 3, "followNote", "");
    }

    /**
     * AuthAspect 必须能从真实直接声明的 Mine Controller 方法解析类级 MaintainerAccess。
     */
    @Test
    void authAspectResolvesMaintainerAccessFromRealTask8DeclaringClass() throws Throwable {
        AuthContextHolder.clear();
        AuthTokenService authTokenService = mock(AuthTokenService.class);
        VisitorAuthTokenService visitorAuthTokenService = mock(VisitorAuthTokenService.class);
        ProceedingJoinPoint joinPoint = mock(ProceedingJoinPoint.class);
        MethodSignature signature = mock(MethodSignature.class);
        Method method = MineTeamPortfolioController.class.getDeclaredMethod(
                "visitRecords", Long.class, int.class, int.class);
        when(joinPoint.getSignature()).thenReturn(signature);
        when(signature.getMethod()).thenReturn(method);
        when(signature.getDeclaringType()).thenReturn(MineTeamPortfolioController.class);
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setMethod("GET");
        request.setRequestURI("/api/mine/teams/31/visits");
        request.addHeader("Authorization", "Bearer maintainer");
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));
        when(authTokenService.resolveAuthenticatedUserId("Bearer maintainer"))
                .thenReturn(Optional.of(USER_ID));
        doAnswer(invocation -> {
            assertThat(AuthContextHolder.requireUserId()).isEqualTo(USER_ID);
            return com.jxc.wefolio.common.Response.success();
        }).when(joinPoint).proceed();

        new AuthAspect(authTokenService, visitorAuthTokenService).authenticate(joinPoint);

        verify(authTokenService).resolveAuthenticatedUserId("Bearer maintainer");
        assertThat(AuthContextHolder.getUserId()).isEmpty();
    }

    /** 创建带原有依赖的维护端 Controller。 */
    private static MineTeamPortfolioController mineController(MineTeamPortfolioService service) {
        return new MineTeamPortfolioController(
                service,
                mock(TeamPortfolioAssetService.class),
                mock(TeamCarouselComponentService.class),
                mock(TeamSingleWorkComponentService.class),
                mock(TeamMemberPortfolioGridComponentService.class),
                mock(TeamMemberPortfolioListComponentService.class),
                mock(TeamMemberPortfolioPreviewService.class));
    }

    /** 断言 path + body 参数。 */
    private static void assertPathAndBody(Method method, Class<?> bodyType) {
        assertThat(method.getParameters()).hasSize(2);
        assertThat(method.getParameters()[0].getAnnotation(PathVariable.class)).isNotNull();
        assertThat(method.getParameters()[1].getType()).isEqualTo(bodyType);
        assertThat(method.getParameters()[1].getAnnotation(RequestBody.class)).isNotNull();
    }

    /** 断言请求参数名和默认值。 */
    private static void assertRequestParam(Method method, int index, String name, String defaultValue) {
        RequestParam requestParam = method.getParameters()[index].getAnnotation(RequestParam.class);
        assertThat(requestParam).isNotNull();
        assertThat(requestParam.value()).isEqualTo(name);
        assertThat(requestParam.defaultValue()).isEqualTo(defaultValue);
    }

    /** 按名称查找唯一方法。 */
    private static Method method(Class<?> type, String name) {
        return java.util.Arrays.stream(type.getDeclaredMethods())
                .filter(candidate -> candidate.getName().equals(name))
                .findFirst()
                .orElseThrow();
    }

    /** 收集 Controller 精确请求方法和 URL。 */
    private static Map<String, String> endpointMap(Class<?> type) {
        Map<String, String> endpoints = new LinkedHashMap<>();
        for (Method method : type.getDeclaredMethods()) {
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
            PutMapping put = method.getAnnotation(PutMapping.class);
            if (put != null) {
                for (String path : put.value()) {
                    endpoints.put("PUT " + path, method.getName());
                }
            }
        }
        return endpoints;
    }
}
