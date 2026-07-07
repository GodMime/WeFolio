package com.jxc.wefolio.job.task;

import com.jxc.wefolio.job.config.WorkAuditProperties;
import com.jxc.wefolio.job.service.WorkAuditService;
import org.junit.jupiter.api.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * 作品审核调度任务测试。
 */
class WorkAuditJobTest {

    @Test
    void disabledJobShouldSkipRun() {
        WorkAuditService service = mock(WorkAuditService.class);
        WorkAuditProperties properties = new WorkAuditProperties();
        properties.setEnabled(false);
        WorkAuditJob job = new WorkAuditJob(service, properties);

        job.run();

        verify(service, never()).runOneRound();
    }

    @Test
    void runningJobShouldSkipReentry() throws Exception {
        WorkAuditService service = mock(WorkAuditService.class);
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        doAnswer(invocation -> {
            started.countDown();
            release.await(1, TimeUnit.SECONDS);
            return null;
        }).when(service).runOneRound();
        WorkAuditProperties properties = new WorkAuditProperties();
        properties.setEnabled(true);
        WorkAuditJob job = new WorkAuditJob(service, properties);
        ExecutorService executor = Executors.newSingleThreadExecutor();

        try {
            executor.submit(job::run);
            started.await(1, TimeUnit.SECONDS);
            job.run();
            release.countDown();
            executor.shutdown();
            executor.awaitTermination(1, TimeUnit.SECONDS);
        } finally {
            executor.shutdownNow();
        }

        verify(service).runOneRound();
    }
}
