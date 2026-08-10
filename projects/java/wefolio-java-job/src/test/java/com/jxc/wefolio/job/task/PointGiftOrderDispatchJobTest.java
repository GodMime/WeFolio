package com.jxc.wefolio.job.task;

import com.jxc.wefolio.job.config.VirtualPaymentDispatchProperties;
import com.jxc.wefolio.job.config.VirtualPaymentSchedulingConfig;
import com.jxc.wefolio.job.service.VirtualPaymentDispatchService;
import com.jxc.wefolio.job.service.VirtualPaymentTaskDispatchSummary;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
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
 * 赠送订单分发调度器测试。
 */
@ExtendWith(OutputCaptureExtension.class)
class PointGiftOrderDispatchJobTest {

    /** 调度必须使用上一轮结束后的固定延迟。 */
    @Test
    void shouldUseConfiguredFixedDelay() throws Exception {
        Method method = PointGiftOrderDispatchJob.class.getMethod("execute");
        Scheduled scheduled = method.getAnnotation(Scheduled.class);

        assertThat(scheduled).isNotNull();
        assertThat(scheduled.fixedDelayString())
                .isEqualTo("${virtual-payment-dispatch.gift-scan-interval:60s}");
        assertThat(scheduled.scheduler())
                .isEqualTo(VirtualPaymentSchedulingConfig.TASK_SCHEDULER_BEAN_NAME);
    }

    /** 关闭配置时不得委派。 */
    @Test
    void disabledJobShouldSkipDispatchAndLogTrigger(CapturedOutput output) {
        VirtualPaymentDispatchService service = mock(VirtualPaymentDispatchService.class);

        new PointGiftOrderDispatchJob(service, properties(false)).execute();

        verify(service, never()).dispatchGiftOrders();
        assertThat(output).contains("operation=赠送订单候选分发 result=STARTED enabled=false")
                .contains("operation=赠送订单候选分发 result=SKIPPED_DISABLED");
    }

    /** 开启配置时委派一次。 */
    @Test
    void enabledJobShouldDispatchGiftOrdersAndLogSummary(CapturedOutput output) {
        VirtualPaymentDispatchService service = mock(VirtualPaymentDispatchService.class);
        when(service.dispatchGiftOrders()).thenReturn(
                new VirtualPaymentTaskDispatchSummary(10, 10, 8, 1, 1));

        new PointGiftOrderDispatchJob(service, properties(true)).execute();

        verify(service).dispatchGiftOrders();
        assertThat(output).contains("operation=赠送订单候选分发 result=STARTED enabled=true")
                .contains("operation=赠送订单候选分发 result=COMPLETED")
                .contains("candidateCount=10 requestCount=10 processedCount=8 "
                        + "skippedCount=1 failedCount=1 durationMs=");
    }

    /** 上一轮未结束时必须跳过同实例重入。 */
    @Test
    void runningJobShouldSkipReentry() throws Exception {
        VirtualPaymentDispatchService service = mock(VirtualPaymentDispatchService.class);
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        doAnswer(invocation -> {
            started.countDown();
            release.await(1, TimeUnit.SECONDS);
            return new VirtualPaymentTaskDispatchSummary(0, 0, 0, 0, 0);
        }).when(service).dispatchGiftOrders();
        PointGiftOrderDispatchJob job = new PointGiftOrderDispatchJob(service, properties(true));
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            executor.submit(job::execute);
            assertThat(started.await(1, TimeUnit.SECONDS)).isTrue();
            job.execute();
            release.countDown();
            executor.shutdown();
            assertThat(executor.awaitTermination(1, TimeUnit.SECONDS)).isTrue();
        } finally {
            executor.shutdownNow();
        }

        verify(service).dispatchGiftOrders();
    }

    /** 构造启停配置。 */
    private VirtualPaymentDispatchProperties properties(boolean enabled) {
        VirtualPaymentDispatchProperties properties = new VirtualPaymentDispatchProperties();
        properties.setEnabled(enabled);
        return properties;
    }
}
