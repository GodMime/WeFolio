package com.jxc.wefolio.controller;

import com.jxc.wefolio.annotation.VisitorAccess;
import com.jxc.wefolio.common.Response;
import com.jxc.wefolio.dto.ContactLeadSubmitRequest;
import com.jxc.wefolio.dto.ContactLeadSubmitResponse;
import com.jxc.wefolio.dto.PortfolioScheduleOptionsResponse;
import com.jxc.wefolio.dto.PortfolioScheduleQueryRequest;
import com.jxc.wefolio.dto.PortfolioScheduleQueryResponse;
import com.jxc.wefolio.dto.VisitorAvatarUploadTicketRequest;
import com.jxc.wefolio.dto.VisitorAvatarUploadTicketResponse;
import com.jxc.wefolio.dto.VisitorPortfolioOpenRequest;
import com.jxc.wefolio.dto.VisitorPortfolioEventRequest;
import com.jxc.wefolio.dto.VisitorPortfolioResponse;
import com.jxc.wefolio.dto.VisitorPortfolioScheduleResponse;
import com.jxc.wefolio.dto.VisitorProfileUpdateRequest;
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
        VisitorPortfolioOpenRequest openRequest = new VisitorPortfolioOpenRequest();
        VisitorAvatarUploadTicketRequest uploadTicketRequest = new VisitorAvatarUploadTicketRequest();
        VisitorAvatarUploadTicketResponse uploadTicketResponse = new VisitorAvatarUploadTicketResponse();
        VisitorProfileUpdateRequest profileUpdateRequest = new VisitorProfileUpdateRequest();
        ContactLeadSubmitRequest leadRequest = new ContactLeadSubmitRequest();
        ContactLeadSubmitResponse leadResponse = new ContactLeadSubmitResponse();
        when(visitorPortfolioService.getPortfolio("PF001", "visitor-a", "wx-code", "WECHAT_SHARE_CARD", "open-1"))
                .thenReturn(portfolioResponse);
        when(visitorPortfolioService.openPortfolio("PF001", openRequest)).thenReturn(portfolioResponse);
        when(visitorPortfolioService.createVisitorAvatarUploadTicket("PF001", uploadTicketRequest))
                .thenReturn(uploadTicketResponse);
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
        Response<VisitorPortfolioResponse> open = controller.open("PF001", openRequest);
        Response<VisitorAvatarUploadTicketResponse> uploadTicket = controller.createVisitorAvatarUploadTicket(
                "PF001",
                uploadTicketRequest
        );
        Response<Void> profileUpdate = controller.updateVisitorProfile("PF001", profileUpdateRequest);
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
        assertPostMapping("open",
                new Class<?>[] {String.class, VisitorPortfolioOpenRequest.class},
                "/api/visitor/portfolios/{shareCode}/open");
        assertPostMapping("createVisitorAvatarUploadTicket",
                new Class<?>[] {String.class, VisitorAvatarUploadTicketRequest.class},
                "/api/visitor/portfolios/{shareCode}/visitor-avatar/upload-ticket");
        assertPutMapping("updateVisitorProfile",
                new Class<?>[] {String.class, VisitorProfileUpdateRequest.class},
                "/api/visitor/portfolios/{shareCode}/visitor-profile");
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
        assertThat(open.getData()).isSameAs(portfolioResponse);
        assertThat(uploadTicket.getData()).isSameAs(uploadTicketResponse);
        assertThat(profileUpdate.isSuccess()).isTrue();
        assertThat(schedule.getData()).isSameAs(scheduleResponse);
        assertThat(scheduleOptions.getData()).isSameAs(optionsResponse);
        assertThat(scheduleQuery.getData()).isSameAs(queryResponse);
        assertThat(event.isSuccess()).isTrue();
        assertThat(lead.getData()).isSameAs(leadResponse);
        verify(visitorPortfolioService).recordEvent("PF001", eventRequest);
        verify(visitorPortfolioService).updateVisitorProfile("PF001", profileUpdateRequest);
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

    private void assertPutMapping(String methodName, Class<?>[] parameterTypes, String path)
            throws NoSuchMethodException {
        org.springframework.web.bind.annotation.PutMapping mapping =
                VisitorPortfolioController.class.getMethod(methodName, parameterTypes)
                        .getAnnotation(org.springframework.web.bind.annotation.PutMapping.class);
        assertThat(mapping).isNotNull();
        assertThat(mapping.value()).containsExactly(path);
    }
}
