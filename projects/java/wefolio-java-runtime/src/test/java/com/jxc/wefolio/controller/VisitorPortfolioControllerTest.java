package com.jxc.wefolio.controller;

import com.jxc.wefolio.annotation.VisitorAccess;
import com.jxc.wefolio.common.Response;
import com.jxc.wefolio.dto.ContactLeadSubmitRequest;
import com.jxc.wefolio.dto.ContactLeadSubmitResponse;
import com.jxc.wefolio.dto.PortfolioScheduleOptionsResponse;
import com.jxc.wefolio.dto.PortfolioScheduleQueryRequest;
import com.jxc.wefolio.dto.PortfolioScheduleQueryResponse;
import com.jxc.wefolio.dto.VisitorPortfolioEventRequest;
import com.jxc.wefolio.dto.VisitorPortfolioResponse;
import com.jxc.wefolio.dto.VisitorPortfolioScheduleResponse;
import com.jxc.wefolio.service.ContactLeadService;
import com.jxc.wefolio.service.VisitorPortfolioService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;

import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 访客作品集控制器测试 — 固定访客端接口路径和访问注解。
 */
@ExtendWith(MockitoExtension.class)
class VisitorPortfolioControllerTest {

    /** 访客作品集服务模拟 */
    @Mock
    private VisitorPortfolioService visitorPortfolioService;

    /** 联系线索服务模拟 */
    @Mock
    private ContactLeadService contactLeadService;

    @Test
    void controllerShouldUseVisitorAccessAndDelegateToServices() throws NoSuchMethodException {
        VisitorPortfolioController controller = new VisitorPortfolioController(visitorPortfolioService, contactLeadService);
        VisitorPortfolioResponse portfolioResponse = new VisitorPortfolioResponse();
        VisitorPortfolioScheduleResponse scheduleResponse = new VisitorPortfolioScheduleResponse();
        PortfolioScheduleOptionsResponse optionsResponse = new PortfolioScheduleOptionsResponse();
        PortfolioScheduleQueryRequest queryRequest = new PortfolioScheduleQueryRequest();
        PortfolioScheduleQueryResponse queryResponse = new PortfolioScheduleQueryResponse();
        VisitorPortfolioEventRequest eventRequest = new VisitorPortfolioEventRequest();
        ContactLeadSubmitRequest leadRequest = new ContactLeadSubmitRequest();
        ContactLeadSubmitResponse leadResponse = new ContactLeadSubmitResponse();
        when(visitorPortfolioService.getPortfolio("PF001", "visitor-a", "wx-code", "WECHAT_SHARE_CARD", "open-1"))
                .thenReturn(portfolioResponse);
        when(visitorPortfolioService.querySchedule(
                "PF001",
                LocalDate.of(2026, 7, 18),
                LocalDate.of(2026, 7, 18),
                "ALL",
                "visitor-a",
                "schedule-1")).thenReturn(scheduleResponse);
        when(visitorPortfolioService.queryScheduleOptions("PF001", "2026-07", "c_schedule"))
                .thenReturn(optionsResponse);
        when(visitorPortfolioService.submitScheduleQuery("PF001", queryRequest)).thenReturn(queryResponse);
        when(contactLeadService.submit("PF001", leadRequest)).thenReturn(leadResponse);

        Response<VisitorPortfolioResponse> portfolio = controller.portfolio(
                "PF001", "visitor-a", "wx-code", "WECHAT_SHARE_CARD", "open-1");
        Response<VisitorPortfolioScheduleResponse> schedule = controller.schedule(
                "PF001", "2026-07-18", "2026-07-18", "ALL", "visitor-a", "schedule-1");
        Response<PortfolioScheduleOptionsResponse> scheduleOptions = controller.scheduleOptions(
                "PF001", "2026-07", "c_schedule");
        Response<PortfolioScheduleQueryResponse> scheduleQuery = controller.scheduleQuery("PF001", queryRequest);
        Response<Void> event = controller.event("PF001", eventRequest);
        Response<ContactLeadSubmitResponse> lead = controller.contactLead("PF001", leadRequest);

        assertThat(VisitorPortfolioController.class.isAnnotationPresent(VisitorAccess.class)).isTrue();
        assertGetMapping("portfolio",
                new Class<?>[] {String.class, String.class, String.class, String.class, String.class},
                "/api/visitor/portfolios/{shareCode}");
        assertGetMapping("schedule",
                new Class<?>[] {String.class, String.class, String.class, String.class, String.class, String.class},
                "/api/visitor/portfolios/{shareCode}/schedule");
        assertGetMapping("scheduleOptions",
                new Class<?>[] {String.class, String.class, String.class},
                "/api/visitor/portfolios/{shareCode}/schedule-options");
        assertPostMapping("scheduleQuery",
                new Class<?>[] {String.class, PortfolioScheduleQueryRequest.class},
                "/api/visitor/portfolios/{shareCode}/schedule-query");
        assertPostMapping("event",
                new Class<?>[] {String.class, VisitorPortfolioEventRequest.class},
                "/api/visitor/portfolios/{shareCode}/events");
        assertPostMapping("contactLead",
                new Class<?>[] {String.class, ContactLeadSubmitRequest.class},
                "/api/visitor/portfolios/{shareCode}/contact-leads");
        assertThat(VisitorPortfolioController.class.getMethod(
                        "portfolio",
                        String.class,
                        String.class,
                        String.class,
                        String.class,
                        String.class
                )
                .getParameters()[0].isAnnotationPresent(PathVariable.class)).isTrue();
        assertThat(portfolio.getData()).isSameAs(portfolioResponse);
        assertThat(schedule.getData()).isSameAs(scheduleResponse);
        assertThat(scheduleOptions.getData()).isSameAs(optionsResponse);
        assertThat(scheduleQuery.getData()).isSameAs(queryResponse);
        assertThat(event.isSuccess()).isTrue();
        assertThat(lead.getData()).isSameAs(leadResponse);
        verify(visitorPortfolioService).recordEvent("PF001", eventRequest);
    }

    private void assertGetMapping(String methodName, Class<?>[] parameterTypes, String path)
            throws NoSuchMethodException {
        GetMapping mapping = VisitorPortfolioController.class.getMethod(methodName, parameterTypes)
                .getAnnotation(GetMapping.class);
        assertThat(mapping).isNotNull();
        assertThat(mapping.value()).containsExactly(path);
    }

    private void assertPostMapping(String methodName, Class<?>[] parameterTypes, String path)
            throws NoSuchMethodException {
        PostMapping mapping = VisitorPortfolioController.class.getMethod(methodName, parameterTypes)
                .getAnnotation(PostMapping.class);
        assertThat(mapping).isNotNull();
        assertThat(mapping.value()).containsExactly(path);
    }
}
