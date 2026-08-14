package com.jxc.wefolio.job.controller;

import com.jxc.wefolio.job.dto.JobSchedulingDisableResponse;
import com.jxc.wefolio.job.service.JobSchedulingDisableService;
import com.jxc.wefolio.job.service.JobSchedulingDisableService.ExecutionOutcome;
import com.jxc.wefolio.job.service.JobSchedulingDisableService.ExecutionResult;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 后台调度停用 HTTP 适配器契约测试。
 */
class JobSchedulingControllerTest {

    @Test
    void shouldExposeSuccessfulDisableContract() throws Exception {
        JobSchedulingDisableService disableService = mock(JobSchedulingDisableService.class);
        when(disableService.disable("secret")).thenReturn(new ExecutionResult(
                ExecutionOutcome.DISABLED,
                "定时调度任务已停用，运行中任务已结束",
                new JobSchedulingDisableResponse("DISABLED", 0, 10)));
        MockMvc mockMvc = MockMvcBuilders.standaloneSetup(
                new JobSchedulingController(disableService)).build();

        mockMvc.perform(post("/scheduling/disable")
                        .header("X-Admin-Point-Secret", "secret"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.message").value("定时调度任务已停用，运行中任务已结束"))
                .andExpect(jsonPath("$.data.status").value("DISABLED"))
                .andExpect(jsonPath("$.data.activeTaskCount").value(0))
                .andExpect(jsonPath("$.data.timeoutSeconds").value(10));

        verify(disableService).disable("secret");
    }

    @Test
    void shouldMapUnauthorizedTimeoutAndUnavailableOutcomes() throws Exception {
        JobSchedulingDisableService disableService = mock(JobSchedulingDisableService.class);
        when(disableService.disable(null)).thenReturn(
                ExecutionResult.unauthorized("job 运维密钥无效"));
        when(disableService.disable("timeout")).thenReturn(new ExecutionResult(
                ExecutionOutcome.TIMEOUT,
                "定时调度任务已停止接收新任务，但仍有运行中任务未结束",
                new JobSchedulingDisableResponse("DRAINING", 1, 10)));
        when(disableService.disable("unavailable")).thenReturn(new ExecutionResult(
                ExecutionOutcome.UNAVAILABLE,
                "定时任务注册器暂不可用",
                new JobSchedulingDisableResponse("DISABLED", 0, 10)));
        JobSchedulingController controller = new JobSchedulingController(disableService);
        MockMvc mockMvc = MockMvcBuilders.standaloneSetup(controller).build();

        mockMvc.perform(post("/scheduling/disable"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.message").value("job 运维密钥无效"))
                .andExpect(jsonPath("$.data").doesNotExist());
        mockMvc.perform(post("/scheduling/disable")
                        .header("X-Admin-Point-Secret", "timeout"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.data.status").value("DRAINING"))
                .andExpect(jsonPath("$.data.activeTaskCount").value(1))
                .andExpect(jsonPath("$.data.timeoutSeconds").value(10));

        assertThat(controller.disable("unavailable").getStatusCode().value()).isEqualTo(503);
        assertThat(controller.disable("unavailable").getBody().getData().getStatus())
                .isEqualTo("DISABLED");
        verify(disableService).disable(null);
        verify(disableService).disable("timeout");
        verify(disableService, org.mockito.Mockito.times(2)).disable("unavailable");
    }
}
