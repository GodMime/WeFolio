package com.jxc.wefolio.controller;

import com.jxc.wefolio.annotation.LoginAccess;
import com.jxc.wefolio.annotation.MaintainerAccess;
import com.jxc.wefolio.annotation.TimelineAnonymousAccess;
import com.jxc.wefolio.annotation.VisitorAccess;
import com.jxc.wefolio.dict.PortfolioTemplateTypeDict;
import com.jxc.wefolio.dto.VisitActivityUpdateRequest;
import com.jxc.wefolio.dto.teamportfolio.TeamContactLeadSubmitRequest;
import com.jxc.wefolio.dto.teamportfolio.TeamPortfolioScheduleQueryRequest;
import com.jxc.wefolio.dto.teamportfolio.VisitorTeamPortfolioEventRequest;
import com.jxc.wefolio.dto.teamportfolio.VisitorTeamPortfolioOpenRequest;
import com.jxc.wefolio.service.advancedportfolio.AdvancedPortfolioApplicationService;
import com.jxc.wefolio.service.advancedportfolio.AdvancedTeamMemberPortfolioPreviewService;
import com.jxc.wefolio.service.teamportfolio.VisitorTeamPortfolioService;
import org.junit.jupiter.api.Test;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestMapping;

import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

/** 高级维护/访客独立路由与 HTTP 委派契约，控制器不参与业务推导。 */
class AdvancedPortfolioControllerTest {
    /** 高级维护 Controller 保留原始请求字符串交由应用层校验。 */
    @Test void maintenanceUsesDedicatedRoutesAndRawSaveBody() {
        assertThat(MineAdvancedPortfolioController.class).hasAnnotation(MaintainerAccess.class);
        assertThat(MineAdvancedPortfolioController.class.getAnnotation(RequestMapping.class).value())
                .containsExactly("/api/mine/advanced-portfolios");
        assertThat(endpointMap(MineAdvancedPortfolioController.class)).containsEntry("PUT /{id}/draft", "saveDraft")
                .containsEntry("POST /{id}/publish", "publish").doesNotContainKeys("GET /", "GET ");
        AdvancedPortfolioApplicationService application = mock(AdvancedPortfolioApplicationService.class);
        MineAdvancedPortfolioController controller = new MineAdvancedPortfolioController(application, mock(AdvancedTeamMemberPortfolioPreviewService.class));
        String raw = "{\"config\":{},\"aiContext\":{\"version\":1}}";
        controller.saveDraft(11L, raw); controller.preview(11L); controller.publishedPreview(11L); controller.delete(11L);
        verify(application).saveDraft(11L, raw); verify(application).preview(11L, false);
        verify(application).preview(11L, true); verify(application).delete(11L);
    }

    /** 高级团队的匿名能力仅出现在明确的互动端点，open 单独受理登录流程。 */
    @Test void visitorRoutesAndAnonymousAnnotationsAreExact() {
        assertThat(VisitorAdvancedTeamPortfolioController.class).hasAnnotation(VisitorAccess.class);
        assertThat(method("open").getAnnotation(LoginAccess.class)).isNotNull();
        Set<String> anonymous = Set.of("event", "scheduleOptions", "scheduleQuery", "contactLead", "activity");
        for (Method endpoint : VisitorAdvancedTeamPortfolioController.class.getDeclaredMethods()) {
            assertThat(endpoint.isAnnotationPresent(TimelineAnonymousAccess.class)).isEqualTo(anonymous.contains(endpoint.getName()));
        }
        assertThat(endpointMap(VisitorAdvancedTeamPortfolioController.class)).containsExactlyInAnyOrderEntriesOf(Map.of(
                "POST /api/visitor/advanced-team-portfolios/{shareCode}/open", "open",
                "POST /api/visitor/advanced-team-portfolios/{shareCode}/visitor-avatar/upload-ticket", "createVisitorAvatarUploadTicket",
                "PUT /api/visitor/advanced-team-portfolios/{shareCode}/visitor-profile", "updateVisitorProfile",
                "POST /api/visitor/advanced-team-portfolios/{shareCode}/events", "event",
                "GET /api/visitor/advanced-team-portfolios/{shareCode}/schedule-options", "scheduleOptions",
                "POST /api/visitor/advanced-team-portfolios/{shareCode}/schedule-query", "scheduleQuery",
                "POST /api/visitor/advanced-team-portfolios/{shareCode}/contact-leads", "contactLead",
                "PUT /api/visitor/advanced-team-portfolios/{shareCode}/visit-sessions/{sessionId}/activity", "activity"));
    }

    /** 每个高级访客动作以服务端常量选择模板，客户端没有模板选择权。 */
    @Test void visitorDelegatesWithExplicitAdvancedTemplate() {
        VisitorTeamPortfolioService visitor = mock(VisitorTeamPortfolioService.class);
        VisitorAdvancedTeamPortfolioController controller = new VisitorAdvancedTeamPortfolioController(visitor);
        String advanced = PortfolioTemplateTypeDict.ADVANCED.getCode();
        VisitorTeamPortfolioOpenRequest open = new VisitorTeamPortfolioOpenRequest();
        VisitorTeamPortfolioEventRequest event = new VisitorTeamPortfolioEventRequest();
        TeamPortfolioScheduleQueryRequest schedule = new TeamPortfolioScheduleQueryRequest();
        TeamContactLeadSubmitRequest lead = new TeamContactLeadSubmitRequest();
        VisitActivityUpdateRequest activity = new VisitActivityUpdateRequest();
        controller.open("PF001", open); controller.event("PF001", event); controller.scheduleOptions("PF001", "schedule");
        controller.scheduleQuery("PF001", schedule); controller.contactLead("PF001", lead); controller.activity("PF001", 8L, activity);
        verify(visitor).openPortfolio("PF001", open, advanced); verify(visitor).recordEvent("PF001", event, advanced);
        verify(visitor).queryScheduleOptions("PF001", "schedule", advanced); verify(visitor).submitScheduleQuery("PF001", schedule, advanced);
        verify(visitor).submitContactLead("PF001", lead, advanced); verify(visitor).recordActivity("PF001", 8L, activity, advanced);
    }

    /** 查找真实访客方法，避免在测试另造一套匿名注解。 */
    private Method method(String name) {
        return Arrays.stream(VisitorAdvancedTeamPortfolioController.class.getDeclaredMethods()).filter(method -> method.getName().equals(name)).findFirst().orElseThrow();
    }
    /** 汇总明确路由声明，用来检查高级与普通 HTTP 命名空间隔离。 */
    private Map<String,String> endpointMap(Class<?> controller) {
        Map<String,String> routes = new LinkedHashMap<>();
        for (Method method : controller.getDeclaredMethods()) {
            GetMapping get = method.getAnnotation(GetMapping.class); PostMapping post = method.getAnnotation(PostMapping.class); PutMapping put = method.getAnnotation(PutMapping.class);
            if (get != null) { for (String route : get.value()) { routes.put("GET " + route, method.getName()); } }
            if (post != null) { for (String route : post.value()) { routes.put("POST " + route, method.getName()); } }
            if (put != null) { for (String route : put.value()) { routes.put("PUT " + route, method.getName()); } }
        }
        return routes;
    }
}
