package com.jxc.wefolio.job.task;

import com.jxc.wefolio.job.config.FeedbackUploadCleanupProperties;
import com.jxc.wefolio.job.config.FeedbackUploadCleanupSchedulingConfig;
import com.jxc.wefolio.job.service.FeedbackUploadCleanupService;
import org.junit.jupiter.api.Test;
import org.springframework.scheduling.annotation.Scheduled;

import java.lang.reflect.Method;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 反馈附件过期清理调度测试。
 */
class FeedbackUploadCleanupJobTest {

    /** 验证任务使用低峰期表达式、上海时区和独立调度器。 */
    @Test
    void shouldUseLowTrafficCronAndShanghaiZone() throws Exception {
        Method method = FeedbackUploadCleanupJob.class.getMethod("execute");
        Scheduled scheduled = method.getAnnotation(Scheduled.class);

        assertThat(scheduled).isNotNull();
        assertThat(scheduled.cron())
                .isEqualTo("${feedback-upload-cleanup.cron:0 40 3 * * ?}");
        assertThat(scheduled.zone())
                .isEqualTo("${feedback-upload-cleanup.zone:Asia/Shanghai}");
        assertThat(scheduled.scheduler())
                .isEqualTo(FeedbackUploadCleanupSchedulingConfig.TASK_SCHEDULER_BEAN_NAME);
    }

    /** 验证关闭开关时不执行清理。 */
    @Test
    void disabledJobShouldSkipExecution() {
        FeedbackUploadCleanupService service = mock(FeedbackUploadCleanupService.class);
        FeedbackUploadCleanupProperties properties = properties(false);

        new FeedbackUploadCleanupJob(service, properties).execute();

        verify(service, never()).cleanupExpiredUploads();
    }

    /** 验证开启开关时每次调度执行一次清理。 */
    @Test
    void enabledJobShouldExecuteCleanupOnce() {
        FeedbackUploadCleanupService service = mock(FeedbackUploadCleanupService.class);
        FeedbackUploadCleanupProperties properties = properties(true);
        when(service.cleanupExpiredUploads()).thenReturn(
                new FeedbackUploadCleanupService.CleanupResult(2, 1, 1, 0, 1, false));

        new FeedbackUploadCleanupJob(service, properties).execute();

        verify(service).cleanupExpiredUploads();
    }

    /** 验证上一轮仍在运行时跳过重入。 */
    @Test
    void runningJobShouldSkipReentry() throws Exception {
        FeedbackUploadCleanupService service = mock(FeedbackUploadCleanupService.class);
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        doAnswer(invocation -> {
            started.countDown();
            release.await(1, TimeUnit.SECONDS);
            return new FeedbackUploadCleanupService.CleanupResult(0, 0, 0, 0, 0, false);
        }).when(service).cleanupExpiredUploads();
        FeedbackUploadCleanupJob job = new FeedbackUploadCleanupJob(service, properties(true));
        ExecutorService executor = Executors.newSingleThreadExecutor();

        try {
            executor.submit(job::execute);
            assertThat(started.await(1, TimeUnit.SECONDS)).isTrue();
            job.execute();
            release.countDown();
            executor.shutdown();
            assertThat(executor.awaitTermination(1, TimeUnit.SECONDS)).isTrue();
        } finally {
            release.countDown();
            executor.shutdownNow();
        }

        verify(service).cleanupExpiredUploads();
    }

    /** 创建指定启用状态的任务配置。 */
    private FeedbackUploadCleanupProperties properties(boolean enabled) {
        FeedbackUploadCleanupProperties properties = new FeedbackUploadCleanupProperties();
        properties.setEnabled(enabled);
        return properties;
    }
}
