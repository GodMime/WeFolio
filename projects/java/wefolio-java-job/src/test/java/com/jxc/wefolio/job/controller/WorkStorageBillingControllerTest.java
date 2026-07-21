package com.jxc.wefolio.job.controller;

import com.jxc.wefolio.job.dto.WorkStorageBillingExecuteRequest;
import com.jxc.wefolio.job.dto.WorkStorageBillingExecutionResponse;
import com.jxc.wefolio.job.service.WorkStorageBillingExecutionService;
import com.jxc.wefolio.job.service.WorkStorageBillingExecutionService.ExecutionOutcome;
import com.jxc.wefolio.job.service.WorkStorageBillingExecutionService.ExecutionResult;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** 作品存储结算主动执行 HTTP 适配测试。 */
class WorkStorageBillingControllerTest {

    /** 已接受结果必须保留原有 202 和响应 JSON。 */
    @Test
    void acceptedResultShouldExposeOriginalHttpContract() throws Exception {
        WorkStorageBillingExecutionService service = mock(WorkStorageBillingExecutionService.class);
        WorkStorageBillingExecutionResponse data =
                new WorkStorageBillingExecutionResponse(true, "execution-1", "2026-12", "ACCEPTED");
        when(service.execute("secret", null))
                .thenReturn(new ExecutionResult(ExecutionOutcome.ACCEPTED, "作品存储结算任务已提交", data));
        MockMvc mockMvc = MockMvcBuilders.standaloneSetup(new WorkStorageBillingController(service)).build();

        mockMvc.perform(post("/work-storage-billing/execute")
                        .header("X-Admin-Point-Secret", "secret")
                        .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.message").value("作品存储结算任务已提交"))
                .andExpect(jsonPath("$.data.accepted").value(true))
                .andExpect(jsonPath("$.data.executionId").value("execution-1"))
                .andExpect(jsonPath("$.data.billingMonth").value("2026-12"))
                .andExpect(jsonPath("$.data.executionStatus").value("ACCEPTED"));
        verify(service).execute("secret", null);
    }

    /** Controller 必须原样转发密钥和请求体。 */
    @Test
    void controllerShouldForwardSecretAndRequest() {
        WorkStorageBillingExecutionService service = mock(WorkStorageBillingExecutionService.class);
        WorkStorageBillingExecuteRequest request = new WorkStorageBillingExecuteRequest();
        request.setBillingMonth("2026-07");
        WorkStorageBillingExecutionResponse data =
                new WorkStorageBillingExecutionResponse(false, "execution-1", "2026-07", "ALREADY_RUNNING");
        when(service.execute("secret", request)).thenReturn(new ExecutionResult(
                ExecutionOutcome.ALREADY_RUNNING, "作品存储结算任务正在执行，本次未重复提交", data));
        WorkStorageBillingController controller = new WorkStorageBillingController(service);

        var response = controller.execute("secret", request);

        assertThat(response.getStatusCode().value()).isEqualTo(200);
        assertThat(response.getBody().getMessage()).isEqualTo("作品存储结算任务正在执行，本次未重复提交");
        assertThat(response.getBody().getData()).isSameAs(data);
        verify(service).execute("secret", request);
    }

    /** 失败结果必须映射到原有 HTTP 状态。 */
    @Test
    void failureOutcomesShouldMapToOriginalHttpStatuses() {
        WorkStorageBillingExecutionService service = mock(WorkStorageBillingExecutionService.class);
        WorkStorageBillingController controller = new WorkStorageBillingController(service);
        WorkStorageBillingExecuteRequest request = new WorkStorageBillingExecuteRequest();

        when(service.execute("unauthorized", request)).thenReturn(
                ExecutionResult.failure(ExecutionOutcome.UNAUTHORIZED, "后台积分密钥无效"));
        when(service.execute("bad", request)).thenReturn(
                ExecutionResult.failure(ExecutionOutcome.BAD_REQUEST, "账期格式必须为 yyyy-MM"));
        when(service.execute("unavailable", request)).thenReturn(
                ExecutionResult.failure(ExecutionOutcome.UNAVAILABLE, "作品存储结算执行器暂不可用"));

        assertThat(controller.execute("unauthorized", request).getStatusCode().value()).isEqualTo(401);
        assertThat(controller.execute("bad", request).getStatusCode().value()).isEqualTo(400);
        assertThat(controller.execute("unavailable", request).getStatusCode().value()).isEqualTo(503);
    }
}
