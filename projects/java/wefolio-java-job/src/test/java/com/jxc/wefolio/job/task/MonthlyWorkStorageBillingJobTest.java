package com.jxc.wefolio.job.task;

import com.jxc.wefolio.job.config.WorkStorageBillingProperties;
import com.jxc.wefolio.job.service.WorkStorageBillingExecutionCoordinator;
import org.junit.jupiter.api.Test;
import org.springframework.scheduling.annotation.Scheduled;

import java.lang.reflect.Method;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * 月度作品存储调度测试。
 */
class MonthlyWorkStorageBillingJobTest {

    @Test
    void januaryRunShouldSubmitPreviousDecember() {
        WorkStorageBillingExecutionCoordinator coordinator = mock(WorkStorageBillingExecutionCoordinator.class);
        WorkStorageBillingProperties properties = properties(true);
        Clock clock = Clock.fixed(Instant.parse("2026-12-31T18:00:00Z"), ZoneId.of("Asia/Shanghai"));

        new MonthlyWorkStorageBillingJob(coordinator, properties, clock).execute();

        verify(coordinator).submit(
                LocalDate.of(2026, 12, 1), WorkStorageBillingExecutionCoordinator.TriggerSource.SCHEDULED);
    }

    @Test
    void disabledJobShouldSkipSubmission() {
        WorkStorageBillingExecutionCoordinator coordinator = mock(WorkStorageBillingExecutionCoordinator.class);

        new MonthlyWorkStorageBillingJob(coordinator, properties(false), Clock.systemUTC()).execute();

        verify(coordinator, never()).submit(
                org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any());
    }

    @Test
    void jobShouldUseConfiguredCronAndZone() throws NoSuchMethodException {
        Method method = MonthlyWorkStorageBillingJob.class.getMethod("execute");
        Scheduled scheduled = method.getAnnotation(Scheduled.class);

        assertThat(scheduled.cron()).isEqualTo("${work-storage-billing.cron:0 0 2 1 * ?}");
        assertThat(scheduled.zone()).isEqualTo("${work-storage-billing.zone:Asia/Shanghai}");
    }

    private WorkStorageBillingProperties properties(boolean enabled) {
        WorkStorageBillingProperties properties = new WorkStorageBillingProperties();
        properties.setEnabled(enabled);
        properties.setZone("Asia/Shanghai");
        return properties;
    }
}
