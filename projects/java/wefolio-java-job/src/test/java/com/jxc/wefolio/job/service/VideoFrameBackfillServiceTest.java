package com.jxc.wefolio.job.service;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.jxc.wefolio.job.config.VideoFrameProperties;
import com.jxc.wefolio.job.model.VideoFrameBackfillModels.Work;
import com.jxc.wefolio.job.repo.VideoFrameBackfillRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** 无任务表历史扫描的完成幂等、日志、成本、发布未知与失锁边界。 */
class VideoFrameBackfillServiceTest {
    private static final String EXECUTION_ID = "execution-1";
    /** 本次批次由入口冻结的真实队列。 */
    private static final String QUEUE_ID = "queue-1";
    private static final String MANIFEST = "{\"attempt\":\"new\"}";
    private VideoFrameBackfillRepository repository;
    private VideoFrameStorageService storage;
    private VideoFrameSnapshotClient snapshots;
    private VideoFrameBackfillLock.ExecutionLock executionLock;
    private VideoFrameBackfillService service;
    private ListAppender<ILoggingEvent> events;
    private final VideoFrameSource source = new VideoFrameSource("OWNER/work/video/a.mp4", "a".repeat(64), "etag", 1000);
    private final Work work = new Work(1, source.objectKey(), "sha", 23000, null, 0, "OWNER");

    @BeforeEach
    void setup() {
        repository = mock(VideoFrameBackfillRepository.class);
        storage = mock(VideoFrameStorageService.class);
        snapshots = mock(VideoFrameSnapshotClient.class);
        executionLock = mock(VideoFrameBackfillLock.ExecutionLock.class);
        service = new VideoFrameBackfillService(repository, storage, snapshots, new VideoFrameProperties());
        when(repository.highWatermark()).thenReturn(1L);
        when(repository.scan(0, 1, 20)).thenReturn(List.of(work));
        when(repository.scan(1, 1, 20)).thenReturn(List.of());
        when(repository.ownedSource(any())).thenReturn(true);
        when(storage.inspectSource(anyString(), any())).thenReturn(source);
        when(storage.targetPrefix(eq(source), anyLong(), anyString())).thenReturn("new-prefix/");
        when(snapshots.submit(eq(source), eq(8), anyString(), eq(QUEUE_ID))).thenReturn("j-new");
        when(snapshots.ready("j-new")).thenReturn(true);
        when(snapshots.outputFrames(anyString(), eq(8))).thenReturn(List.of());
        when(storage.persist(eq(source), anyLong(), anyString(), any(), eq(8), anyList(), any())).thenReturn(MANIFEST);
        when(repository.saveManifest(any(), eq(MANIFEST))).thenReturn(true);
        events = new ListAppender<>();
        events.start();
        ((Logger) LoggerFactory.getLogger(VideoFrameBackfillService.class)).addAppender(events);
    }

    @AfterEach
    void detachLogs() {
        ((Logger) LoggerFactory.getLogger(VideoFrameBackfillService.class)).detachAppender(events);
        events.stop();
    }

    @Test
    void savesWholeManifestWithFrontendDurationAndLogsActualSuccess() {
        var result = service.run(EXECUTION_ID, 8, executionLock, QUEUE_ID);
        assertEquals(1, result.succeeded());
        assertEquals(8, result.chargedFrames());
        verify(repository).saveManifest(work, MANIFEST);
        verify(storage).persist(eq(source), eq(1L), anyString(), eq(23000), eq(8), anyList(), any());
        var order = inOrder(storage, repository);
        order.verify(storage).verifySource(eq(source), any());
        order.verify(repository).saveManifest(work, MANIFEST);
        assertTrue(logged(Level.INFO, "历史视频补帧成功", "executionId=execution-1", "workId=1", "stage=MANIFEST_SAVE"));
        verify(storage, never()).cleanupOwnedManifest(anyString(), any(), any());
    }

    @Test
    void validExistingTwelveFramesAreSkippedWithoutResamplingAndLogged() {
        when(storage.hasValidManifest(isNull(), eq(source), eq(1L), any())).thenReturn(true);
        var result = service.run(EXECUTION_ID, 8, executionLock, QUEUE_ID);
        assertEquals(1, result.skipped());
        assertEquals(0, result.chargedFrames());
        verifyNoInteractions(snapshots);
        verify(repository, never()).saveManifest(any(), anyString());
        assertTrue(logged(Level.INFO, "历史视频补帧跳过", "executionId=execution-1", "workId=1"));
    }

    @Test
    void frameFailureLogsStageAndContinuesToNextWorkWithoutChangingAudit() {
        addSecondWork();
        when(storage.persist(eq(source), eq(1L), anyString(), any(), eq(8), anyList(), any()))
                .thenThrow(new VideoFrameProcessingException("FRAME_UPLOAD", "上传失败"));
        var result = service.run(EXECUTION_ID, 16, executionLock, QUEUE_ID);
        assertEquals(1, result.failed());
        assertEquals(1, result.succeeded());
        assertEquals(16, result.chargedFrames());
        verify(snapshots, times(2)).submit(eq(source), eq(8), anyString(), eq(QUEUE_ID));
        verify(snapshots, never()).resolveQueueId();
        verify(repository, never()).saveManifest(eq(work), anyString());
        assertTrue(logged(Level.ERROR, "历史视频补帧失败", "executionId=execution-1", "workId=1", "stage=FRAME_UPLOAD", "上传失败"));
    }

    @Test
    void commitSucceededButResponseLostNeverDeletesPublishedFrames() {
        when(repository.saveManifest(work, MANIFEST)).thenThrow(new IllegalStateException("response lost"));
        when(repository.manifestPublished(1, MANIFEST)).thenReturn(true);
        var result = service.run(EXECUTION_ID, 8, executionLock, QUEUE_ID);
        assertEquals(1, result.succeeded());
        verify(storage, never()).cleanupOwnedManifest(anyString(), any(), any());
        assertTrue(logged(Level.INFO, "历史视频补帧成功", "workId=1"));
    }

    @Test
    void databaseUnknownPreservesObjectsAndLogsFailure() {
        when(repository.saveManifest(work, MANIFEST)).thenThrow(new IllegalStateException("response lost"));
        when(repository.manifestPublished(1, MANIFEST)).thenThrow(new IllegalStateException("DB down"));
        var result = service.run(EXECUTION_ID, 8, executionLock, QUEUE_ID);
        assertEquals(1, result.failed());
        verify(storage, never()).cleanupOwnedManifest(anyString(), any(), any());
        assertTrue(logged(Level.ERROR, "历史视频帧保存结果未知", "executionId=execution-1", "workId=1", "stage=MANIFEST_SAVE"));
    }

    @Test
    void confirmedRollbackCleansOnlyAttemptManifestAndLogsFailure() {
        when(repository.saveManifest(work, MANIFEST)).thenReturn(false);
        var result = service.run(EXECUTION_ID, 8, executionLock, QUEUE_ID);
        assertEquals(1, result.failed());
        verify(storage).cleanupOwnedManifest(MANIFEST, source, 1L);
        assertTrue(logged(Level.ERROR, "历史视频补帧失败", "stage=MANIFEST_SAVE"));
    }

    @Test
    void rawCleanupAfterSuccessfulPublishCannotTurnSuccessIntoFailure() {
        doThrow(new IllegalStateException("cleanup failed")).when(snapshots).cleanupOutput(anyString(), eq(8));
        var result = service.run(EXECUTION_ID, 8, executionLock, QUEUE_ID);
        assertEquals(1, result.succeeded());
        assertEquals(0, result.failed());
    }

    @Test
    void unknownSubmitIsNotRepeatedAndKeepsReservationBeforeNextWorkHitsBudget() {
        addSecondWork();
        when(snapshots.submit(eq(source), eq(8), anyString(), eq(QUEUE_ID)))
                .thenThrow(new VideoFrameProcessingException("FRAME_SUBMIT", "提交结果未知"));
        var result = service.run(EXECUTION_ID, 8, executionLock, QUEUE_ID);
        assertEquals(1, result.failed());
        assertEquals(8, result.chargedFrames());
        assertTrue(result.budgetExhausted());
        verify(snapshots, times(1)).submit(eq(source), eq(8), anyString(), eq(QUEUE_ID));
        verify(snapshots, never()).ready(anyString());
        verify(snapshots, never()).cleanupOutput(anyString(), anyInt());
        verify(repository, never()).saveManifest(any(), anyString());
    }

    @Test
    void successfulReplacementCleansOnlyOldInvalidManifestWhenNewOneIsCurrent() {
        String oldManifest = "{\"attempt\":\"old\"}";
        Work previous = new Work(work.id(), work.objectKey(), work.sha256(), work.durationMs(), oldManifest, work.version(), work.ownerCode());
        when(repository.scan(0, 1, 20)).thenReturn(List.of(previous));
        when(repository.manifestPublished(1, MANIFEST)).thenReturn(true);
        service.run(EXECUTION_ID, 8, executionLock, QUEUE_ID);
        verify(storage).cleanupOwnedManifest(oldManifest, source, 1L);
        verify(storage, never()).cleanupOwnedManifest(eq(MANIFEST), any(), any());
    }

    @Test
    void terminalProviderFailureCleansRawOutputButQueryNetworkFailurePreservesIt() {
        when(snapshots.ready("j-new")).thenThrow(new VideoFrameSnapshotTerminalException("cancelled"));
        service.run(EXECUTION_ID, 8, executionLock, QUEUE_ID);
        verify(snapshots).cleanupOutput(anyString(), eq(8));
        reset(snapshots);
        when(snapshots.submit(eq(source), eq(8), anyString(), eq(QUEUE_ID))).thenReturn("j-new");
        when(snapshots.ready("j-new")).thenThrow(new VideoFrameProcessingException("FRAME_QUERY", "timeout"));
        service.run(EXECUTION_ID, 8, executionLock, QUEUE_ID);
        verify(snapshots, never()).cleanupOutput(anyString(), anyInt());
        verify(snapshots, times(1)).submit(eq(source), eq(8), anyString(), eq(QUEUE_ID));
    }

    @Test
    void lostExecutionLockBeforePaidSubmitStopsWholeScanWithoutProcessingNextWork() {
        addSecondWork();
        AtomicInteger checks = new AtomicInteger();
        doAnswer(invocation -> {
            if (checks.incrementAndGet() == 4) { throw new IllegalStateException("lock lost"); }
            return null;
        }).when(executionLock).checkHeld();
        var result = service.run(EXECUTION_ID, 16, executionLock, QUEUE_ID);
        assertEquals(0, result.chargedFrames());
        verify(storage, times(1)).inspectSource(anyString(), any());
        verifyNoInteractions(snapshots);
        assertTrue(logged(Level.ERROR, "历史视频补帧扫描中断", "executionId=execution-1"));
    }

    @Test
    void lostExecutionLockDuringQueryStopsWithoutSubmittingNextWork() {
        addSecondWork();
        AtomicInteger checks = new AtomicInteger();
        doAnswer(invocation -> {
            if (checks.incrementAndGet() == 5) { throw new IllegalStateException("lock lost"); }
            return null;
        }).when(executionLock).checkHeld();
        service.run(EXECUTION_ID, 16, executionLock, QUEUE_ID);
        verify(snapshots, times(1)).submit(eq(source), eq(8), anyString(), eq(QUEUE_ID));
        verify(snapshots, never()).ready(anyString());
        verify(storage, times(1)).inspectSource(anyString(), any());
        verify(snapshots, never()).cleanupOutput(anyString(), anyInt());
    }

    /** 同一固定水位分页内加入第二件，用于验证失败继续与成本边界。 */
    private void addSecondWork() {
        Work second = new Work(2, source.objectKey(), "sha", 23000, null, 0, "OWNER");
        when(repository.highWatermark()).thenReturn(2L);
        when(repository.scan(0, 2, 20)).thenReturn(List.of(work, second));
        when(repository.scan(2, 2, 20)).thenReturn(List.of());
    }

    /** 验证真实日志级别及安全关键字段，避免仅测试格式常量。 */
    private boolean logged(Level level, String... fragments) {
        return events.list.stream().anyMatch(event -> event.getLevel() == level
                && java.util.Arrays.stream(fragments).allMatch(event.getFormattedMessage()::contains));
    }
}
