package com.jxc.wefolio.job.service;

import com.jxc.wefolio.job.config.VideoFrameProperties;
import com.jxc.wefolio.job.model.VideoFrameBackfillModels.Request;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.core.task.TaskExecutor;
import org.springframework.scheduling.annotation.Scheduled;

import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** 无表运维入口的认证、单次预算、跨实例锁与异步排空语义。 */
class VideoFrameBackfillExecutionServiceTest {
    /** 入口只解析一次并传给整个历史批次的真实队列。 */
    private static final String QUEUE_ID = "queue-1";
    private AdminPointSecretValidator secrets;
    private VideoFrameBackfillService backfill;
    private VideoFrameSnapshotClient snapshots;
    private VideoFrameBackfillLock executionLocks;
    private VideoFrameBackfillLock.ExecutionLock executionLock;
    private TaskExecutor executor;
    private JobExecutionLifecycle lifecycle;
    private VideoFrameBackfillExecutionService service;

    @BeforeEach
    void setup() {
        secrets = mock(AdminPointSecretValidator.class);
        backfill = mock(VideoFrameBackfillService.class);
        snapshots = mock(VideoFrameSnapshotClient.class);
        executionLocks = mock(VideoFrameBackfillLock.class);
        executionLock = mock(VideoFrameBackfillLock.ExecutionLock.class);
        executor = mock(TaskExecutor.class);
        lifecycle = new JobExecutionLifecycle();
        service = new VideoFrameBackfillExecutionService(secrets, backfill, snapshots,
                new VideoFrameProperties(), lifecycle, executionLocks, executor);
        when(secrets.isValid("test-secret")).thenReturn(true);
        when(snapshots.resolveQueueId()).thenReturn(QUEUE_ID);
        when(executionLocks.tryAcquire()).thenReturn(Optional.of(executionLock));
    }

    @Test
    void unauthorizedDoesNotInspectQueueAcquireLocksOrExecute() {
        assertEquals(VideoFrameBackfillExecutionService.Outcome.UNAUTHORIZED, service.execute(null, null).outcome());
        verifyNoInteractions(backfill, snapshots, executionLocks, executor);
    }

    @Test
    void invalidBudgetDoesNotInspectCloudQueueOrAcquireLocks() {
        assertEquals(VideoFrameBackfillExecutionService.Outcome.INVALID,
                service.execute("test-secret", new Request(null, 7)).outcome());
        verifyNoInteractions(snapshots, executionLocks, executor, backfill);
    }

    /** 队列不可用在任何截图提交前失败，并释放锁及本机执行标识。 */
    @Test
    void queueResolutionFailureReturnsReasonAndAllowsNewExplicitExecution() {
        when(snapshots.resolveQueueId()).thenThrow(new VideoFrameProcessingException("FRAME_QUEUE", "没有可用媒体队列"));
        var result = service.execute("test-secret", null);
        assertEquals(VideoFrameBackfillExecutionService.Outcome.UNAVAILABLE, result.outcome());
        assertTrue(result.message().contains("FRAME_QUEUE"));
        assertTrue(result.message().contains("没有可用媒体队列"));
        verifyNoInteractions(executor, backfill);
        verify(executionLock).close();
        assertEquals(0, lifecycle.snapshot().activeTaskCount());
        doReturn(QUEUE_ID).when(snapshots).resolveQueueId();
        assertEquals(VideoFrameBackfillExecutionService.Outcome.ACCEPTED,
                service.execute("test-secret", null).outcome());
    }

    @Test
    void runIdIsExplicitlyRejectedWithoutPretendingPersistentResume() {
        var result = service.execute("test-secret", new Request(10L, 8));
        assertEquals(VideoFrameBackfillExecutionService.Outcome.INVALID, result.outcome());
        assertTrue(result.message().contains("不支持 runId 续跑"));
        verifyNoInteractions(snapshots, executionLocks, executor, backfill);
    }

    @Test
    void acceptanceKeepsLockAndLifecyclePermitUntilAsynchronousTaskFinishes() {
        var result = service.execute("test-secret", new Request(null, 8));
        assertEquals(VideoFrameBackfillExecutionService.Outcome.ACCEPTED, result.outcome());
        assertNotNull(result.data().executionId());
        assertNull(result.data().runId());
        var callback = ArgumentCaptor.forClass(Runnable.class);
        verify(executor).execute(callback.capture());
        var order = inOrder(executionLock, snapshots, executor);
        order.verify(executionLock).checkHeld();
        order.verify(snapshots).resolveQueueId();
        order.verify(executionLock).checkHeld();
        order.verify(executor).execute(any());
        verify(executionLock, never()).close();
        assertEquals(1, lifecycle.snapshot().activeTaskCount());
        callback.getValue().run();
        verify(backfill).run(result.data().executionId(), 8, executionLock, QUEUE_ID);
        verify(executionLock).close();
        assertEquals(0, lifecycle.snapshot().activeTaskCount());
    }

    @Test
    void localDuplicateReturnsSameExecutionIdWithoutTakingSecondLock() {
        var first = service.execute("test-secret", new Request(null, 8));
        var second = service.execute("test-secret", new Request(null, 8));
        assertEquals(VideoFrameBackfillExecutionService.Outcome.EXISTING, second.outcome());
        assertEquals(first.data().executionId(), second.data().executionId());
        verify(executionLocks, times(1)).tryAcquire();
        verify(snapshots, times(1)).resolveQueueId();
        verify(executor, times(1)).execute(any());
        assertEquals(1, lifecycle.snapshot().activeTaskCount());
    }

    /** 尚未完成队列准入的请求可能失败，并发请求不能收到从未被接受的执行编号。 */
    @Test
    void concurrentRequestDuringAdmissionDoesNotClaimUnacceptedExecution() {
        var concurrent = new AtomicReference<VideoFrameBackfillExecutionService.Result>();
        when(snapshots.resolveQueueId()).thenAnswer(invocation -> {
            concurrent.set(service.execute("test-secret", new Request(null, 8)));
            throw new VideoFrameProcessingException("FRAME_QUEUE", "队列暂不可用");
        });

        var first = service.execute("test-secret", new Request(null, 8));
        assertEquals(VideoFrameBackfillExecutionService.Outcome.UNAVAILABLE, first.outcome());
        assertEquals(VideoFrameBackfillExecutionService.Outcome.UNAVAILABLE, concurrent.get().outcome());
        assertNull(concurrent.get().data());
        verifyNoInteractions(backfill, executor);
        verify(executionLock).close();
        assertEquals(0, lifecycle.snapshot().activeTaskCount());
    }

    @Test
    void unknownRemoteLockOwnerReturnsUnavailableWithoutClaimingExistingBackfill() {
        when(executionLocks.tryAcquire()).thenReturn(Optional.empty());
        var result = service.execute("test-secret", new Request(null, 8));
        assertEquals(VideoFrameBackfillExecutionService.Outcome.UNAVAILABLE, result.outcome());
        assertNull(result.data());
        assertTrue(result.message().contains("未接受"));
        assertTrue(result.message().contains("重新提交"));
        verifyNoInteractions(snapshots, executor, backfill);
        assertEquals(0, lifecycle.snapshot().activeTaskCount());
    }

    @Test
    void executorRejectReleasesPermitAndSessionLockAndAllowsNewExplicitExecution() {
        doThrow(new IllegalStateException("executor busy")).when(executor).execute(any());
        assertEquals(VideoFrameBackfillExecutionService.Outcome.UNAVAILABLE,
                service.execute("test-secret", new Request(null, 8)).outcome());
        verify(executionLock).close();
        assertEquals(0, lifecycle.snapshot().activeTaskCount());
        reset(executor);
        assertEquals(VideoFrameBackfillExecutionService.Outcome.ACCEPTED,
                service.execute("test-secret", new Request(null, 8)).outcome());
    }

    @Test
    void failedLockAcquisitionDoesNotSubmitWorkAndReleasesPermit() {
        when(executionLocks.tryAcquire()).thenThrow(new IllegalStateException("DB unavailable"));
        assertEquals(VideoFrameBackfillExecutionService.Outcome.UNAVAILABLE,
                service.execute("test-secret", new Request(null, 8)).outcome());
        verifyNoInteractions(snapshots, executor, backfill);
        assertEquals(0, lifecycle.snapshot().activeTaskCount());
    }

    /** 云端查询期间失去跨实例锁时不能派发历史截图批次。 */
    @Test
    void lostLockAfterQueueResolutionDoesNotDispatchAndReleasesPermit() {
        doNothing().doThrow(new IllegalStateException("lock lost")).when(executionLock).checkHeld();
        assertEquals(VideoFrameBackfillExecutionService.Outcome.UNAVAILABLE,
                service.execute("test-secret", null).outcome());
        verify(snapshots).resolveQueueId();
        verifyNoInteractions(executor, backfill);
        verify(executionLock).close();
        assertEquals(0, lifecycle.snapshot().activeTaskCount());
    }

    @Test
    void asynchronousFailureStillReleasesSessionLockAndLifecyclePermit() {
        doThrow(new IllegalStateException("worker failed")).when(backfill).run(anyString(), anyInt(), any(), eq(QUEUE_ID));
        service.execute("test-secret", new Request(null, 8));
        var callback = ArgumentCaptor.forClass(Runnable.class);
        verify(executor).execute(callback.capture());
        callback.getValue().run();
        verify(executionLock).close();
        assertEquals(0, lifecycle.snapshot().activeTaskCount());
    }

    /** 补偿由持续调度驱动，批次正在读取原始帧时不得交叉清理。 */
    @Test
    void cleanupIsScheduledAndCannotRunWhileBackfillOwnsLocalExecution() throws Exception {
        var scheduled = VideoFrameBackfillExecutionService.class.getMethod("cleanupFinishedSnapshots").getAnnotation(Scheduled.class);
        assertNotNull(scheduled);
        assertEquals(60000, scheduled.fixedDelay());
        service.execute("test-secret", new Request(null, 8));
        service.cleanupFinishedSnapshots();
        verify(executionLocks, times(1)).tryAcquire();
        verify(snapshots, never()).cleanupCompletedOutputs(any());
        verify(snapshots, never()).cleanupExpiredOutputs(any());
    }

    /** 补偿异步结束前保持命名锁和排空凭证，不生成新的付费截图。 */
    @Test
    void maintenanceKeepsPermitUntilCleanupAndFallsBackWhenProviderHistoryFails() {
        doThrow(new VideoFrameProcessingException("FRAME_CLEANUP", "供应商历史不可查"))
                .when(snapshots).cleanupCompletedOutputs(executionLock);
        service.cleanupFinishedSnapshots();
        var callback = ArgumentCaptor.forClass(Runnable.class);
        verify(executor).execute(callback.capture());
        assertEquals(1, lifecycle.snapshot().activeTaskCount());
        verify(executionLock, never()).close();
        callback.getValue().run();
        verify(snapshots).cleanupCompletedOutputs(executionLock);
        verify(snapshots).cleanupExpiredOutputs(executionLock);
        verify(executionLock).close();
        verify(snapshots, never()).resolveQueueId();
        verifyNoInteractions(backfill);
        assertEquals(0, lifecycle.snapshot().activeTaskCount());
    }

    /** 清扫占用执行器时明确告知未接受，不能冒充已存在的付费补帧或返回清扫编号。 */
    @Test
    void maintenanceRejectsExplicitBackfillClearlyAndAllowsRetryAfterCleanup() {
        service.cleanupFinishedSnapshots();
        var callback = ArgumentCaptor.forClass(Runnable.class);
        verify(executor).execute(callback.capture());

        var rejected = service.execute("test-secret", new Request(null, 8));
        assertEquals(VideoFrameBackfillExecutionService.Outcome.UNAVAILABLE, rejected.outcome());
        assertNull(rejected.data());
        assertTrue(rejected.message().contains("清扫"));
        assertTrue(rejected.message().contains("重新提交"));
        verify(executionLocks, times(1)).tryAcquire();
        verify(executor, times(1)).execute(any());
        verifyNoInteractions(backfill);
        assertEquals(1, lifecycle.snapshot().activeTaskCount());

        callback.getValue().run();
        var accepted = service.execute("test-secret", new Request(null, 8));
        assertEquals(VideoFrameBackfillExecutionService.Outcome.ACCEPTED, accepted.outcome());
        assertTrue(accepted.data().accepted());
        assertFalse(accepted.data().executionId().startsWith("cleanup-"));
        var duplicate = service.execute("test-secret", new Request(null, 8));
        assertEquals(VideoFrameBackfillExecutionService.Outcome.EXISTING, duplicate.outcome());
        assertEquals(accepted.data().executionId(), duplicate.data().executionId());
    }

    /** 清扫派发失败时也释放本机状态，后续运维提交能够真正接受。 */
    @Test
    void rejectedMaintenanceDoesNotBlockNextExplicitSubmission() {
        doThrow(new IllegalStateException("executor busy")).when(executor).execute(any());
        service.cleanupFinishedSnapshots();
        assertEquals(0, lifecycle.snapshot().activeTaskCount());
        verify(executionLock).close();

        reset(executor);
        var accepted = service.execute("test-secret", new Request(null, 8));
        assertEquals(VideoFrameBackfillExecutionService.Outcome.ACCEPTED, accepted.outcome());
        assertFalse(accepted.data().executionId().startsWith("cleanup-"));
    }

    /** 任一实例持有补帧锁时不读取供应商清单或 COS 清理目录。 */
    @Test
    void maintenanceSkipsWhenAnotherInstanceConsumesRawFrames() {
        when(executionLocks.tryAcquire()).thenReturn(Optional.empty());
        service.cleanupFinishedSnapshots();
        verifyNoInteractions(executor, snapshots);
        assertEquals(0, lifecycle.snapshot().activeTaskCount());
    }
}
