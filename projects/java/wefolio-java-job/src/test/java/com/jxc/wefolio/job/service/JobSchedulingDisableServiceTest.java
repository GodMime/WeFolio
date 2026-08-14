package com.jxc.wefolio.job.service;

import com.jxc.wefolio.job.config.JobScheduledTaskRegistry;
import com.jxc.wefolio.job.config.JobSchedulingProperties;
import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * 后台调度安全停用应用服务测试。
 */
class JobSchedulingDisableServiceTest {

    @Test
    void invalidSecretShouldNotDisableOrCancelSchedules() {
        AdminPointSecretValidator validator = mock(AdminPointSecretValidator.class);
        JobExecutionLifecycle lifecycle = new JobExecutionLifecycle();
        JobScheduledTaskRegistry registry = mock(JobScheduledTaskRegistry.class);

        JobSchedulingDisableService.ExecutionResult result = service(
                validator, lifecycle, registry, Duration.ofMillis(20)).disable("wrong");

        assertThat(result.outcome()).isEqualTo(JobSchedulingDisableService.ExecutionOutcome.UNAUTHORIZED);
        assertThat(result.message()).isEqualTo("job 运维密钥无效");
        assertThat(result.data()).isNull();
        assertThat(lifecycle.snapshot().status()).isEqualTo(JobExecutionLifecycle.Status.ACCEPTING);
        verifyNoInteractions(registry);
    }

    @Test
    void idleInstanceShouldDisableImmediatelyAndExposeDefaultTenSecondTimeout() {
        JobExecutionLifecycle lifecycle = new JobExecutionLifecycle();
        JobScheduledTaskRegistry registry = mockRegistry();

        JobSchedulingDisableService.ExecutionResult result = service(
                validValidator(), lifecycle, registry, Duration.ofSeconds(10)).disable("secret");

        assertThat(result.outcome()).isEqualTo(JobSchedulingDisableService.ExecutionOutcome.DISABLED);
        assertThat(result.message()).isEqualTo("定时调度任务已停用，运行中任务已结束");
        assertThat(result.data().getStatus()).isEqualTo("DISABLED");
        assertThat(result.data().getActiveTaskCount()).isZero();
        assertThat(result.data().getTimeoutSeconds()).isEqualTo(10);
        verify(registry).cancelScheduledTasks();
    }

    @Test
    void subSecondTimeoutShouldRoundUpToOneSecondInResponse() {
        JobSchedulingDisableService.ExecutionResult result = service(
                validValidator(),
                new JobExecutionLifecycle(),
                mockRegistry(),
                Duration.ofMillis(500)).disable("secret");

        assertThat(result.data().getTimeoutSeconds()).isEqualTo(1);
    }

    @Test
    void timeoutShouldRemainDrainingAndLaterRetryShouldSucceed() {
        JobExecutionLifecycle lifecycle = new JobExecutionLifecycle();
        JobExecutionLifecycle.ExecutionPermit permit = lifecycle.tryAcquire().orElseThrow();
        JobScheduledTaskRegistry registry = mockRegistry();
        JobSchedulingDisableService service = service(
                validValidator(), lifecycle, registry, Duration.ofMillis(5));

        JobSchedulingDisableService.ExecutionResult timedOut = service.disable("secret");

        assertThat(timedOut.outcome()).isEqualTo(JobSchedulingDisableService.ExecutionOutcome.TIMEOUT);
        assertThat(timedOut.message()).isEqualTo("定时调度任务已停止接收新任务，但仍有运行中任务未结束");
        assertThat(timedOut.data().getStatus()).isEqualTo("DRAINING");
        assertThat(timedOut.data().getActiveTaskCount()).isOne();

        permit.close();
        JobSchedulingDisableService.ExecutionResult completed = service.disable("secret");

        assertThat(completed.outcome()).isEqualTo(JobSchedulingDisableService.ExecutionOutcome.DISABLED);
        assertThat(completed.data().getStatus()).isEqualTo("DISABLED");
        verify(registry, org.mockito.Mockito.times(2)).cancelScheduledTasks();
    }

    @Test
    void registryFailureShouldReturnUnavailableWithoutRestoringAdmission() {
        JobExecutionLifecycle lifecycle = new JobExecutionLifecycle();
        JobScheduledTaskRegistry registry = mock(JobScheduledTaskRegistry.class);
        when(registry.cancelScheduledTasks()).thenThrow(new IllegalStateException("missing"));

        JobSchedulingDisableService.ExecutionResult result = service(
                validValidator(), lifecycle, registry, Duration.ofMillis(20)).disable("secret");

        assertThat(result.outcome()).isEqualTo(JobSchedulingDisableService.ExecutionOutcome.UNAVAILABLE);
        assertThat(result.message()).isEqualTo("定时任务注册器暂不可用");
        assertThat(result.data().getStatus()).isEqualTo("DISABLED");
        assertThat(lifecycle.snapshot().status()).isEqualTo(JobExecutionLifecycle.Status.DISABLED);
    }

    @Test
    void interruptedWaitShouldRestoreInterruptFlag() {
        JobExecutionLifecycle lifecycle = new JobExecutionLifecycle();
        JobExecutionLifecycle.ExecutionPermit permit = lifecycle.tryAcquire().orElseThrow();
        JobSchedulingDisableService service = service(
                validValidator(), lifecycle, mockRegistry(), Duration.ofSeconds(1));
        try {
            Thread.currentThread().interrupt();

            JobSchedulingDisableService.ExecutionResult result = service.disable("secret");

            assertThat(result.outcome()).isEqualTo(JobSchedulingDisableService.ExecutionOutcome.UNAVAILABLE);
            assertThat(Thread.currentThread().isInterrupted()).isTrue();
        } finally {
            Thread.interrupted();
            permit.close();
        }
    }

    private JobSchedulingDisableService service(
            AdminPointSecretValidator validator,
            JobExecutionLifecycle lifecycle,
            JobScheduledTaskRegistry registry,
            Duration timeout
    ) {
        JobSchedulingProperties properties = new JobSchedulingProperties();
        properties.setDisableTimeout(timeout);
        properties.validate();
        return new JobSchedulingDisableService(validator, lifecycle, registry, properties);
    }

    private AdminPointSecretValidator validValidator() {
        AdminPointSecretValidator validator = mock(AdminPointSecretValidator.class);
        when(validator.isValid("secret")).thenReturn(true);
        return validator;
    }

    private JobScheduledTaskRegistry mockRegistry() {
        JobScheduledTaskRegistry registry = mock(JobScheduledTaskRegistry.class);
        when(registry.cancelScheduledTasks()).thenReturn(5);
        return registry;
    }
}
