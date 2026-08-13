package com.jxc.wefolio.controller;

import com.jxc.wefolio.annotation.VisitorAccess;
import com.jxc.wefolio.annotation.LoginAccess;
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
import com.jxc.wefolio.exception.BusinessException;
import com.jxc.wefolio.exception.GlobalExceptionHandler;
import com.jxc.wefolio.service.ContactLeadService;
import com.jxc.wefolio.service.VisitorPortfolioService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 访客作品集控制器测试 — 固定访客端接口路径和访问注解。
 */
@ExtendWith({MockitoExtension.class, OutputCaptureExtension.class})
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
        ContactLeadSubmitResponse leadV2Response = new ContactLeadSubmitResponse();
        when(visitorPortfolioService.openPortfolio("PF001", openRequest)).thenReturn(portfolioResponse);
        when(visitorPortfolioService.createVisitorAvatarUploadTicket("PF001", uploadTicketRequest))
                .thenReturn(uploadTicketResponse);
        when(visitorPortfolioService.querySchedule(
                "PF001",
                "2026-07-18",
                "2026-07-18",
                "ALL",
                "visitor-a",
                "schedule-1")).thenReturn(scheduleResponse);
        when(visitorPortfolioService.queryScheduleOptions("PF001", "2026-07", "c_schedule"))
                .thenReturn(optionsResponse);
        when(visitorPortfolioService.submitScheduleQuery("PF001", queryRequest)).thenReturn(queryResponse);
        when(contactLeadService.submit("PF001", leadRequest)).thenReturn(leadResponse);
        when(contactLeadService.submitV2("PF001", leadRequest)).thenReturn(leadV2Response);

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
        Response<ContactLeadSubmitResponse> leadV2 = controller.contactLeadV2("PF001", leadRequest);

        assertThat(VisitorPortfolioController.class.isAnnotationPresent(VisitorAccess.class)).isTrue();
        assertThat(VisitorPortfolioController.class
                .getMethod("open", String.class, VisitorPortfolioOpenRequest.class)
                .isAnnotationPresent(LoginAccess.class)).isTrue();
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
        assertPostMapping("contactLeadV2",
                new Class<?>[] {String.class, ContactLeadSubmitRequest.class},
                "/api/visitor/portfolios/{shareCode}/contact-leads/v2");
        assertThat(VisitorPortfolioController.class
                .getMethod("contactLead", String.class, ContactLeadSubmitRequest.class)
                .isAnnotationPresent(Deprecated.class)).isTrue();
        assertThat(open.getData()).isSameAs(portfolioResponse);
        assertThat(uploadTicket.getData()).isSameAs(uploadTicketResponse);
        assertThat(profileUpdate.isSuccess()).isTrue();
        assertThat(schedule.getData()).isSameAs(scheduleResponse);
        assertThat(scheduleOptions.getData()).isSameAs(optionsResponse);
        assertThat(scheduleQuery.getData()).isSameAs(queryResponse);
        assertThat(event.isSuccess()).isTrue();
        assertThat(lead.getData()).isSameAs(leadResponse);
        assertThat(leadV2.getData()).isSameAs(leadV2Response);
        verify(visitorPortfolioService).recordEvent("PF001", eventRequest);
        verify(visitorPortfolioService).updateVisitorProfile("PF001", profileUpdateRequest);
    }

    @Test
    void legacyGetPortfolioEndpointShouldBeRemoved() {
        assertThat(VisitorPortfolioController.class.getDeclaredMethods())
                .filteredOn(method -> {
                    GetMapping mapping = method.getAnnotation(GetMapping.class);
                    return mapping != null
                            && java.util.Arrays.asList(mapping.value()).contains("/api/visitor/portfolios/{shareCode}");
                })
                .isEmpty();
    }

    /** 旧留资入口必须继续委派旧方法并打印弃用告警。 */
    @Test
    void legacyContactLeadShouldKeepDeprecatedWarning(CapturedOutput output) {
        VisitorPortfolioController controller =
                new VisitorPortfolioController(visitorPortfolioService, contactLeadService);
        ContactLeadSubmitRequest request = new ContactLeadSubmitRequest();
        ContactLeadSubmitResponse response = new ContactLeadSubmitResponse();
        when(contactLeadService.submit("PF001", request)).thenReturn(response);

        Response<ContactLeadSubmitResponse> actual =
                controller.contactLead("PF001", request);

        assertThat(actual.getData()).isSameAs(response);
        verify(contactLeadService).submit("PF001", request);
        verify(contactLeadService, never()).submitV2(anyString(), any());
        assertThat(output).contains("访客调用已弃用的联系线索接口")
                .contains("shareCode=PF001");
    }

    /** 计费业务故障必须返回 400 失败响应。 */
    @Test
    void billingBusinessFailureShouldReturnBadRequestForBothEndpoints()
            throws Exception {
        when(visitorPortfolioService.submitScheduleQuery(eq("PF001"), any()))
                .thenThrow(new BusinessException("积分规则不存在或未启用"));
        when(contactLeadService.submitV2(eq("PF001"), any()))
                .thenThrow(new BusinessException("积分规则不存在或未启用"));
        MockMvc mvc = MockMvcBuilders
                .standaloneSetup(new VisitorPortfolioController(
                        visitorPortfolioService, contactLeadService))
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();

        for (String path : List.of(
                "/api/visitor/portfolios/PF001/schedule-query",
                "/api/visitor/portfolios/PF001/contact-leads/v2")) {
            mvc.perform(post(path).contentType(MediaType.APPLICATION_JSON)
                            .content("{}"))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.success").value(false))
                    .andExpect(jsonPath("$.message")
                            .value("积分规则不存在或未启用"));
        }
    }

    /** 未预期计费系统故障必须返回 500 通用失败响应。 */
    @Test
    void billingSystemFailureShouldReturnInternalServerErrorForBothEndpoints()
            throws Exception {
        when(visitorPortfolioService.submitScheduleQuery(eq("PF001"), any()))
                .thenThrow(new IllegalStateException("ledger write failed"));
        when(contactLeadService.submitV2(eq("PF001"), any()))
                .thenThrow(new IllegalStateException("ledger write failed"));
        MockMvc mvc = MockMvcBuilders
                .standaloneSetup(new VisitorPortfolioController(
                        visitorPortfolioService, contactLeadService))
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();

        for (String path : List.of(
                "/api/visitor/portfolios/PF001/schedule-query",
                "/api/visitor/portfolios/PF001/contact-leads/v2")) {
            mvc.perform(post(path).contentType(MediaType.APPLICATION_JSON)
                            .content("{}"))
                    .andExpect(status().isInternalServerError())
                    .andExpect(jsonPath("$.success").value(false))
                    .andExpect(jsonPath("$.message").value("Internal server error"));
        }
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
