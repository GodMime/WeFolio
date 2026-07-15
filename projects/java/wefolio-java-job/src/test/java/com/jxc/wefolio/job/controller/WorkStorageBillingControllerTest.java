package com.jxc.wefolio.job.controller;

import com.jxc.wefolio.job.dto.WorkStorageBillingExecuteRequest;
import com.jxc.wefolio.job.service.AdminPointSecretValidator;
import com.jxc.wefolio.job.service.WorkStorageBillingExecutionCoordinator;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 主动执行作品存储结算接口测试。
 */
class WorkStorageBillingControllerTest {

    @Test
    void endpointShouldExposeJobApiRelativePathAndAcceptedJson() throws Exception {
        AdminPointSecretValidator validator = mock(AdminPointSecretValidator.class);
        WorkStorageBillingExecutionCoordinator coordinator = mock(WorkStorageBillingExecutionCoordinator.class);
        when(coordinator.submit(
                LocalDate.of(2026, 12, 1), WorkStorageBillingExecutionCoordinator.TriggerSource.MANUAL))
                .thenReturn(new WorkStorageBillingExecutionCoordinator.Submission(
                        true, "execution-1", LocalDate.of(2026, 12, 1), "ACCEPTED"));
        MockMvc mockMvc = MockMvcBuilders.standaloneSetup(controller(validator, coordinator)).build();

        mockMvc.perform(post("/work-storage-billing/execute")
                        .header("X-Admin-Point-Secret", "secret")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.accepted").value(true))
                .andExpect(jsonPath("$.data.executionId").value("execution-1"))
                .andExpect(jsonPath("$.data.billingMonth").value("2026-12"))
                .andExpect(jsonPath("$.data.executionStatus").value("ACCEPTED"));
    }

    @Test
    void missingMonthInJanuaryShouldSubmitPreviousDecember() {
        AdminPointSecretValidator validator = mock(AdminPointSecretValidator.class);
        WorkStorageBillingExecutionCoordinator coordinator = mock(WorkStorageBillingExecutionCoordinator.class);
        when(coordinator.submit(
                LocalDate.of(2026, 12, 1), WorkStorageBillingExecutionCoordinator.TriggerSource.MANUAL))
                .thenReturn(new WorkStorageBillingExecutionCoordinator.Submission(
                        true, "execution-1", LocalDate.of(2026, 12, 1), "ACCEPTED"));
        WorkStorageBillingController controller = controller(validator, coordinator);

        var response = controller.execute("secret", null);

        assertThat(response.getStatusCode().value()).isEqualTo(202);
        assertThat(response.getBody().getData().getBillingMonth()).isEqualTo("2026-12");
        verify(coordinator).submit(
                LocalDate.of(2026, 12, 1), WorkStorageBillingExecutionCoordinator.TriggerSource.MANUAL);
    }

    @Test
    void invalidSecretShouldReturnUnauthorizedWithoutSubmitting() {
        AdminPointSecretValidator validator = mock(AdminPointSecretValidator.class);
        doThrow(new IllegalArgumentException("后台积分密钥无效")).when(validator).validate("wrong");
        WorkStorageBillingExecutionCoordinator coordinator = mock(WorkStorageBillingExecutionCoordinator.class);

        var response = controller(validator, coordinator).execute("wrong", request("2026-07"));

        assertThat(response.getStatusCode().value()).isEqualTo(401);
        assertThat(response.getBody().getMessage()).isEqualTo("后台积分密钥无效");
    }

    @Test
    void invalidCurrentFutureOrTooEarlyMonthShouldReturnBadRequest() {
        WorkStorageBillingController controller = controller(
                mock(AdminPointSecretValidator.class), mock(WorkStorageBillingExecutionCoordinator.class));

        assertThat(controller.execute("secret", request("2026-7")).getStatusCode().value()).isEqualTo(400);
        assertThat(controller.execute("secret", request("2026-05")).getStatusCode().value()).isEqualTo(400);
        assertThat(controller.execute("secret", request("2027-01")).getStatusCode().value()).isEqualTo(400);
    }

    @Test
    void defaultPreviousMonthAtFirstBillingMonthShouldSubmitJune() {
        WorkStorageBillingExecutionCoordinator coordinator = mock(WorkStorageBillingExecutionCoordinator.class);
        when(coordinator.submit(
                LocalDate.of(2026, 6, 1), WorkStorageBillingExecutionCoordinator.TriggerSource.MANUAL))
                .thenReturn(new WorkStorageBillingExecutionCoordinator.Submission(
                        true, "execution-june", LocalDate.of(2026, 6, 1), "ACCEPTED"));
        Clock clock = Clock.fixed(
                Instant.parse("2026-07-01T02:00:00Z"), ZoneId.of("Asia/Shanghai"));
        WorkStorageBillingController controller = new WorkStorageBillingController(
                mock(AdminPointSecretValidator.class), coordinator, clock);

        var response = controller.execute("secret", null);

        assertThat(response.getStatusCode().value()).isEqualTo(202);
        assertThat(response.getBody().getData().getBillingMonth()).isEqualTo("2026-06");
        verify(coordinator).submit(
                LocalDate.of(2026, 6, 1), WorkStorageBillingExecutionCoordinator.TriggerSource.MANUAL);
    }

    private WorkStorageBillingController controller(
            AdminPointSecretValidator validator,
            WorkStorageBillingExecutionCoordinator coordinator
    ) {
        Clock clock = Clock.fixed(
                Instant.parse("2027-01-01T02:00:00Z"), ZoneId.of("Asia/Shanghai"));
        return new WorkStorageBillingController(validator, coordinator, clock);
    }

    private WorkStorageBillingExecuteRequest request(String month) {
        WorkStorageBillingExecuteRequest request = new WorkStorageBillingExecuteRequest();
        request.setBillingMonth(month);
        return request;
    }
}
