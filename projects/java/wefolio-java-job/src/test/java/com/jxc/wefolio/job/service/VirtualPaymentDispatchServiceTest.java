package com.jxc.wefolio.job.service;

import com.jxc.wefolio.job.config.VirtualPaymentDispatchProperties;
import com.jxc.wefolio.job.repo.VirtualPaymentCandidateRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 微信虚拟支付候选任务分发服务测试。
 */
class VirtualPaymentDispatchServiceTest {

    private ExecutorService executor;

    @AfterEach
    void tearDown() {
        if (executor != null) {
            executor.shutdown();
        }
    }

    /** 单条赠送异常不阻断同轮其他 ID，并正确汇总处理、跳过和失败数量。 */
    @Test
    void dispatchGiftOrdersShouldSummarizeEveryCandidateOnce() {
        VirtualPaymentCandidateRepository repository = mock(VirtualPaymentCandidateRepository.class);
        RuntimeVirtualPaymentTaskClient client = mock(RuntimeVirtualPaymentTaskClient.class);
        when(repository.findDueGiftOrderIds(100)).thenReturn(List.of(17L, 18L, 19L));
        when(client.executeGiftOrder(17L)).thenReturn(executionResult("PROCESSED"));
        when(client.executeGiftOrder(18L)).thenReturn(executionResult("SKIPPED_NOT_CLAIMABLE"));
        doThrow(new IllegalStateException("runtime timeout")).when(client).executeGiftOrder(19L);

        VirtualPaymentTaskDispatchSummary summary = service(repository, client).dispatchGiftOrders();

        assertThat(summary).isEqualTo(new VirtualPaymentTaskDispatchSummary(3, 3, 1, 1, 1));
        verify(client, times(1)).executeGiftOrder(17L);
        verify(client, times(1)).executeGiftOrder(18L);
        verify(client, times(1)).executeGiftOrder(19L);
    }

    /** 扣币分发必须先请求 runtime 恢复缺失任务，再逐条执行候选任务。 */
    @Test
    void dispatchDebitTasksShouldRecoverAndSummarizeCandidatesOnce() {
        VirtualPaymentCandidateRepository repository = mock(VirtualPaymentCandidateRepository.class);
        RuntimeVirtualPaymentTaskClient client = mock(RuntimeVirtualPaymentTaskClient.class);
        when(repository.findUsersMissingActiveDebitTasks(100)).thenReturn(List.of(7L, 8L));
        when(repository.findDueDebitTaskIds(100)).thenReturn(List.of(23L, 24L));
        when(client.recoverDebitTask(7L)).thenReturn(recoveryResult("ACTIVE_TASK_ENSURED"));
        doThrow(new IllegalStateException("runtime timeout")).when(client).recoverDebitTask(8L);
        when(client.executeDebitTask(23L)).thenReturn(executionResult("PROCESSED"));
        when(client.executeDebitTask(24L)).thenReturn(executionResult("SKIPPED_DISABLED"));

        VirtualPaymentDebitDispatchSummary summary = service(repository, client).dispatchDebitTasks();

        assertThat(summary).isEqualTo(new VirtualPaymentDebitDispatchSummary(
                2, 2, 1, new VirtualPaymentTaskDispatchSummary(2, 2, 1, 1, 0)));
        verify(client, times(1)).recoverDebitTask(7L);
        verify(client, times(1)).recoverDebitTask(8L);
        verify(client, times(1)).executeDebitTask(23L);
        verify(client, times(1)).executeDebitTask(24L);
    }

    /** 关闭时取消排队任务，但不得中断已经开始的 runtime 请求。 */
    @Test
    void shutdownShouldCancelQueuedTasksWithoutInterruptingActiveRequest() throws Exception {
        VirtualPaymentCandidateRepository repository = mock(VirtualPaymentCandidateRepository.class);
        RuntimeVirtualPaymentTaskClient client = mock(RuntimeVirtualPaymentTaskClient.class);
        when(repository.findDueGiftOrderIds(100)).thenReturn(List.of(17L, 18L));
        CountDownLatch activeStarted = new CountDownLatch(1);
        CountDownLatch releaseActive = new CountDownLatch(1);
        AtomicBoolean interrupted = new AtomicBoolean(false);
        doAnswer(invocation -> {
            long orderId = invocation.getArgument(0);
            if (orderId == 17L) {
                activeStarted.countDown();
                try {
                    releaseActive.await(2, TimeUnit.SECONDS);
                } catch (InterruptedException exception) {
                    interrupted.set(true);
                    Thread.currentThread().interrupt();
                }
            }
            return executionResult("PROCESSED");
        }).when(client).executeGiftOrder(anyLong());
        VirtualPaymentDispatchProperties properties = new VirtualPaymentDispatchProperties();
        properties.setRequestTimeout(Duration.ofMillis(200));
        executor = Executors.newFixedThreadPool(1);
        VirtualPaymentDispatchService service =
                new VirtualPaymentDispatchService(repository, client, properties, executor);
        ExecutorService control = Executors.newFixedThreadPool(2);
        try {
            Future<?> round = control.submit(service::dispatchGiftOrders);
            assertThat(activeStarted.await(1, TimeUnit.SECONDS)).isTrue();
            Future<?> stopping = control.submit(service::shutdown);
            assertThat(awaitShutdown(executor)).isTrue();
            assertThat(awaitQueuedCancellation((ThreadPoolExecutor) executor)).isTrue();
            releaseActive.countDown();
            stopping.get(1, TimeUnit.SECONDS);
            round.get(1, TimeUnit.SECONDS);

            verify(client).executeGiftOrder(17L);
            verify(client, never()).executeGiftOrder(18L);
            assertThat(interrupted).isFalse();
        } finally {
            releaseActive.countDown();
            control.shutdownNow();
        }
    }

    /** 调度线程先被 Spring 中断时，Service 销毁仍必须取消尚未开始的请求。 */
    @Test
    void shutdownAfterSchedulerInterruptionShouldStillCancelQueuedTasks() throws Exception {
        VirtualPaymentCandidateRepository repository = mock(VirtualPaymentCandidateRepository.class);
        RuntimeVirtualPaymentTaskClient client = mock(RuntimeVirtualPaymentTaskClient.class);
        when(repository.findDueGiftOrderIds(100)).thenReturn(List.of(17L, 18L));
        CountDownLatch activeStarted = new CountDownLatch(1);
        CountDownLatch releaseActive = new CountDownLatch(1);
        doAnswer(invocation -> {
            if (invocation.<Long>getArgument(0) == 17L) {
                activeStarted.countDown();
                releaseActive.await(2, TimeUnit.SECONDS);
            }
            return executionResult("PROCESSED");
        }).when(client).executeGiftOrder(anyLong());
        VirtualPaymentDispatchProperties properties = new VirtualPaymentDispatchProperties();
        properties.setRequestTimeout(Duration.ofMillis(200));
        executor = Executors.newFixedThreadPool(1);
        VirtualPaymentDispatchService service =
                new VirtualPaymentDispatchService(repository, client, properties, executor);
        ExecutorService control = Executors.newFixedThreadPool(2);
        try {
            Future<?> round = control.submit(service::dispatchGiftOrders);
            assertThat(activeStarted.await(1, TimeUnit.SECONDS)).isTrue();
            assertThat(round.cancel(true)).isTrue();

            Future<?> stopping = control.submit(service::shutdown);
            assertThat(awaitShutdown(executor)).isTrue();
            assertThat(awaitQueuedCancellation((ThreadPoolExecutor) executor)).isTrue();
            releaseActive.countDown();
            stopping.get(1, TimeUnit.SECONDS);

            verify(client).executeGiftOrder(17L);
            verify(client, never()).executeGiftOrder(18L);
        } finally {
            releaseActive.countDown();
            control.shutdownNow();
        }
    }

    /** 构造使用测试线程池的分发服务。 */
    private VirtualPaymentDispatchService service(
            VirtualPaymentCandidateRepository repository,
            RuntimeVirtualPaymentTaskClient client
    ) {
        VirtualPaymentDispatchProperties properties = new VirtualPaymentDispatchProperties();
        executor = Executors.newFixedThreadPool(2);
        return new VirtualPaymentDispatchService(repository, client, properties, executor);
    }

    /** 构造指定 outcome 的完整 runtime 任务执行结果。 */
    private RuntimeVirtualPaymentTaskClient.TaskExecutionResult executionResult(String outcome) {
        RuntimeVirtualPaymentTaskClient.TaskExecutionResult result =
                new RuntimeVirtualPaymentTaskClient.TaskExecutionResult();
        result.setTargetId(17L);
        result.setTaskType("GIFT_ORDER");
        result.setOutcome(outcome);
        return result;
    }

    /** 构造指定 outcome 的完整 runtime 扣币恢复结果。 */
    private RuntimeVirtualPaymentTaskClient.DebitTaskRecoveryResult recoveryResult(String outcome) {
        RuntimeVirtualPaymentTaskClient.DebitTaskRecoveryResult result =
                new RuntimeVirtualPaymentTaskClient.DebitTaskRecoveryResult();
        result.setUserId(7L);
        result.setTaskId(31L);
        result.setOutcome(outcome);
        return result;
    }

    /** 在一秒内等待分发线程池进入关闭状态。 */
    private boolean awaitShutdown(ExecutorService target) {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(1);
        while (!target.isShutdown() && System.nanoTime() < deadline) {
            Thread.onSpinWait();
        }
        return target.isShutdown();
    }

    /** 等待线程池中的排队 Future 被标记为取消。 */
    private boolean awaitQueuedCancellation(ThreadPoolExecutor target) {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(1);
        while (System.nanoTime() < deadline) {
            boolean cancelled = target.getQueue().stream()
                    .filter(Future.class::isInstance)
                    .map(Future.class::cast)
                    .anyMatch(Future::isCancelled);
            if (cancelled) {
                return true;
            }
            Thread.onSpinWait();
        }
        return false;
    }
}
