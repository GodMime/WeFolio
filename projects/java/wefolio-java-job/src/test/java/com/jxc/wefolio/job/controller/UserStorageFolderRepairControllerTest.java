package com.jxc.wefolio.job.controller;

import com.jxc.wefolio.job.dto.UserStorageFolderRepairExecutionResponse;
import com.jxc.wefolio.job.service.UserStorageFolderRepairExecutionService;
import com.jxc.wefolio.job.service.UserStorageFolderRepairExecutionService.ExecutionOutcome;
import com.jxc.wefolio.job.service.UserStorageFolderRepairExecutionService.ExecutionResult;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 历史用户目录修复 HTTP 适配测试。
 */
class UserStorageFolderRepairControllerTest {

    @Test
    void controllerShouldOnlyForwardSecretAndMapAllOutcomes() {
        UserStorageFolderRepairExecutionService service =
                mock(UserStorageFolderRepairExecutionService.class);
        UserStorageFolderRepairController controller =
                new UserStorageFolderRepairController(service);
        UserStorageFolderRepairExecutionResponse acceptedData =
                new UserStorageFolderRepairExecutionResponse(true, "execution-1", "ACCEPTED");
        when(service.execute("accepted")).thenReturn(new ExecutionResult(
                ExecutionOutcome.ACCEPTED, "已提交", acceptedData));
        when(service.execute("running")).thenReturn(new ExecutionResult(
                ExecutionOutcome.ALREADY_RUNNING, "执行中",
                new UserStorageFolderRepairExecutionResponse(
                        false, "execution-1", "ALREADY_RUNNING")));
        when(service.execute("wrong")).thenReturn(
                ExecutionResult.failure(ExecutionOutcome.UNAUTHORIZED, "密钥无效"));
        when(service.execute("busy")).thenReturn(
                ExecutionResult.failure(ExecutionOutcome.UNAVAILABLE, "执行器不可用"));

        assertThat(controller.repair("accepted").getStatusCode().value()).isEqualTo(202);
        assertThat(controller.repair("running").getStatusCode().value()).isEqualTo(200);
        assertThat(controller.repair("wrong").getStatusCode().value()).isEqualTo(401);
        assertThat(controller.repair("busy").getStatusCode().value()).isEqualTo(503);
        verify(service).execute("accepted");
    }
}
