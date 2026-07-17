package com.jxc.wefolio.job.task;

import com.jxc.wefolio.job.config.RechargeOrderCloseProperties;
import com.jxc.wefolio.job.service.RechargeOrderCloseService;
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

/**
 * 过期充值订单关闭调度测试。
 */
class RechargeOrderCloseJobTest {

    @Test
    void shouldScheduleAtSecondThirtyInShanghai() throws Exception {
        Method method = RechargeOrderCloseJob.class.getMethod("execute");
        Scheduled scheduled = method.getAnnotation(Scheduled.class);

        assertThat(scheduled).isNotNull();
        assertThat(scheduled.cron()).isEqualTo("${recharge-order-close.cron:30 * * * * ?}");
        assertThat(scheduled.zone()).isEqualTo("${recharge-order-close.zone:Asia/Shanghai}");
    }

    @Test
    void disabledJobShouldSkipExecution() {
        RechargeOrderCloseService service = mock(RechargeOrderCloseService.class);
        RechargeOrderCloseProperties properties = new RechargeOrderCloseProperties();
        properties.setEnabled(false);
        RechargeOrderCloseJob job = new RechargeOrderCloseJob(service, properties);

        job.execute();

        verify(service, never()).closeExpiredOrders();
    }

    @Test
    void runningJobShouldSkipReentry() throws Exception {
        RechargeOrderCloseService service = mock(RechargeOrderCloseService.class);
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        doAnswer(invocation -> {
            started.countDown();
            release.await(1, TimeUnit.SECONDS);
            return null;
        }).when(service).closeExpiredOrders();
        RechargeOrderCloseProperties properties = new RechargeOrderCloseProperties();
        properties.setEnabled(true);
        RechargeOrderCloseJob job = new RechargeOrderCloseJob(service, properties);
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

        verify(service).closeExpiredOrders();
    }
}
