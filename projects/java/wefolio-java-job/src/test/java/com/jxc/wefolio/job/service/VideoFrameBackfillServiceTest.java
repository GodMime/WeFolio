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
    /** 默认关闭模拟生产灰度开关，转写用例显式改为所需结果。 */
    private VideoAudioTranscriptionService audio;
    /** 与音频无关的原视频摘要入口及本轮预算。 */
    private VideoSummaryService videoSummary;
    /** 可配置单件期限，用于验证清空及付费前的时间边界。 */
    private VideoFrameProperties properties;
    private ListAppender<ILoggingEvent> events;
    private final VideoFrameSource source = new VideoFrameSource("OWNER/work/video/a.mp4", "a".repeat(64), "etag", 1000);
    private final Work work = new Work(1, source.objectKey(), "sha", 23000, null, 0, "OWNER");

    @BeforeEach
    void setup() {
        repository = mock(VideoFrameBackfillRepository.class);
        storage = mock(VideoFrameStorageService.class);
        snapshots = mock(VideoFrameSnapshotClient.class);
        executionLock = mock(VideoFrameBackfillLock.ExecutionLock.class);
        properties = new VideoFrameProperties();
        audio = VideoAudioTestSupport.disabled();
        videoSummary = VideoSummaryTestSupport.disabled();
        service = new VideoFrameBackfillService(repository, storage, snapshots, properties, audio, videoSummary);
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

    /** 合法帧缺文字只请求转写，不产生任何重新截图和删帧费用。 */
    @Test
    void existingFramesWithMissingTextOnlyTranscribe() {
        when(storage.hasValidManifest(any(), eq(source), eq(1L), any())).thenReturn(true);
        when(audio.transcribe(eq(1L), any(), any(), any())).thenReturn(VideoAudioTranscriptionService.Outcome.SAVED);
        var result = service.run(EXECUTION_ID, 8, executionLock, QUEUE_ID);
        assertEquals(1, result.succeeded());
        assertEquals(0, result.chargedFrames());
        verifyNoInteractions(snapshots);
        verify(repository, never()).saveManifest(any(), anyString());
        assertTrue(logged(Level.INFO, "历史视频音频处理汇总", "audioSaved=1", "frameSucceeded=0"));
    }

    /** 同源已有帧的音频步骤继续使用历史锁守卫，失锁后不处理下一件作品。 */
    @Test
    void audioCallbackRetainsHistoricalExecutionLockGuard() {
        addSecondWork();
        when(storage.hasValidManifest(any(), eq(source), eq(1L), any())).thenReturn(true);
        java.util.concurrent.atomic.AtomicBoolean lost = new java.util.concurrent.atomic.AtomicBoolean();
        doAnswer(invocation -> {
            if (lost.get()) { throw new IllegalStateException("模拟历史锁丢失"); }
            return null;
        }).when(executionLock).checkHeld();
        when(audio.transcribe(eq(1L), any(), any(), any())).thenAnswer(invocation -> {
            lost.set(true);
            invocation.<Runnable>getArgument(3).run();
            return VideoAudioTranscriptionService.Outcome.SAVED;
        });
        service.run(EXECUTION_ID, 16, executionLock, QUEUE_ID);
        verify(audio, never()).transcribe(eq(2L), any(), any(), any());
        verifyNoInteractions(snapshots);
        assertTrue(logged(Level.ERROR, "历史视频补帧扫描中断", "cursor=0"));
    }

    /** 模块识别空串为已处理时，保持帧和空结果，不重复识别。 */
    @Test
    void completedEmptyTranscriptWithValidFramesSkipsWork() {
        when(storage.hasValidManifest(any(), eq(source), eq(1L), any())).thenReturn(true);
        when(audio.transcribe(eq(1L), any(), any(), any())).thenReturn(VideoAudioTranscriptionService.Outcome.ALREADY_COMPLETE);
        assertEquals(1, service.run(EXECUTION_ID, 8, executionLock, QUEUE_ID).skipped());
        verifyNoInteractions(snapshots);
    }

    /** 帧已提交之后的音频失败明确暴露为未完成，不能删除刚发布的可用帧。 */
    @Test
    void transcriptionFailureRetainsPublishedFramesAndReportsIncomplete() {
        when(audio.transcribe(eq(1L), any(), any(), any())).thenReturn(VideoAudioTranscriptionService.Outcome.FAILED);
        var result = service.run(EXECUTION_ID, 8, executionLock, QUEUE_ID);
        assertEquals(1, result.failed());
        assertEquals(8, result.chargedFrames());
        var order = inOrder(repository, audio);
        order.verify(repository).saveManifest(work, MANIFEST);
        order.verify(audio).transcribe(eq(1L), any(), any(), any());
        verify(storage, never()).cleanupOwnedManifest(eq(MANIFEST), any(), any());
        assertTrue(logged(Level.WARN, "历史视频有转写未完成", "audioFailed=1"));
        assertTrue(logged(Level.INFO, "历史视频音频处理汇总", "frameSucceeded=1", "audioFailed=1"));
    }

    /** 账户级 ASR 失败由音频模块停止发送，但本轮其余作品的原截图流程仍继续。 */
    @Test
    void stoppedAudioAccountDoesNotStopRemainingFrameBackfill() {
        addSecondWork();
        when(audio.transcribe(anyLong(), any(), any(), any())).thenReturn(VideoAudioTranscriptionService.Outcome.FAILED);
        var result = service.run(EXECUTION_ID, 16, executionLock, QUEUE_ID);
        assertEquals(2, result.failed());
        assertEquals(16, result.chargedFrames());
        assertFalse(result.budgetExhausted());
        verify(repository, times(2)).saveManifest(any(), eq(MANIFEST));
        verify(snapshots, times(2)).submit(eq(source), eq(8), anyString(), eq(QUEUE_ID));
        assertTrue(logged(Level.INFO, "历史视频音频处理汇总", "frameSucceeded=2", "audioFailed=2"));
    }

    /** 音频额度不足与截图额度相同，不让接续游标越过当前尚未处理作品。 */
    @Test
    void audioBudgetExhaustionStopsBeforeAdvancingCursor() {
        when(storage.hasValidManifest(any(), eq(source), eq(1L), any())).thenReturn(true);
        when(audio.transcribe(eq(1L), any(), any(), any())).thenReturn(VideoAudioTranscriptionService.Outcome.BUDGET_EXHAUSTED);
        var result = service.run(EXECUTION_ID, 8, executionLock, QUEUE_ID);
        assertTrue(result.budgetExhausted());
        verify(repository, never()).scan(1, 1, 20);
        assertTrue(logged(Level.INFO, "历史视频补帧执行结束", "cursor=0", "status=BUDGET_EXHAUSTED"));
    }

    /** 强制重建在任何清空之前下载探测并预留音频预算；额度不足保留全部旧内容。 */
    @Test
    void rebuildAudioBudgetRefusalPreservesCurrentWork() {
        Work previous = existingWork();
        var preparation = mock(VideoAudioTranscriptionService.Preparation.class);
        when(preparation.outcome()).thenReturn(VideoAudioTranscriptionService.Outcome.BUDGET_EXHAUSTED);
        when(audio.prepare(eq(1L), eq(true), any(), any(), any())).thenReturn(preparation);
        var result = service.runRebuild(EXECUTION_ID, 8, 0, null, executionLock, QUEUE_ID);
        assertTrue(result.budgetExhausted());
        verify(repository, never()).clearManifestForRebuild(previous);
        verifyNoInteractions(snapshots);
        verify(audio, never()).complete(any(), any());
        verify(preparation).close();
        assertTrue(logged(Level.INFO, "视频帧重建扫描结束", "nextAfterWorkId=0"));
    }

    /** 成功预检资源跨过帧重建复用，新的作品版本由共用转写模块重新读取。 */
    @Test
    void rebuildReusesPreparationAndTranscribesOnlyAfterNewManifest() {
        Work previous = existingWork();
        var preparation = mock(VideoAudioTranscriptionService.Preparation.class);
        when(preparation.ready()).thenReturn(true);
        when(audio.prepare(eq(1L), eq(true), any(), any(), any())).thenReturn(preparation);
        when(audio.complete(eq(preparation), any())).thenReturn(VideoAudioTranscriptionService.Outcome.EMPTY);
        var result = service.runRebuild(EXECUTION_ID, 8, 0, null, executionLock, QUEUE_ID);
        assertEquals(1, result.succeeded());
        var order = inOrder(audio, repository, preparation);
        order.verify(audio).prepare(eq(1L), eq(true), any(), any(), any());
        order.verify(repository).clearManifestForRebuild(previous);
        order.verify(repository).saveManifest(cleared(previous), MANIFEST);
        order.verify(audio).complete(eq(preparation), any());
        order.verify(preparation).close();
        var preflightDeadline = org.mockito.ArgumentCaptor.forClass(java.time.Instant.class);
        var completionDeadline = org.mockito.ArgumentCaptor.forClass(java.time.Instant.class);
        verify(audio).prepare(eq(1L), eq(true), any(), preflightDeadline.capture(), any());
        verify(audio).complete(eq(preparation), completionDeadline.capture());
        assertEquals(preflightDeadline.getValue(), completionDeadline.getValue());
        verify(audio, never()).transcribe(anyLong(), any(), any(), any());
        assertTrue(logged(Level.INFO, "历史视频音频处理汇总", "audioEmpty=1"));
    }

    /** 预检失败或另一个入口持有同作品锁时，不能先清空再等待补偿。 */
    @Test
    void failedOrBusyAudioPreflightDoesNotClearOldFrames() {
        existingWork();
        for (var outcome : List.of(VideoAudioTranscriptionService.Outcome.FAILED,
                VideoAudioTranscriptionService.Outcome.BUSY, VideoAudioTranscriptionService.Outcome.STALE)) {
            var preparation = mock(VideoAudioTranscriptionService.Preparation.class);
            when(preparation.outcome()).thenReturn(outcome);
            when(audio.prepare(eq(1L), eq(true), any(), any(), any())).thenReturn(preparation);
            service.runRebuild(EXECUTION_ID, 8, 0, null, executionLock, QUEUE_ID);
            verify(preparation).close();
        }
        verify(repository, never()).clearManifestForRebuild(any());
        verifyNoInteractions(snapshots);
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

    /** 重建不跳过旧有效帧，先CAS清引用、严格删旧，再通过原共享链路生成完整新清单。 */
    @Test
    void rebuildClearsAndDeletesBeforeSharedFrameGeneration() {
        Work previous = existingWork();
        Work cleared = cleared(previous);
        var result = service.runRebuild(EXECUTION_ID, 8, 0, null, executionLock, QUEUE_ID);
        assertEquals(1, result.succeeded());
        assertEquals(8, result.chargedFrames());
        var order = inOrder(repository, storage, snapshots);
        order.verify(storage).validateOwnedManifestForCleanup(previous.manifest(), source, 1L);
        order.verify(repository).clearManifestForRebuild(previous);
        order.verify(storage).cleanupOwnedManifestStrict(eq(previous.manifest()), eq(source), eq(1L), any());
        order.verify(snapshots).submit(eq(source), eq(8), anyString(), eq(QUEUE_ID));
        order.verify(storage).persist(eq(source), eq(1L), startsWith("history-"), eq(23000), eq(8), anyList(), any());
        order.verify(repository).saveManifest(cleared, MANIFEST);
        verify(storage, never()).hasValidManifest(any(), any(), anyLong(), any());
        verify(storage, never()).cleanupOwnedManifest(anyString(), any(), anyLong());
        assertTrue(logged(Level.INFO, "视频帧重建扫描结束", "nextAfterWorkId=1", "highWatermark=1"));
    }

    /** 不足以支付下一件截图的预算不能先清空该件数据库或COS。 */
    @Test
    void rebuildBudgetExhaustionPreservesTheNextWork() {
        Work first = existingWork();
        Work second = new Work(2, source.objectKey(), "sha", 23000, "{\"attempt\":\"second\"}", 0, "OWNER");
        when(repository.highWatermark()).thenReturn(2L);
        when(repository.scan(0, 2, 20)).thenReturn(List.of(first, second));
        var result = service.runRebuild(EXECUTION_ID, 8, 0, null, executionLock, QUEUE_ID);
        assertTrue(result.budgetExhausted());
        assertEquals(1, result.succeeded());
        verify(repository, never()).clearManifestForRebuild(second);
        verify(storage, never()).cleanupOwnedManifestStrict(eq(second.manifest()), any(), anyLong(), any());
        assertTrue(logged(Level.INFO, "视频帧重建扫描结束", "nextAfterWorkId=1", "highWatermark=2"));
    }

    /** CAS冲突或清空提交结果未知时保留所有旧对象，不假定数据库已安全解绑。 */
    @Test
    void rebuildClearConflictOrUnknownResultDoesNotDeleteOrPay() {
        Work previous = existingWork();
        when(repository.clearManifestForRebuild(previous)).thenReturn(null)
                .thenThrow(new IllegalStateException("response lost"));
        for (int attempt = 0; attempt < 2; attempt++) {
            var result = service.runRebuild(EXECUTION_ID, 8, 0, null, executionLock, QUEUE_ID);
            assertEquals(1, result.failed());
            assertEquals(0, result.chargedFrames());
        }
        verify(storage, never()).cleanupOwnedManifestStrict(anyString(), any(), anyLong(), any());
        verifyNoInteractions(snapshots);
        verify(repository, never()).saveManifest(any(), anyString());
    }

    /** 删除失败不能进入付费截图；清空引用由后续补帧恢复，剩余旧对象交给孤儿扫尾。 */
    @Test
    void rebuildDeleteFailureStopsBeforePaidSubmission() {
        Work previous = existingWork();
        doThrow(new VideoFrameProcessingException("FRAME_CLEANUP", "旧帧删除失败"))
                .when(storage).cleanupOwnedManifestStrict(eq(previous.manifest()), eq(source), eq(1L), any());
        var result = service.runRebuild(EXECUTION_ID, 8, 0, null, executionLock, QUEUE_ID);
        assertEquals(1, result.failed());
        assertEquals(0, result.chargedFrames());
        verify(repository).clearManifestForRebuild(previous);
        verifyNoInteractions(snapshots);
        verify(repository, never()).saveManifest(any(), anyString());
        assertTrue(logged(Level.ERROR, "历史视频补帧失败", "stage=FRAME_CLEANUP"));
    }

    /** 剩余时间不足以完成远端调用时不破坏原清单，也不提交收费截图。 */
    @Test
    void insufficientDeadlineBeforeRebuildPreservesOldManifest() {
        existingWork();
        properties.setBackfillItemSeconds(15);
        properties.setRemoteTimeoutSeconds(15);
        var result = service.runRebuild(EXECUTION_ID, 8, 0, null, executionLock, QUEUE_ID);
        assertEquals(1, result.failed());
        assertEquals(0, result.chargedFrames());
        verify(repository, never()).clearManifestForRebuild(any());
        verify(storage, never()).cleanupOwnedManifestStrict(anyString(), any(), anyLong(), any());
        verifyNoInteractions(snapshots);
        assertTrue(logged(Level.ERROR, "历史视频补帧失败", "stage=PROCESS_INTERRUPTED"));
    }

    /** 清理期间收到关闭中断后，不能继续预留预算或提交新的收费截图。 */
    @Test
    void interruptedAfterOldFrameCleanupDoesNotSubmitPaidJob() {
        Work previous = existingWork();
        doAnswer(invocation -> {
            Thread.currentThread().interrupt();
            return null;
        }).when(storage).cleanupOwnedManifestStrict(eq(previous.manifest()), eq(source), eq(1L), any());
        try {
            var result = service.runRebuild(EXECUTION_ID, 8, 0, null, executionLock, QUEUE_ID);
            assertEquals(0, result.chargedFrames());
            verify(repository).clearManifestForRebuild(previous);
            verifyNoInteractions(snapshots);
            verify(repository, never()).saveManifest(any(), anyString());
            assertTrue(logged(Level.ERROR, "历史视频补帧失败", "stage=PROCESS_INTERRUPTED"));
        } finally {
            Thread.interrupted();
        }
    }

    /** 旧清单无法安全确认所有权时在数据库清空前失败。 */
    @Test
    void unsafeOldManifestCannotBeCleared() {
        Work previous = existingWork();
        doThrow(new VideoFrameProcessingException("FRAME_CLEANUP", "旧清单无法安全识别"))
                .when(storage).validateOwnedManifestForCleanup(previous.manifest(), source, 1L);
        var result = service.runRebuild(EXECUTION_ID, 8, 0, null, executionLock, QUEUE_ID);
        assertEquals(1, result.failed());
        verify(repository, never()).clearManifestForRebuild(any());
        verify(storage, never()).cleanupOwnedManifestStrict(anyString(), any(), anyLong(), any());
        verifyNoInteractions(snapshots);
    }

    /** 清空后被审核或用户修改，新的清单不能覆盖并发版本，只回收本次生成对象。 */
    @Test
    void rebuildPublicationUsesClearedVersionAndCleansOnlyNewUnpublishedFrames() {
        Work previous = existingWork();
        when(repository.saveManifest(cleared(previous), MANIFEST)).thenReturn(false);
        var result = service.runRebuild(EXECUTION_ID, 8, 0, null, executionLock, QUEUE_ID);
        assertEquals(1, result.failed());
        verify(repository).saveManifest(cleared(previous), MANIFEST);
        verify(storage).cleanupOwnedManifest(MANIFEST, source, 1L);
        verify(storage, never()).cleanupOwnedManifest(eq(previous.manifest()), any(), anyLong());
    }

    /** 显式接续范围避免预算有限时反复重建首批作品，扫描上界以本次实际水位为限。 */
    @Test
    void rebuildHonorsExclusiveCursorAndInclusiveUpperBound() {
        when(repository.highWatermark()).thenReturn(100L);
        service.runRebuild(EXECUTION_ID, 8, 20, 30L, executionLock, QUEUE_ID);
        verify(repository).scan(20, 30, 20);
        verify(repository, never()).scan(eq(0L), anyLong(), anyInt());
        verifyNoInteractions(storage, snapshots);
        assertTrue(logged(Level.INFO, "视频帧重建扫描结束", "afterWorkId=20", "highWatermark=30", "nextAfterWorkId=20"));
    }

    /** 帧和音频已齐全时只进入原视频摘要，不重复截图或重新提取音轨。 */
    @Test
    void onlyMissingSummaryUsesOriginalVideoWithExpectedSource() {
        when(storage.hasValidManifest(any(), eq(source), eq(1L), any())).thenReturn(true);
        when(audio.transcribe(eq(1L), any(), any(), any())).thenReturn(VideoAudioTranscriptionService.Outcome.ALREADY_COMPLETE);
        when(videoSummary.summarize(eq(1L), eq(work.objectKey()), eq(work.sha256()), any(), any(), any()))
                .thenReturn(VideoSummaryService.Outcome.SAVED);
        var result = service.run(EXECUTION_ID, 8, executionLock, QUEUE_ID);
        assertEquals(1, result.succeeded());
        assertEquals(0, result.chargedFrames());
        verifyNoInteractions(snapshots);
        verify(videoSummary).summarize(eq(1L), eq(work.objectKey()), eq(work.sha256()), any(), any(), any());
        assertTrue(logged(Level.INFO, "历史视频摘要处理汇总", "summarySaved=1", "summaryFailed=0"));
    }

    /** 帧上传普通失败后原视频摘要仍可独立完成，部分失败保留补偿含义。 */
    @Test
    void ordinaryFrameFailureStillGeneratesOriginalVideoSummary() {
        when(storage.persist(eq(source), eq(1L), anyString(), any(), eq(8), anyList(), any()))
                .thenThrow(new VideoFrameProcessingException("FRAME_UPLOAD", "上传失败"));
        when(videoSummary.summarize(eq(1L), anyString(), any(), any(), any(), any()))
                .thenReturn(VideoSummaryService.Outcome.SAVED);
        var result = service.run(EXECUTION_ID, 8, executionLock, QUEUE_ID);
        assertEquals(1, result.failed());
        assertEquals(0, result.succeeded());
        verify(videoSummary).summarize(eq(1L), eq(work.objectKey()), eq(work.sha256()), any(), any(), any());
        assertTrue(logged(Level.INFO, "历史视频摘要处理汇总", "summarySaved=1"));
    }

    /** 关闭音频或普通转写失败均可生成原视频摘要，音频失败不会被摘要成功掩盖。 */
    @Test
    void disabledAndFailedAudioStillGenerateSummaryWithIndependentStatistics() {
        when(storage.hasValidManifest(any(), eq(source), eq(1L), any())).thenReturn(true);
        when(videoSummary.summarize(eq(1L), anyString(), any(), any(), any(), any()))
                .thenReturn(VideoSummaryService.Outcome.SAVED);
        when(audio.transcribe(eq(1L), any(), any(), any())).thenReturn(VideoAudioTranscriptionService.Outcome.DISABLED);
        assertEquals(1, service.run(EXECUTION_ID, 8, executionLock, QUEUE_ID).succeeded());
        when(audio.transcribe(eq(1L), any(), any(), any())).thenReturn(VideoAudioTranscriptionService.Outcome.FAILED);
        assertEquals(1, service.run(EXECUTION_ID, 8, executionLock, QUEUE_ID).failed());
        verify(videoSummary, times(2)).summarize(eq(1L), anyString(), any(), any(), any(), any());
        assertTrue(logged(Level.INFO, "历史视频音频处理汇总", "audioFailed=1"));
        assertTrue(logged(Level.INFO, "历史视频摘要处理汇总", "summarySaved=1"));
    }

    /** 源过期、另一个入口持锁和额度不足均不能直接进入下一次摘要收费。 */
    @Test
    void staleBusyAndBudgetLimitedAudioDoNotContinueIntoSummary() {
        when(storage.hasValidManifest(any(), eq(source), eq(1L), any())).thenReturn(true);
        for (var outcome : List.of(VideoAudioTranscriptionService.Outcome.STALE,
                VideoAudioTranscriptionService.Outcome.BUSY, VideoAudioTranscriptionService.Outcome.BUDGET_EXHAUSTED)) {
            when(audio.transcribe(eq(1L), any(), any(), any())).thenReturn(outcome);
            service.run(EXECUTION_ID, 8, executionLock, QUEUE_ID);
        }
        verify(videoSummary, never()).summarize(anyLong(), anyString(), any(), any(), any(), any());
    }

    /** 原视频改变或帧发布CAS冲突属于过期结果，不能继续以旧扫描项收费。 */
    @Test
    void staleFrameResultDoesNotContinueIntoSummary() {
        doThrow(new VideoFrameSourceChangedException()).when(storage).verifySource(eq(source), any());
        assertEquals(1, service.run(EXECUTION_ID, 8, executionLock, QUEUE_ID).failed());
        verify(videoSummary, never()).summarize(anyLong(), anyString(), any(), any(), any(), any());
    }

    /** 摘要额度不足在已完成帧、音频后仍停在本件前，接续时复用已有阶段。 */
    @Test
    void summaryBudgetExhaustionKeepsCurrentCursor() {
        when(storage.hasValidManifest(any(), eq(source), eq(1L), any())).thenReturn(true);
        when(videoSummary.summarize(eq(1L), anyString(), any(), any(), any(), any()))
                .thenReturn(VideoSummaryService.Outcome.BUDGET_EXHAUSTED);
        var result = service.run(EXECUTION_ID, 8, executionLock, QUEUE_ID);
        assertTrue(result.budgetExhausted());
        verify(repository, never()).scan(1, 1, 20);
        assertTrue(logged(Level.INFO, "历史视频补帧执行结束", "cursor=0", "status=BUDGET_EXHAUSTED"));
    }

    /** 大小或时长超限及预检异常都保留旧内容并停在当前作品，不把拒绝当执行失败越过游标。 */
    @Test
    void rebuildSummaryPreflightRefusalPreservesDataAndCursor() {
        Work previous = existingWork();
        for (var outcome : List.of(VideoSummaryService.Outcome.NOT_SUPPORTED, VideoSummaryService.Outcome.FAILED,
                VideoSummaryService.Outcome.STALE, VideoSummaryService.Outcome.BUSY)) {
            var preparation = mock(VideoSummaryService.Preparation.class);
            when(preparation.outcome()).thenReturn(outcome);
            when(videoSummary.prepare(eq(1L), eq(true), any(), any(), any())).thenReturn(preparation);
            var result = service.runRebuild(EXECUTION_ID, 8, 0, null, executionLock, QUEUE_ID);
            assertEquals(1, result.failed());
            assertFalse(result.budgetExhausted());
            verify(preparation).close();
        }
        verify(repository, never()).clearManifestForRebuild(previous);
        verify(repository, never()).scan(1, 1, 20);
        verify(videoSummary, never()).complete(any(), any());
        verifyNoInteractions(snapshots);
        assertTrue(logged(Level.INFO, "视频帧重建扫描结束", "status=PREFLIGHT_REFUSED", "nextAfterWorkId=0"));
    }

    /** 原视频摘要费用不足也必须在清空前拒绝，保留原帧、文字和摘要。 */
    @Test
    void rebuildSummaryBudgetRefusalDoesNotClearExistingWork() {
        Work previous = existingWork();
        var preparation = mock(VideoSummaryService.Preparation.class);
        when(preparation.outcome()).thenReturn(VideoSummaryService.Outcome.BUDGET_EXHAUSTED);
        when(videoSummary.prepare(eq(1L), eq(true), any(), any(), any())).thenReturn(preparation);
        var result = service.runRebuild(EXECUTION_ID, 8, 0, null, executionLock, QUEUE_ID);
        assertTrue(result.budgetExhausted());
        verify(repository, never()).clearManifestForRebuild(previous);
        verify(videoSummary, never()).complete(any(), any());
        verify(preparation).close();
        assertTrue(logged(Level.INFO, "视频帧重建扫描结束", "nextAfterWorkId=0"));
    }

    /** 预留凭证只生成一次，且音频持有的作品锁在摘要领取同一锁之前释放。 */
    @Test
    void rebuildReusesSummaryReservationAndClosesAudioBeforeSummary() {
        Work previous = existingWork();
        var audioPrepared = mock(VideoAudioTranscriptionService.Preparation.class);
        when(audioPrepared.ready()).thenReturn(true);
        when(audio.prepare(eq(1L), eq(true), any(), any(), any())).thenReturn(audioPrepared);
        when(audio.complete(eq(audioPrepared), any())).thenReturn(VideoAudioTranscriptionService.Outcome.SAVED);
        var summaryPrepared = mock(VideoSummaryService.Preparation.class);
        when(summaryPrepared.ready()).thenReturn(true);
        when(videoSummary.prepare(eq(1L), eq(true), any(), any(), any())).thenReturn(summaryPrepared);
        when(videoSummary.complete(eq(summaryPrepared), any())).thenAnswer(invocation -> {
            verify(audioPrepared).close();
            return VideoSummaryService.Outcome.SAVED;
        });
        var result = service.runRebuild(EXECUTION_ID, 8, 0, null, executionLock, QUEUE_ID);
        assertEquals(1, result.succeeded());
        var order = inOrder(audio, videoSummary, repository, audioPrepared, summaryPrepared);
        order.verify(audio).prepare(eq(1L), eq(true), any(), any(), any());
        order.verify(videoSummary).prepare(eq(1L), eq(true), any(), any(), any());
        order.verify(repository).clearManifestForRebuild(previous);
        order.verify(repository).saveManifest(cleared(previous), MANIFEST);
        order.verify(audio).complete(eq(audioPrepared), any());
        order.verify(audioPrepared).close();
        order.verify(videoSummary).complete(eq(summaryPrepared), any());
        order.verify(summaryPrepared).close();
        verify(videoSummary, never()).summarize(anyLong(), anyString(), any(), any(), any(), any());
    }

    /** 重建已清空后的普通帧失败仍可保存原视频摘要，音频预检锁同样先释放。 */
    @Test
    void failedRebuiltFramesCanStillSaveReservedSummaryAfterClosingAudio() {
        existingWork();
        var audioPrepared = mock(VideoAudioTranscriptionService.Preparation.class);
        when(audioPrepared.ready()).thenReturn(true);
        when(audio.prepare(eq(1L), eq(true), any(), any(), any())).thenReturn(audioPrepared);
        var summaryPrepared = mock(VideoSummaryService.Preparation.class);
        when(summaryPrepared.ready()).thenReturn(true);
        when(videoSummary.prepare(eq(1L), eq(true), any(), any(), any())).thenReturn(summaryPrepared);
        when(videoSummary.complete(eq(summaryPrepared), any())).thenReturn(VideoSummaryService.Outcome.SAVED);
        when(storage.persist(eq(source), eq(1L), anyString(), any(), eq(8), anyList(), any()))
                .thenThrow(new VideoFrameProcessingException("FRAME_UPLOAD", "上传失败"));
        var result = service.runRebuild(EXECUTION_ID, 8, 0, null, executionLock, QUEUE_ID);
        assertEquals(1, result.failed());
        var order = inOrder(audioPrepared, videoSummary);
        order.verify(audioPrepared).close();
        order.verify(videoSummary).complete(eq(summaryPrepared), any());
        verify(audio, never()).complete(any(), any());
        assertTrue(logged(Level.INFO, "历史视频摘要处理汇总", "summarySaved=1"));
    }

    /** 为重建用例准备一份旧清单及成功CAS返回的已清空快照。 */
    private Work existingWork() {
        Work previous = new Work(work.id(), work.objectKey(), work.sha256(), work.durationMs(),
                "{\"attempt\":\"old\"}", work.version(), work.ownerCode());
        when(repository.scan(0, 1, 20)).thenReturn(List.of(previous));
        when(repository.clearManifestForRebuild(previous)).thenReturn(cleared(previous));
        return previous;
    }

    /** 模拟短事务清空只增加标准版本、清掉帧引用，其它源事实不变。 */
    private Work cleared(Work value) {
        return new Work(value.id(), value.objectKey(), value.sha256(), value.durationMs(), null, value.version() + 1, value.ownerCode());
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
