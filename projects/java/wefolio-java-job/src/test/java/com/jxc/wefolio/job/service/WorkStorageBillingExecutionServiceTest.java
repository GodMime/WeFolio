package com.jxc.wefolio.job.service;

import com.jxc.wefolio.job.dto.WorkStorageBillingExecuteRequest;
import com.jxc.wefolio.job.service.WorkStorageBillingExecutionCoordinator.Submission;
import com.jxc.wefolio.job.service.WorkStorageBillingExecutionCoordinator.TriggerSource;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** 作品存储结算手工执行应用服务测试。 */
class WorkStorageBillingExecutionServiceTest {

    /** 未指定账期时必须沿用上海时区上一个月并返回已接受结果。 */
    @Test
    void missingMonthShouldSubmitPreviousMonthAndBuildAcceptedResponse() {
        AdminPointSecretValidator validator = mock(AdminPointSecretValidator.class);
        when(validator.isValid("secret")).thenReturn(true);
        WorkStorageBillingExecutionCoordinator coordinator = mock(WorkStorageBillingExecutionCoordinator.class);
        when(coordinator.submit(LocalDate.of(2026, 12, 1), TriggerSource.MANUAL))
                .thenReturn(new Submission(true, "execution-1", LocalDate.of(2026, 12, 1), "ACCEPTED"));

        WorkStorageBillingExecutionService.ExecutionResult result =
                service(validator, coordinator).execute("secret", null);

        assertThat(result.outcome()).isEqualTo(WorkStorageBillingExecutionService.ExecutionOutcome.ACCEPTED);
        assertThat(result.message()).isEqualTo("作品存储结算任务已提交");
        assertThat(result.data().getBillingMonth()).isEqualTo("2026-12");
        verify(coordinator).submit(LocalDate.of(2026, 12, 1), TriggerSource.MANUAL);
    }

    /** 密钥无效时必须返回原有未授权消息且不得提交任务。 */
    @Test
    void invalidSecretShouldReturnUnauthorizedWithoutSubmitting() {
        AdminPointSecretValidator validator = mock(AdminPointSecretValidator.class);
        WorkStorageBillingExecutionCoordinator coordinator = mock(WorkStorageBillingExecutionCoordinator.class);

        WorkStorageBillingExecutionService.ExecutionResult result =
                service(validator, coordinator).execute("wrong", request("2026-07"));

        assertThat(result.outcome()).isEqualTo(WorkStorageBillingExecutionService.ExecutionOutcome.UNAUTHORIZED);
        assertThat(result.message()).isEqualTo("后台积分密钥无效");
        assertThat(result.data()).isNull();
        verify(coordinator, never()).submit(LocalDate.of(2026, 7, 1), TriggerSource.MANUAL);
    }

    /** 非法格式、过早和非历史账期必须保留原有业务消息。 */
    @Test
    void invalidMonthsShouldReturnOriginalBadRequestMessages() {
        WorkStorageBillingExecutionService service = service(
                validSecretValidator(), mock(WorkStorageBillingExecutionCoordinator.class));

        assertBadRequest(service.execute("secret", request("2026-7")), "账期格式必须为 yyyy-MM");
        assertBadRequest(service.execute("secret", request("2026-05")), "账期不得早于 2026-06");
        assertBadRequest(service.execute("secret", request("2027-01")), "仅允许执行当前月之前的账期");
    }

    /** 已有任务运行时必须返回原有非重复提交结果。 */
    @Test
    void runningTaskShouldReturnAlreadyRunningResult() {
        WorkStorageBillingExecutionCoordinator coordinator = mock(WorkStorageBillingExecutionCoordinator.class);
        when(coordinator.submit(LocalDate.of(2026, 7, 1), TriggerSource.MANUAL))
                .thenReturn(new Submission(false, "execution-1", LocalDate.of(2026, 7, 1), "ALREADY_RUNNING"));

        WorkStorageBillingExecutionService.ExecutionResult result = service(
                validSecretValidator(), coordinator).execute("secret", request("2026-07"));

        assertThat(result.outcome())
                .isEqualTo(WorkStorageBillingExecutionService.ExecutionOutcome.ALREADY_RUNNING);
        assertThat(result.message()).isEqualTo("作品存储结算任务正在执行，本次未重复提交");
        assertThat(result.data().isAccepted()).isFalse();
    }

    /** 停用后协调器拒绝新任务时必须沿用现有 503 应用结果契约。 */
    @Test
    void stoppedCoordinatorShouldReturnUnavailableWithStopMessage() {
        WorkStorageBillingExecutionCoordinator coordinator = mock(WorkStorageBillingExecutionCoordinator.class);
        when(coordinator.submit(any(), eq(TriggerSource.MANUAL)))
                .thenThrow(new WorkStorageBillingUnavailableException(
                        "job 服务正在停用，不再接受作品存储结算任务"));

        WorkStorageBillingExecutionService.ExecutionResult result = service(
                validSecretValidator(), coordinator).execute("secret", request("2026-07"));

        assertThat(result.outcome()).isEqualTo(WorkStorageBillingExecutionService.ExecutionOutcome.UNAVAILABLE);
        assertThat(result.message()).isEqualTo("job 服务正在停用，不再接受作品存储结算任务");
        assertThat(result.data()).isNull();
    }

    private WorkStorageBillingExecutionService service(
            AdminPointSecretValidator validator,
            WorkStorageBillingExecutionCoordinator coordinator
    ) {
        Clock clock = Clock.fixed(
                Instant.parse("2027-01-01T02:00:00Z"), ZoneId.of("Asia/Shanghai"));
        return new WorkStorageBillingExecutionService(validator, coordinator, clock);
    }

    /** 构造始终通过密钥校验的模拟对象。 */
    private AdminPointSecretValidator validSecretValidator() {
        AdminPointSecretValidator validator = mock(AdminPointSecretValidator.class);
        when(validator.isValid(anyString())).thenReturn(true);
        return validator;
    }

    private WorkStorageBillingExecuteRequest request(String month) {
        WorkStorageBillingExecuteRequest request = new WorkStorageBillingExecuteRequest();
        request.setBillingMonth(month);
        return request;
    }

    private void assertBadRequest(
            WorkStorageBillingExecutionService.ExecutionResult result,
            String message
    ) {
        assertThat(result.outcome()).isEqualTo(WorkStorageBillingExecutionService.ExecutionOutcome.BAD_REQUEST);
        assertThat(result.message()).isEqualTo(message);
        assertThat(result.data()).isNull();
    }
}
