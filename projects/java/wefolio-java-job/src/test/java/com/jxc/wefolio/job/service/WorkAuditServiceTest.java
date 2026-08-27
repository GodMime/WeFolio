package com.jxc.wefolio.job.service;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.jxc.wefolio.job.config.WorkAuditProperties;
import com.jxc.wefolio.job.dict.AuditResultDict;
import com.jxc.wefolio.job.dict.MediaTypeDict;
import com.jxc.wefolio.job.dict.WorkAuditStatusDict;
import com.jxc.wefolio.job.entity.WorkAuditTaskEntity;
import com.jxc.wefolio.job.entity.WorkAuditWorkEntity;
import com.jxc.wefolio.job.repo.WorkAuditTaskRepository;
import com.jxc.wefolio.job.repo.WorkAuditWorkRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.slf4j.LoggerFactory;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * 作品审核服务编排测试。
 */
class WorkAuditServiceTest {

    /** 一轮任务必须按既定顺序执行各类审核链路。 */
    @Test
    void runOneRoundShouldFollowDesignedSequence() {
        WorkAuditWorkRepository workRepository = mock(WorkAuditWorkRepository.class);
        WorkAuditTaskRepository taskRepository = mock(WorkAuditTaskRepository.class);
        WorkAuditClaimTransactionService claimTransactionService = mock(WorkAuditClaimTransactionService.class);
        TencentCiAuditClient auditClient = mock(TencentCiAuditClient.class);
        WorkAuditService service =
                new WorkAuditService(workRepository, taskRepository, claimTransactionService, auditClient, properties());

        service.runOneRound();

        InOrder inOrder = inOrder(taskRepository, workRepository);
        inOrder.verify(workRepository).countPendingImages();
        inOrder.verify(workRepository).countPendingVideos();
        inOrder.verify(taskRepository).countQueryableVideoTasks(eq(120), any(LocalDateTime.class));
        inOrder.verify(taskRepository).findExhaustedExpiredVideoSubmitTasks(eq(500), eq(3), any());
        inOrder.verify(taskRepository).findRetryableExpiredVideoSubmitTasks(eq(500), eq(3), any());
        inOrder.verify(taskRepository).findExhaustedExpiredVideoQueryTasks(eq(1000), eq(120), any());
        inOrder.verify(taskRepository).findQueryableVideoTasks(eq(1000), eq(120), any());
        inOrder.verify(workRepository).findPendingVideos(500);
        inOrder.verify(workRepository).findPendingImages(500);
        inOrder.verify(taskRepository).findQueryableVideoTasks(eq(1000), eq(120), any());
    }

    /** 视频候选为空时编排层不得调用腾讯云。 */
    @Test
    void emptyVideoCandidatesDoNotReachTencentClient() {
        WorkAuditWorkRepository workRepository = mock(WorkAuditWorkRepository.class);
        WorkAuditTaskRepository taskRepository = mock(WorkAuditTaskRepository.class);
        WorkAuditClaimTransactionService claimTransactionService =
                mock(WorkAuditClaimTransactionService.class);
        TencentCiAuditClient auditClient = mock(TencentCiAuditClient.class);
        when(workRepository.findPendingVideos(20)).thenReturn(List.of());
        WorkAuditService service = new WorkAuditService(
                workRepository, taskRepository, claimTransactionService, auditClient, properties());

        service.submitPendingVideoAudits(20);

        verify(workRepository).findPendingVideos(20);
        verifyNoInteractions(auditClient);
    }

    /** 动图候选和任务均为空时编排层不得调用腾讯云。 */
    @Test
    void emptyAnimationCandidatesDoNotReachTencentClient() {
        WorkAuditWorkRepository workRepository = mock(WorkAuditWorkRepository.class);
        WorkAuditTaskRepository taskRepository = mock(WorkAuditTaskRepository.class);
        WorkAuditClaimTransactionService claimTransactionService =
                mock(WorkAuditClaimTransactionService.class);
        TencentCiAuditClient auditClient = mock(TencentCiAuditClient.class);
        when(taskRepository.findExhaustedExpiredAnimationTasks(eq(20), eq(3), any()))
                .thenReturn(List.of());
        when(taskRepository.findRunnableAnimationTasks(eq(20), eq(3), any()))
                .thenReturn(List.of());
        when(workRepository.findPendingAnimations(20)).thenReturn(List.of());
        WorkAuditService service = new WorkAuditService(
                workRepository, taskRepository, claimTransactionService, auditClient, properties());

        service.auditPendingAnimations(20);

        verify(workRepository).findPendingAnimations(20);
        verifyNoInteractions(auditClient);
    }

    @Test
    void runOneRoundShouldCreateAndExecuteNewAnimationTaskImmediately() {
        WorkAuditWorkRepository workRepository = mock(WorkAuditWorkRepository.class);
        WorkAuditTaskRepository taskRepository = mock(WorkAuditTaskRepository.class);
        WorkAuditClaimTransactionService claimTransactionService = mock(WorkAuditClaimTransactionService.class);
        TencentCiAuditClient auditClient = mock(TencentCiAuditClient.class);
        AnimationFrameSampler sampler = mock(AnimationFrameSampler.class);
        AnimationAuditResultAggregator aggregator = new AnimationAuditResultAggregator();
        AnimationFrameCosService frameCosService = mock(AnimationFrameCosService.class);
        WorkAuditWorkEntity work = work(
                31L,
                MediaTypeDict.ANIMATION,
                "WFA3B1E7A2/work/animation/demo.gif",
                null);
        work.setFrameCount(2);
        when(workRepository.findPendingAnimations(500)).thenReturn(List.of(work));
        when(sampler.sample(2)).thenReturn(List.of(1, 2));
        when(claimTransactionService.claimAndCreatePendingAnimationTask(
                eq(31L), any(WorkAuditTaskEntity.class), eq(List.of(1, 2))))
                .thenAnswer(invocation -> {
                    WorkAuditTaskEntity task = invocation.getArgument(1);
                    task.setId(301L);
                    task.setSampledFrameNumbers("[1,2]");
                    return task;
                });
        when(taskRepository.claimAnimationTask(eq(301L), anyString(), any(), eq(3))).thenReturn(true);
        when(frameCosService.generate(any(WorkAuditTaskEntity.class), eq(1))).thenReturn(
                "WFA3B1E7A2/work/animation/demo-audit-301-1-generated.jpg");
        when(frameCosService.generate(any(WorkAuditTaskEntity.class), eq(2))).thenReturn(
                "WFA3B1E7A2/work/animation/demo-audit-301-2-generated.jpg");
        when(auditClient.auditImage(anyString())).thenReturn(
                imageResult(AuditResultDict.PASS, 0, "Normal", 0));
        WorkAuditService service = new WorkAuditService(
                workRepository, taskRepository, claimTransactionService, auditClient, properties(),
                sampler, aggregator, frameCosService);

        service.runOneRound();

        verify(auditClient).auditImage("WFA3B1E7A2/work/animation/demo-audit-301-1-generated.jpg");
        verify(auditClient).auditImage("WFA3B1E7A2/work/animation/demo-audit-301-2-generated.jpg");
        verify(claimTransactionService).markAnimationTaskSuccessAndUpdateWork(
                eq(301L), eq(31L), eq(1), anyString(),
                eq(AuditResultDict.PASS), eq("Success"), eq(0), eq("Normal"), eq(0),
                eq(List.<TencentCiAuditRisk>of()), anyString(), eq(WorkAuditStatusDict.PASSED), eq(null));
        verify(frameCosService).deleteQuietly(
                "WFA3B1E7A2/work/animation/demo-audit-301-1-generated.jpg", 301L);
        verify(frameCosService).deleteQuietly(
                "WFA3B1E7A2/work/animation/demo-audit-301-2-generated.jpg", 301L);
    }

    @Test
    void animationRetryShouldReusePersistedFramesAndUnknownShouldReturnPending() {
        WorkAuditWorkRepository workRepository = mock(WorkAuditWorkRepository.class);
        WorkAuditTaskRepository taskRepository = mock(WorkAuditTaskRepository.class);
        WorkAuditClaimTransactionService claimTransactionService = mock(WorkAuditClaimTransactionService.class);
        TencentCiAuditClient auditClient = mock(TencentCiAuditClient.class);
        AnimationFrameSampler sampler = mock(AnimationFrameSampler.class);
        AnimationFrameCosService frameCosService = mock(AnimationFrameCosService.class);
        WorkAuditTaskEntity task = new WorkAuditTaskEntity();
        task.setId(302L);
        task.setWorkId(32L);
        task.setMediaType(MediaTypeDict.ANIMATION.getCode());
        task.setMediaObjectKey("WFA3B1E7A2/work/animation/retry.webp");
        task.setSampledFrameNumbers("[5,23]");
        task.setAuditRound(1);
        when(taskRepository.findRunnableAnimationTasks(eq(500), eq(3), any()))
                .thenReturn(List.of(task));
        when(taskRepository.claimAnimationTask(eq(302L), anyString(), any(), eq(3))).thenReturn(true);
        when(frameCosService.generate(task, 5)).thenReturn(
                "WFA3B1E7A2/work/animation/retry-audit-302-5.jpg");
        when(frameCosService.generate(task, 23)).thenReturn(
                "WFA3B1E7A2/work/animation/retry-audit-302-23.jpg");
        when(auditClient.auditImage("WFA3B1E7A2/work/animation/retry-audit-302-5.jpg"))
                .thenReturn(imageResult(AuditResultDict.PASS, 0, "Normal", 0));
        when(auditClient.auditImage("WFA3B1E7A2/work/animation/retry-audit-302-23.jpg"))
                .thenReturn(imageResult(AuditResultDict.UNKNOWN, null, null, null));
        WorkAuditService service = new WorkAuditService(
                workRepository, taskRepository, claimTransactionService, auditClient, properties(),
                sampler, new AnimationAuditResultAggregator(), frameCosService);

        service.runOneRound();

        verify(sampler, never()).sample(any(Integer.class));
        verify(claimTransactionService).retryOrFailAnimationTask(
                eq(302L), eq(32L), eq(1), anyString(),
                eq("动图审核结果未知"), anyString(), eq(3));
        verify(frameCosService).deleteQuietly(
                "WFA3B1E7A2/work/animation/retry-audit-302-5.jpg", 302L);
        verify(frameCosService).deleteQuietly(
                "WFA3B1E7A2/work/animation/retry-audit-302-23.jpg", 302L);
    }

    @Test
    void expiredAnimationAtAttemptLimitShouldBeRecoveredToFailed() {
        WorkAuditWorkRepository workRepository = mock(WorkAuditWorkRepository.class);
        WorkAuditTaskRepository taskRepository = mock(WorkAuditTaskRepository.class);
        WorkAuditClaimTransactionService claimTransactionService = mock(WorkAuditClaimTransactionService.class);
        TencentCiAuditClient auditClient = mock(TencentCiAuditClient.class);
        WorkAuditTaskEntity task = new WorkAuditTaskEntity();
        task.setId(303L);
        task.setWorkId(33L);
        task.setAuditRound(2);
        task.setAttemptCount(3);
        when(taskRepository.findExhaustedExpiredAnimationTasks(eq(10), eq(3), any()))
                .thenReturn(List.of(task));
        when(taskRepository.claimExhaustedAnimationTask(eq(303L), anyString(), any(), eq(3)))
                .thenReturn(true);
        when(claimTransactionService.retryOrFailAnimationTask(
                eq(303L), eq(33L), eq(2), anyString(), anyString(), eq(null), eq(3)))
                .thenReturn(true);
        WorkAuditService service =
                new WorkAuditService(workRepository, taskRepository, claimTransactionService, auditClient, properties());

        service.auditPendingAnimations(10);

        verify(claimTransactionService).retryOrFailAnimationTask(
                eq(303L), eq(33L), eq(2), anyString(),
                eq("动图审核任务在最后一次尝试中断"), eq(null), eq(3));
        verifyNoInteractions(auditClient);
    }

    @Test
    void runOneRoundShouldLogStartConfigWithChineseLabels() {
        WorkAuditWorkRepository workRepository = mock(WorkAuditWorkRepository.class);
        WorkAuditTaskRepository taskRepository = mock(WorkAuditTaskRepository.class);
        WorkAuditClaimTransactionService claimTransactionService = mock(WorkAuditClaimTransactionService.class);
        TencentCiAuditClient auditClient = mock(TencentCiAuditClient.class);
        WorkAuditService service =
                new WorkAuditService(workRepository, taskRepository, claimTransactionService, auditClient, properties());
        Logger logger = (Logger) LoggerFactory.getLogger(WorkAuditService.class);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);

        try {
            service.runOneRound();
        } finally {
            logger.detachAppender(appender);
            appender.stop();
        }

        assertThat(appender.list)
                .extracting(ILoggingEvent::getFormattedMessage)
                .contains("作品审核任务开始: 单轮视频查询任务上限=1000, 单轮视频提交作品上限=500, "
                        + "视频提交最大尝试次数=3, 单轮图片审核作品上限=500, 单轮动图审核任务上限=500, "
                        + "动图最大尝试次数=3, 视频主动查询最大次数=120");
    }

    @Test
    void runOneRoundShouldLogTotalDurationWhenFinished() {
        WorkAuditWorkRepository workRepository = mock(WorkAuditWorkRepository.class);
        WorkAuditTaskRepository taskRepository = mock(WorkAuditTaskRepository.class);
        WorkAuditClaimTransactionService claimTransactionService = mock(WorkAuditClaimTransactionService.class);
        TencentCiAuditClient auditClient = mock(TencentCiAuditClient.class);
        WorkAuditService service =
                new WorkAuditService(workRepository, taskRepository, claimTransactionService, auditClient, properties());
        Logger logger = (Logger) LoggerFactory.getLogger(WorkAuditService.class);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);

        try {
            service.runOneRound();
        } finally {
            logger.detachAppender(appender);
            appender.stop();
        }

        assertThat(appender.list)
                .extracting(ILoggingEvent::getFormattedMessage)
                .anyMatch(message -> message.matches("作品审核任务结束: 任务总耗时毫秒=\\d+"));
    }

    @Test
    void runOneRoundShouldLogBacklogSummaryWithChineseLabels() {
        WorkAuditWorkRepository workRepository = mock(WorkAuditWorkRepository.class);
        WorkAuditTaskRepository taskRepository = mock(WorkAuditTaskRepository.class);
        WorkAuditClaimTransactionService claimTransactionService = mock(WorkAuditClaimTransactionService.class);
        TencentCiAuditClient auditClient = mock(TencentCiAuditClient.class);
        when(workRepository.countPendingImages()).thenReturn(1L);
        when(workRepository.countPendingVideos()).thenReturn(2L);
        when(workRepository.countPendingAnimations()).thenReturn(4L);
        when(taskRepository.countQueryableVideoTasks(eq(120), any())).thenReturn(3L);
        WorkAuditService service =
                new WorkAuditService(workRepository, taskRepository, claimTransactionService, auditClient, properties());
        Logger logger = (Logger) LoggerFactory.getLogger(WorkAuditService.class);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);

        try {
            service.runOneRound();
        } finally {
            logger.detachAppender(appender);
            appender.stop();
        }

        assertThat(appender.list)
                .extracting(ILoggingEvent::getFormattedMessage)
                .contains("作品审核待处理统计: 待审核图片作品数=1, 待审核视频作品数=2, "
                        + "待审核动图作品数=4, 待审核作品总数=7, 待查询视频任务数=3, "
                        + "视频主动查询最大次数=120");
    }

    @Test
    void submitVideoFailureShouldMarkTaskAndWorkFailedWithoutRetry() {
        WorkAuditWorkEntity work = work(11L, MediaTypeDict.VIDEO, "video.mp4", 270000);
        WorkAuditWorkRepository workRepository = mock(WorkAuditWorkRepository.class);
        WorkAuditTaskRepository taskRepository = mock(WorkAuditTaskRepository.class);
        WorkAuditClaimTransactionService claimTransactionService = mock(WorkAuditClaimTransactionService.class);
        TencentCiAuditClient auditClient = mock(TencentCiAuditClient.class);
        when(workRepository.findPendingVideos(500)).thenReturn(List.of(work));
        when(claimTransactionService.claimAndCreateSubmittingTask(eq(11L), any(WorkAuditTaskEntity.class))).thenAnswer(invocation -> {
            WorkAuditTaskEntity task = invocation.getArgument(1);
            task.setId(101L);
            return task;
        });
        when(auditClient.submitVideo(eq("video.mp4"), eq(60), eq(5)))
                .thenThrow(new IllegalStateException("submit failed"));
        WorkAuditService service =
                new WorkAuditService(workRepository, taskRepository, claimTransactionService, auditClient, properties());

        service.submitPendingVideoAudits(500);

        ArgumentCaptor<String> reasonCaptor = ArgumentCaptor.forClass(String.class);
        verify(claimTransactionService).markVideoTaskFailedAndUpdateWorkFailed(
                eq(101L), eq(11L), eq(1), anyString(), anyString(), anyString(), reasonCaptor.capture());
        assertThat(reasonCaptor.getValue()).contains("提交腾讯云视频审核失败", "submit failed");
    }

    @Test
    void submitVideoSuccessShouldMarkVideoSubmittedAndKeepWorkAuditing() {
        WorkAuditWorkEntity work = work(17L, MediaTypeDict.VIDEO, "clean-video.mp4", 23000);
        WorkAuditWorkRepository workRepository = mock(WorkAuditWorkRepository.class);
        WorkAuditTaskRepository taskRepository = mock(WorkAuditTaskRepository.class);
        WorkAuditClaimTransactionService claimTransactionService = mock(WorkAuditClaimTransactionService.class);
        TencentCiAuditClient auditClient = mock(TencentCiAuditClient.class);
        when(workRepository.findPendingVideos(500)).thenReturn(List.of(work));
        when(claimTransactionService.claimAndCreateSubmittingTask(eq(17L), any(WorkAuditTaskEntity.class))).thenAnswer(invocation -> {
            WorkAuditTaskEntity task = invocation.getArgument(1);
            task.setId(107L);
            return task;
        });
        when(auditClient.submitVideo(eq("clean-video.mp4"), eq(60), eq(1))).thenReturn(new TencentCiAuditResult(
                "video-job-id", "Submitted", AuditResultDict.UNKNOWN, null, null, null, false, false, "{}"));
        WorkAuditService service =
                new WorkAuditService(workRepository, taskRepository, claimTransactionService, auditClient, properties());

        service.submitPendingVideoAudits(500);

        verify(claimTransactionService).markVideoSubmittedAndKeepWorkAuditing(
                eq(107L), eq(17L), eq(1), anyString(), eq("video-job-id"), eq("{}"));
    }

    @Test
    void newVideoSubmissionShouldUseUniqueTokenWhileImageKeepsInstancePrefix() {
        WorkAuditWorkEntity video = work(41L, MediaTypeDict.VIDEO, "unique-video.mp4", 1000);
        WorkAuditWorkEntity image = work(42L, MediaTypeDict.IMAGE, "plain-image.jpg", null);
        WorkAuditWorkRepository workRepository = mock(WorkAuditWorkRepository.class);
        WorkAuditTaskRepository taskRepository = mock(WorkAuditTaskRepository.class);
        WorkAuditClaimTransactionService claimTransactionService = mock(WorkAuditClaimTransactionService.class);
        TencentCiAuditClient auditClient = mock(TencentCiAuditClient.class);
        when(workRepository.findPendingVideos(1)).thenReturn(List.of(video));
        when(workRepository.findPendingImages(1)).thenReturn(List.of(image));
        when(claimTransactionService.claimAndCreateSubmittingTask(any(), any()))
                .thenAnswer(invocation -> {
                    WorkAuditTaskEntity task = invocation.getArgument(1);
                    task.setId(task.getWorkId() + 400L);
                    return task;
                });
        when(auditClient.submitVideo("unique-video.mp4", 60, 1)).thenReturn(new TencentCiAuditResult(
                "job-unique", "Submitted", AuditResultDict.UNKNOWN,
                null, null, null, false, false, "{}"));
        WorkAuditService service =
                new WorkAuditService(workRepository, taskRepository, claimTransactionService, auditClient, properties());

        service.submitPendingVideoAudits(1);
        service.auditPendingImages(1);

        ArgumentCaptor<WorkAuditTaskEntity> taskCaptor = ArgumentCaptor.forClass(WorkAuditTaskEntity.class);
        verify(claimTransactionService, times(2))
                .claimAndCreateSubmittingTask(any(), taskCaptor.capture());
        assertThat(taskCaptor.getAllValues().get(0).getLockedBy()).matches("test-[0-9a-f]{32}");
        assertThat(taskCaptor.getAllValues().get(1).getLockedBy()).isEqualTo("test");
    }

    @Test
    void expiredVideoSubmitShouldReusePersistedTaskAndSnapshotSettings() {
        WorkAuditTaskEntity task = videoTask(501L, 51L, 0);
        task.setMediaObjectKey("persisted-old.mp4");
        task.setSnapshotIntervalSeconds(30);
        task.setSnapshotCount(7);
        task.setAttemptCount(1);
        task.setLockedUntil(LocalDateTime.of(2026, 8, 13, 11, 55));
        LocalDateTime now = LocalDateTime.of(2026, 8, 13, 12, 0);
        WorkAuditWorkRepository workRepository = mock(WorkAuditWorkRepository.class);
        WorkAuditTaskRepository taskRepository = mock(WorkAuditTaskRepository.class);
        WorkAuditClaimTransactionService claimTransactionService = mock(WorkAuditClaimTransactionService.class);
        TencentCiAuditClient auditClient = mock(TencentCiAuditClient.class);
        when(taskRepository.findRetryableExpiredVideoSubmitTasks(2, 3, now)).thenReturn(List.of(task));
        when(taskRepository.claimExpiredVideoSubmit(
                eq(501L), anyString(), eq(now), eq(now.plusMinutes(5)), eq(3))).thenReturn(true);
        when(auditClient.submitVideo("persisted-old.mp4", 30, 7)).thenReturn(new TencentCiAuditResult(
                "recovered-job", "Submitted", AuditResultDict.UNKNOWN,
                null, null, null, false, false, "{}"));
        when(claimTransactionService.markVideoSubmittedAndKeepWorkAuditing(
                eq(501L), eq(51L), eq(1), anyString(), eq("recovered-job"), eq("{}"))).thenReturn(true);
        WorkAuditService service =
                new WorkAuditService(workRepository, taskRepository, claimTransactionService, auditClient, properties());

        int remaining = service.recoverExpiredVideoSubmissions(2, now);

        assertThat(remaining).isEqualTo(1);
        verify(auditClient).submitVideo("persisted-old.mp4", 30, 7);
        verify(workRepository, never()).findPendingVideos(any(Integer.class));
        verify(claimTransactionService, never()).claimAndCreateSubmittingTask(any(), any());
    }

    @Test
    void expiredVideoSubmitClaimFailureShouldNotCallTencentOrConsumeCapacity() {
        WorkAuditTaskEntity contested = videoTask(511L, 61L, 0);
        contested.setAttemptCount(1);
        WorkAuditTaskEntity claimed = videoTask(512L, 62L, 0);
        claimed.setAttemptCount(1);
        claimed.setSnapshotIntervalSeconds(60);
        claimed.setSnapshotCount(2);
        LocalDateTime now = LocalDateTime.of(2026, 8, 13, 12, 0);
        WorkAuditWorkRepository workRepository = mock(WorkAuditWorkRepository.class);
        WorkAuditTaskRepository taskRepository = mock(WorkAuditTaskRepository.class);
        WorkAuditClaimTransactionService claimTransactionService = mock(WorkAuditClaimTransactionService.class);
        TencentCiAuditClient auditClient = mock(TencentCiAuditClient.class);
        when(taskRepository.findRetryableExpiredVideoSubmitTasks(2, 3, now))
                .thenReturn(List.of(contested, claimed));
        when(taskRepository.claimExpiredVideoSubmit(
                eq(511L), anyString(), eq(now), eq(now.plusMinutes(5)), eq(3))).thenReturn(false);
        when(taskRepository.claimExpiredVideoSubmit(
                eq(512L), anyString(), eq(now), eq(now.plusMinutes(5)), eq(3))).thenReturn(true);
        when(auditClient.submitVideo("video.mp4", 60, 2)).thenReturn(new TencentCiAuditResult(
                "claimed-job", "Submitted", AuditResultDict.UNKNOWN,
                null, null, null, false, false, "{}"));
        WorkAuditService service =
                new WorkAuditService(workRepository, taskRepository, claimTransactionService, auditClient, properties());

        int remaining = service.recoverExpiredVideoSubmissions(2, now);

        assertThat(remaining).isEqualTo(1);
        verify(auditClient).submitVideo("video.mp4", 60, 2);
    }

    @Test
    void exhaustedExpiredVideoSubmitShouldFailWithoutRemoteCall() {
        WorkAuditTaskEntity task = videoTask(521L, 71L, 0);
        task.setAttemptCount(3);
        LocalDateTime now = LocalDateTime.of(2026, 8, 13, 12, 0);
        WorkAuditWorkRepository workRepository = mock(WorkAuditWorkRepository.class);
        WorkAuditTaskRepository taskRepository = mock(WorkAuditTaskRepository.class);
        WorkAuditClaimTransactionService claimTransactionService = mock(WorkAuditClaimTransactionService.class);
        TencentCiAuditClient auditClient = mock(TencentCiAuditClient.class);
        when(taskRepository.findExhaustedExpiredVideoSubmitTasks(2, 3, now)).thenReturn(List.of(task));
        when(taskRepository.claimExhaustedExpiredVideoSubmit(
                eq(521L), anyString(), eq(now), eq(now.plusMinutes(5)), eq(3))).thenReturn(true);
        WorkAuditService service =
                new WorkAuditService(workRepository, taskRepository, claimTransactionService, auditClient, properties());

        int remaining = service.recoverExpiredVideoSubmissions(2, now);

        assertThat(remaining).isEqualTo(2);
        verify(claimTransactionService).markVideoTaskFailedAndUpdateWorkFailed(
                eq(521L), eq(71L), eq(1), anyString(),
                eq("视频审核提交任务在最后一次尝试中断"), eq(null),
                eq("视频审核提交任务连续中断并达到最大恢复次数"));
        verifyNoInteractions(auditClient);
    }

    @Test
    void exhaustedVideoSubmitRecoveryShouldLogClaimAndWritebackSkips() {
        LocalDateTime now = LocalDateTime.of(2026, 8, 13, 12, 0);
        WorkAuditTaskEntity contested = videoTask(541L, 83L, 0);
        contested.setAttemptCount(3);
        contested.setLockedUntil(now.minusMinutes(5));
        WorkAuditTaskEntity stale = videoTask(542L, 84L, 0);
        stale.setAttemptCount(3);
        stale.setLockedUntil(now.minusMinutes(4));
        WorkAuditWorkRepository workRepository = mock(WorkAuditWorkRepository.class);
        WorkAuditTaskRepository taskRepository = mock(WorkAuditTaskRepository.class);
        WorkAuditClaimTransactionService claimTransactionService = mock(WorkAuditClaimTransactionService.class);
        TencentCiAuditClient auditClient = mock(TencentCiAuditClient.class);
        when(taskRepository.findExhaustedExpiredVideoSubmitTasks(2, 3, now))
                .thenReturn(List.of(contested, stale));
        when(taskRepository.claimExhaustedExpiredVideoSubmit(
                eq(542L), anyString(), eq(now), eq(now.plusMinutes(5)), eq(3))).thenReturn(true);
        WorkAuditService service =
                new WorkAuditService(workRepository, taskRepository, claimTransactionService, auditClient, properties());

        List<String> messages = captureLogMessages(() -> service.recoverExpiredVideoSubmissions(2, now));

        assertThat(messages).contains(
                "视频提交终态回收跳过: workId=83, taskId=541, auditRound=1, attemptCount=3, "
                        + "maxAttempts=3, 原锁过期时间=2026-08-13T11:55, reason=未抢占到任务",
                "视频提交终态回收跳过: workId=84, taskId=542, auditRound=1, attemptCount=3, "
                        + "maxAttempts=3, 原锁过期时间=2026-08-13T11:56, "
                        + "reason=任务租约已失效或作品审核轮次已变化");
    }

    @Test
    void recoveredAndNewVideoSubmissionsShouldShareCapacity() {
        WorkAuditTaskEntity recovered = videoTask(531L, 81L, 0);
        recovered.setAttemptCount(1);
        recovered.setSnapshotIntervalSeconds(60);
        recovered.setSnapshotCount(1);
        WorkAuditWorkEntity newWork = work(82L, MediaTypeDict.VIDEO, "new-video.mp4", 1000);
        LocalDateTime now = LocalDateTime.of(2026, 8, 13, 12, 0);
        WorkAuditWorkRepository workRepository = mock(WorkAuditWorkRepository.class);
        WorkAuditTaskRepository taskRepository = mock(WorkAuditTaskRepository.class);
        WorkAuditClaimTransactionService claimTransactionService = mock(WorkAuditClaimTransactionService.class);
        TencentCiAuditClient auditClient = mock(TencentCiAuditClient.class);
        when(taskRepository.findRetryableExpiredVideoSubmitTasks(2, 3, now)).thenReturn(List.of(recovered));
        when(taskRepository.claimExpiredVideoSubmit(
                eq(531L), anyString(), eq(now), eq(now.plusMinutes(5)), eq(3))).thenReturn(true);
        when(workRepository.findPendingVideos(1)).thenReturn(List.of(newWork));
        when(claimTransactionService.claimAndCreateSubmittingTask(eq(82L), any()))
                .thenAnswer(invocation -> {
                    WorkAuditTaskEntity task = invocation.getArgument(1);
                    task.setId(532L);
                    return task;
                });
        when(auditClient.submitVideo(anyString(), anyInt(), anyInt()))
                .thenReturn(new TencentCiAuditResult(
                        "job", "Submitted", AuditResultDict.UNKNOWN,
                        null, null, null, false, false, "{}"));
        WorkAuditService service =
                new WorkAuditService(workRepository, taskRepository, claimTransactionService, auditClient, properties());

        int remaining = service.recoverExpiredVideoSubmissions(2, now);
        service.submitPendingVideoAudits(remaining);

        assertThat(remaining).isEqualTo(1);
        verify(workRepository).findPendingVideos(1);
        verify(auditClient, times(2)).submitVideo(anyString(), anyInt(), anyInt());
    }

    @Test
    void newAuditTaskShouldInheritCurrentWorkAuditRound() {
        WorkAuditWorkEntity work = work(19L, MediaTypeDict.IMAGE, "round-two.jpg", null);
        work.setAuditRound(2);
        WorkAuditWorkRepository workRepository = mock(WorkAuditWorkRepository.class);
        WorkAuditTaskRepository taskRepository = mock(WorkAuditTaskRepository.class);
        WorkAuditClaimTransactionService claimTransactionService = mock(WorkAuditClaimTransactionService.class);
        TencentCiAuditClient auditClient = mock(TencentCiAuditClient.class);
        when(workRepository.findPendingImages(1)).thenReturn(List.of(work));
        when(claimTransactionService.claimAndCreateSubmittingTask(eq(19L), any(WorkAuditTaskEntity.class)))
                .thenReturn(null);
        WorkAuditService service =
                new WorkAuditService(workRepository, taskRepository, claimTransactionService, auditClient, properties());

        service.auditPendingImages(1);

        ArgumentCaptor<WorkAuditTaskEntity> taskCaptor = ArgumentCaptor.forClass(WorkAuditTaskEntity.class);
        verify(claimTransactionService).claimAndCreateSubmittingTask(eq(19L), taskCaptor.capture());
        assertThat(taskCaptor.getValue().getAuditRound()).isEqualTo(2);
    }

    @Test
    void imageReviewResultShouldSetWorkReviewRequired() {
        WorkAuditWorkEntity work = work(12L, MediaTypeDict.IMAGE, "image.jpg", null);
        WorkAuditWorkRepository workRepository = mock(WorkAuditWorkRepository.class);
        WorkAuditTaskRepository taskRepository = mock(WorkAuditTaskRepository.class);
        WorkAuditClaimTransactionService claimTransactionService = mock(WorkAuditClaimTransactionService.class);
        TencentCiAuditClient auditClient = mock(TencentCiAuditClient.class);
        when(workRepository.findPendingImages(500)).thenReturn(List.of(work));
        when(claimTransactionService.claimAndCreateSubmittingTask(eq(12L), any(WorkAuditTaskEntity.class))).thenAnswer(invocation -> {
            WorkAuditTaskEntity task = invocation.getArgument(1);
            task.setId(102L);
            return task;
        });
        when(auditClient.auditImage("image.jpg")).thenReturn(new TencentCiAuditResult(
                "image-job-id", null, AuditResultDict.REVIEW, 1, "Porn", 90, true, false, "{}"));
        WorkAuditService service =
                new WorkAuditService(workRepository, taskRepository, claimTransactionService, auditClient, properties());

        service.auditPendingImages(500);

        ArgumentCaptor<String> reasonCaptor = ArgumentCaptor.forClass(String.class);
        verify(claimTransactionService).markTaskSuccessAndUpdateWork(
                eq(102L), eq(12L), eq(1), eq(AuditResultDict.REVIEW), any(), eq(1), eq("Porn"), eq(90),
                eq(List.<TencentCiAuditRisk>of()), eq("{}"),
                eq(WorkAuditStatusDict.REVIEW_REQUIRED), reasonCaptor.capture());
        assertThat(reasonCaptor.getValue()).contains("疑似违规", "需人工复核", "Porn", "90");
    }

    @Test
    void imagePassResultShouldClearAuditRejectReason() {
        WorkAuditWorkEntity work = work(16L, MediaTypeDict.IMAGE, "clean-image.jpg", null);
        WorkAuditWorkRepository workRepository = mock(WorkAuditWorkRepository.class);
        WorkAuditTaskRepository taskRepository = mock(WorkAuditTaskRepository.class);
        WorkAuditClaimTransactionService claimTransactionService = mock(WorkAuditClaimTransactionService.class);
        TencentCiAuditClient auditClient = mock(TencentCiAuditClient.class);
        when(workRepository.findPendingImages(500)).thenReturn(List.of(work));
        when(claimTransactionService.claimAndCreateSubmittingTask(eq(16L), any(WorkAuditTaskEntity.class))).thenAnswer(invocation -> {
            WorkAuditTaskEntity task = invocation.getArgument(1);
            task.setId(106L);
            return task;
        });
        when(auditClient.auditImage("clean-image.jpg")).thenReturn(new TencentCiAuditResult(
                "image-job-id", null, AuditResultDict.PASS, 0, "Normal", 0, true, false, "{}"));
        WorkAuditService service =
                new WorkAuditService(workRepository, taskRepository, claimTransactionService, auditClient, properties());

        service.auditPendingImages(500);

        verify(claimTransactionService).markTaskSuccessAndUpdateWork(
                eq(106L), eq(16L), eq(1), eq(AuditResultDict.PASS), any(), eq(0), eq("Normal"), eq(0),
                eq(List.<TencentCiAuditRisk>of()), eq("{}"),
                eq(WorkAuditStatusDict.PASSED), eq(null));
    }

    /** 图片结果被隔离条件拒绝时只记录脱敏告警且不记录成功日志。 */
    @Test
    void discardedImageResultShouldWriteSanitizedWarningAndSkipSuccessLog() {
        WorkAuditWorkEntity work = work(17L, MediaTypeDict.IMAGE, "sensitive-image.jpg", null);
        work.setAuditRound(2);
        WorkAuditWorkRepository workRepository = mock(WorkAuditWorkRepository.class);
        WorkAuditTaskRepository taskRepository = mock(WorkAuditTaskRepository.class);
        WorkAuditClaimTransactionService claimTransactionService = mock(WorkAuditClaimTransactionService.class);
        TencentCiAuditClient auditClient = mock(TencentCiAuditClient.class);
        when(workRepository.findPendingImages(1)).thenReturn(List.of(work));
        when(claimTransactionService.claimAndCreateSubmittingTask(eq(17L), any(WorkAuditTaskEntity.class)))
                .thenAnswer(invocation -> {
                    WorkAuditTaskEntity task = invocation.getArgument(1);
                    task.setId(107L);
                    return task;
                });
        when(auditClient.auditImage("sensitive-image.jpg")).thenReturn(new TencentCiAuditResult(
                "image-job-id", null, AuditResultDict.PASS, 0, "Normal", 0, true, false,
                "sensitive-payload"));
        when(claimTransactionService.markTaskSuccessAndUpdateWork(
                eq(107L), eq(17L), eq(2), eq(AuditResultDict.PASS), any(), eq(0), eq("Normal"), eq(0),
                eq(List.<TencentCiAuditRisk>of()), eq("sensitive-payload"),
                eq(WorkAuditStatusDict.PASSED), eq(null))).thenReturn(false);
        WorkAuditService service = new WorkAuditService(
                workRepository, taskRepository, claimTransactionService, auditClient, properties());

        List<String> messages = captureLogMessages(() -> service.auditPendingImages(1));

        assertThat(messages).anyMatch(message -> message.equals(
                "图片自动审核结果被隔离条件拒绝: workId=17, taskId=107, auditRound=2"));
        assertThat(messages.stream()
                .filter(message -> message.contains("图片自动审核结果被隔离条件拒绝")))
                .allMatch(message -> !message.contains("sensitive-image.jpg")
                        && !message.contains("sensitive-payload"));
        assertThat(messages).noneMatch(message -> message.contains("图片作品审核简洁结果"));
    }

    @Test
    void queryFailureShouldReturnRunningWhenUnderLimit() {
        WorkAuditTaskEntity task = videoTask(103L, 13L, 0);
        WorkAuditWorkRepository workRepository = mock(WorkAuditWorkRepository.class);
        WorkAuditTaskRepository taskRepository = mock(WorkAuditTaskRepository.class);
        WorkAuditClaimTransactionService claimTransactionService = mock(WorkAuditClaimTransactionService.class);
        TencentCiAuditClient auditClient = mock(TencentCiAuditClient.class);
        when(taskRepository.findQueryableVideoTasks(eq(1000), eq(120), any())).thenReturn(List.of(task));
        when(taskRepository.claimVideoQuery(eq(103L), anyString(), any(), any(), eq(120))).thenReturn(true);
        when(auditClient.queryVideo("video-job-id")).thenThrow(new IllegalStateException("query failed"));
        WorkAuditService service =
                new WorkAuditService(workRepository, taskRepository, claimTransactionService, auditClient, properties());

        service.queryPendingVideoResults(1000);

        verify(claimTransactionService).markVideoQueryFailureForNextRunAndKeepWorkAuditing(
                eq(103L), eq(13L), eq(1), anyString(), anyString(), anyString());
        verify(claimTransactionService, never()).markVideoTaskFailedAndUpdateWorkFailed(
                eq(103L), eq(13L), eq(1), anyString(), anyString(), anyString(), any());
    }

    @Test
    void videoPassResultShouldSetWorkPassedAndClearAuditRejectReason() {
        WorkAuditTaskEntity task = videoTask(108L, 18L, 3);
        WorkAuditWorkRepository workRepository = mock(WorkAuditWorkRepository.class);
        WorkAuditTaskRepository taskRepository = mock(WorkAuditTaskRepository.class);
        WorkAuditClaimTransactionService claimTransactionService = mock(WorkAuditClaimTransactionService.class);
        TencentCiAuditClient auditClient = mock(TencentCiAuditClient.class);
        when(taskRepository.findQueryableVideoTasks(eq(1000), eq(120), any())).thenReturn(List.of(task));
        when(taskRepository.claimVideoQuery(eq(108L), anyString(), any(), any(), eq(120))).thenReturn(true);
        when(auditClient.queryVideo("video-job-id")).thenReturn(new TencentCiAuditResult(
                "video-job-id", "Success", AuditResultDict.PASS, 0, "Normal", 0, true, false, "{}"));
        WorkAuditService service =
                new WorkAuditService(workRepository, taskRepository, claimTransactionService, auditClient, properties());

        service.queryPendingVideoResults(1000);

        verify(claimTransactionService).markVideoTaskSuccessAndUpdateWork(
                eq(108L), eq(18L), eq(1), anyString(),
                eq(AuditResultDict.PASS), eq("Success"), eq(0), eq("Normal"), eq(0),
                eq(List.<TencentCiAuditRisk>of()), eq("{}"),
                eq(WorkAuditStatusDict.PASSED), eq(null));
    }

    @Test
    void runningVideoAtQueryLimitShouldFail() {
        WorkAuditTaskEntity task = videoTask(104L, 14L, 119);
        WorkAuditWorkRepository workRepository = mock(WorkAuditWorkRepository.class);
        WorkAuditTaskRepository taskRepository = mock(WorkAuditTaskRepository.class);
        WorkAuditClaimTransactionService claimTransactionService = mock(WorkAuditClaimTransactionService.class);
        TencentCiAuditClient auditClient = mock(TencentCiAuditClient.class);
        when(taskRepository.findQueryableVideoTasks(eq(1000), eq(120), any())).thenReturn(List.of(task));
        when(taskRepository.claimVideoQuery(eq(104L), anyString(), any(), any(), eq(120))).thenReturn(true);
        when(auditClient.queryVideo("video-job-id")).thenReturn(new TencentCiAuditResult(
                "video-job-id", "Running", AuditResultDict.UNKNOWN, null, null, null, false, false, "{}"));
        WorkAuditService service =
                new WorkAuditService(workRepository, taskRepository, claimTransactionService, auditClient, properties());

        service.queryPendingVideoResults(1000);

        ArgumentCaptor<String> reasonCaptor = ArgumentCaptor.forClass(String.class);
        verify(claimTransactionService).markVideoTaskFailedAndUpdateWorkFailed(
                eq(104L), eq(14L), eq(1), anyString(), anyString(), eq("{}"), reasonCaptor.capture());
        assertThat(reasonCaptor.getValue()).contains("视频审核查询次数超过上限", "queryCount=120", "maxQueryCount=120");
    }

    @Test
    void staleVideoQuerySnapshotShouldUsePostClaimQueryCount() {
        WorkAuditTaskEntity task = videoTask(105L, 15L, 118);
        WorkAuditWorkRepository workRepository = mock(WorkAuditWorkRepository.class);
        WorkAuditTaskRepository taskRepository = mock(WorkAuditTaskRepository.class);
        WorkAuditClaimTransactionService claimTransactionService = mock(WorkAuditClaimTransactionService.class);
        TencentCiAuditClient auditClient = mock(TencentCiAuditClient.class);
        when(taskRepository.findQueryableVideoTasks(eq(1000), eq(120), any())).thenReturn(List.of(task));
        when(taskRepository.claimVideoQuery(eq(105L), anyString(), any(), any(), eq(120))).thenReturn(true);
        when(taskRepository.findQueryCountById(105L)).thenReturn(120);
        when(auditClient.queryVideo("video-job-id")).thenReturn(new TencentCiAuditResult(
                "video-job-id", "Running", AuditResultDict.UNKNOWN, null, null, null, false, false, "{}"));
        WorkAuditService service =
                new WorkAuditService(workRepository, taskRepository, claimTransactionService, auditClient, properties());

        service.queryPendingVideoResults(1000);

        ArgumentCaptor<String> reasonCaptor = ArgumentCaptor.forClass(String.class);
        verify(claimTransactionService).markVideoTaskFailedAndUpdateWorkFailed(
                eq(105L), eq(15L), eq(1), anyString(), anyString(), eq("{}"), reasonCaptor.capture());
        verify(claimTransactionService, never()).markVideoRunningAndKeepWorkAuditing(
                eq(105L), eq(15L), eq(1), anyString(), anyString(), anyString());
        assertThat(reasonCaptor.getValue()).contains("视频审核查询次数超过上限", "queryCount=120", "maxQueryCount=120");
    }

    @Test
    void expiredQueryingVideoShouldQuerySameJobWithSameUniqueToken() {
        WorkAuditTaskEntity task = videoTask(601L, 91L, 4);
        task.setTaskStatus("QUERYING");
        task.setLockedUntil(LocalDateTime.of(2026, 8, 13, 11, 59));
        LocalDateTime now = LocalDateTime.of(2026, 8, 13, 12, 0);
        WorkAuditWorkRepository workRepository = mock(WorkAuditWorkRepository.class);
        WorkAuditTaskRepository taskRepository = mock(WorkAuditTaskRepository.class);
        WorkAuditClaimTransactionService claimTransactionService = mock(WorkAuditClaimTransactionService.class);
        TencentCiAuditClient auditClient = mock(TencentCiAuditClient.class);
        when(taskRepository.findQueryableVideoTasks(10, 120, now)).thenReturn(List.of(task));
        when(taskRepository.claimVideoQuery(
                eq(601L), anyString(), eq(now), eq(now.plusMinutes(5)), eq(120))).thenReturn(true);
        when(auditClient.queryVideo("video-job-id")).thenReturn(new TencentCiAuditResult(
                "video-job-id", "Running", AuditResultDict.UNKNOWN,
                null, null, null, false, false, "{}"));
        when(claimTransactionService.markVideoRunningAndKeepWorkAuditing(
                eq(601L), eq(91L), eq(1), anyString(), eq("Running"), eq("{}"))).thenReturn(true);
        WorkAuditService service =
                new WorkAuditService(workRepository, taskRepository, claimTransactionService, auditClient, properties());

        service.queryPendingVideoResults(10, now);

        ArgumentCaptor<String> claimToken = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> writeToken = ArgumentCaptor.forClass(String.class);
        verify(taskRepository).claimVideoQuery(
                eq(601L), claimToken.capture(), eq(now), eq(now.plusMinutes(5)), eq(120));
        verify(claimTransactionService).markVideoRunningAndKeepWorkAuditing(
                eq(601L), eq(91L), eq(1), writeToken.capture(), eq("Running"), eq("{}"));
        assertThat(claimToken.getValue()).matches("test-[0-9a-f]{32}");
        assertThat(writeToken.getValue()).isEqualTo(claimToken.getValue());
        verify(auditClient).queryVideo("video-job-id");
    }

    @Test
    void exhaustedExpiredVideoQueryShouldFailWithoutRemoteCall() {
        WorkAuditTaskEntity task = videoTask(611L, 92L, 120);
        task.setTaskStatus("QUERYING");
        LocalDateTime now = LocalDateTime.of(2026, 8, 13, 12, 0);
        WorkAuditWorkRepository workRepository = mock(WorkAuditWorkRepository.class);
        WorkAuditTaskRepository taskRepository = mock(WorkAuditTaskRepository.class);
        WorkAuditClaimTransactionService claimTransactionService = mock(WorkAuditClaimTransactionService.class);
        TencentCiAuditClient auditClient = mock(TencentCiAuditClient.class);
        when(taskRepository.findExhaustedExpiredVideoQueryTasks(10, 120, now)).thenReturn(List.of(task));
        when(taskRepository.claimExhaustedExpiredVideoQuery(
                eq(611L), anyString(), eq(now), eq(now.plusMinutes(5)), eq(120))).thenReturn(true);
        WorkAuditService service =
                new WorkAuditService(workRepository, taskRepository, claimTransactionService, auditClient, properties());

        service.recoverExhaustedVideoQueries(10, now);

        ArgumentCaptor<String> reasonCaptor = ArgumentCaptor.forClass(String.class);
        verify(claimTransactionService).markVideoTaskFailedAndUpdateWorkFailed(
                eq(611L), eq(92L), eq(1), anyString(),
                eq("视频审核查询次数超过上限"), eq(null), reasonCaptor.capture());
        assertThat(reasonCaptor.getValue()).contains(
                "视频审核查询次数超过上限", "queryCount=120", "maxQueryCount=120");
        verifyNoInteractions(auditClient);
    }

    @Test
    void exhaustedVideoQueryRecoveryShouldLogClaimAndWritebackSkips() {
        LocalDateTime now = LocalDateTime.of(2026, 8, 13, 12, 0);
        WorkAuditTaskEntity contested = videoTask(631L, 94L, 120);
        contested.setTaskStatus("QUERYING");
        contested.setLockedUntil(now.minusMinutes(5));
        WorkAuditTaskEntity stale = videoTask(632L, 95L, 120);
        stale.setTaskStatus("QUERYING");
        stale.setLockedUntil(now.minusMinutes(4));
        WorkAuditWorkRepository workRepository = mock(WorkAuditWorkRepository.class);
        WorkAuditTaskRepository taskRepository = mock(WorkAuditTaskRepository.class);
        WorkAuditClaimTransactionService claimTransactionService = mock(WorkAuditClaimTransactionService.class);
        TencentCiAuditClient auditClient = mock(TencentCiAuditClient.class);
        when(taskRepository.findExhaustedExpiredVideoQueryTasks(2, 120, now))
                .thenReturn(List.of(contested, stale));
        when(taskRepository.claimExhaustedExpiredVideoQuery(
                eq(632L), anyString(), eq(now), eq(now.plusMinutes(5)), eq(120))).thenReturn(true);
        WorkAuditService service =
                new WorkAuditService(workRepository, taskRepository, claimTransactionService, auditClient, properties());

        List<String> messages = captureLogMessages(() -> service.recoverExhaustedVideoQueries(2, now));

        assertThat(messages).contains(
                "视频查询终态回收跳过: workId=94, taskId=631, auditRound=1, queryCount=120, "
                        + "maxQueryCount=120, 原锁过期时间=2026-08-13T11:55, reason=未抢占到任务",
                "视频查询终态回收跳过: workId=95, taskId=632, auditRound=1, queryCount=120, "
                        + "maxQueryCount=120, 原锁过期时间=2026-08-13T11:56, "
                        + "reason=任务租约已失效或作品审核轮次已变化");
    }

    @Test
    void videoQueryClaimFailureShouldNotCallTencent() {
        WorkAuditTaskEntity task = videoTask(621L, 93L, 2);
        LocalDateTime now = LocalDateTime.of(2026, 8, 13, 12, 0);
        WorkAuditWorkRepository workRepository = mock(WorkAuditWorkRepository.class);
        WorkAuditTaskRepository taskRepository = mock(WorkAuditTaskRepository.class);
        WorkAuditClaimTransactionService claimTransactionService = mock(WorkAuditClaimTransactionService.class);
        TencentCiAuditClient auditClient = mock(TencentCiAuditClient.class);
        when(taskRepository.findQueryableVideoTasks(10, 120, now)).thenReturn(List.of(task));
        WorkAuditService service =
                new WorkAuditService(workRepository, taskRepository, claimTransactionService, auditClient, properties());

        service.queryPendingVideoResults(10, now);

        verifyNoInteractions(auditClient);
        verifyNoInteractions(claimTransactionService);
    }

    @Test
    void runOneRoundRecoveryQueriesShouldUseSameRoundNow() {
        WorkAuditWorkRepository workRepository = mock(WorkAuditWorkRepository.class);
        WorkAuditTaskRepository taskRepository = mock(WorkAuditTaskRepository.class);
        WorkAuditClaimTransactionService claimTransactionService = mock(WorkAuditClaimTransactionService.class);
        TencentCiAuditClient auditClient = mock(TencentCiAuditClient.class);
        WorkAuditService service =
                new WorkAuditService(workRepository, taskRepository, claimTransactionService, auditClient, properties());

        service.runOneRound();

        ArgumentCaptor<LocalDateTime> backlogNow = ArgumentCaptor.forClass(LocalDateTime.class);
        ArgumentCaptor<LocalDateTime> submitExhaustedNow = ArgumentCaptor.forClass(LocalDateTime.class);
        ArgumentCaptor<LocalDateTime> submitRetryNow = ArgumentCaptor.forClass(LocalDateTime.class);
        ArgumentCaptor<LocalDateTime> queryExhaustedNow = ArgumentCaptor.forClass(LocalDateTime.class);
        ArgumentCaptor<LocalDateTime> firstQueryNow = ArgumentCaptor.forClass(LocalDateTime.class);
        verify(taskRepository).countQueryableVideoTasks(eq(120), backlogNow.capture());
        verify(taskRepository).findExhaustedExpiredVideoSubmitTasks(
                eq(500), eq(3), submitExhaustedNow.capture());
        verify(taskRepository).findRetryableExpiredVideoSubmitTasks(
                eq(500), eq(3), submitRetryNow.capture());
        verify(taskRepository).findExhaustedExpiredVideoQueryTasks(
                eq(1000), eq(120), queryExhaustedNow.capture());
        verify(taskRepository, times(2)).findQueryableVideoTasks(
                eq(1000), eq(120), firstQueryNow.capture());
        assertThat(List.of(
                backlogNow.getValue(), submitExhaustedNow.getValue(), submitRetryNow.getValue(),
                queryExhaustedNow.getValue(), firstQueryNow.getAllValues().get(0)))
                .containsOnly(backlogNow.getValue());
    }

    @Test
    void auditRejectReasonShouldBeTruncatedTo512Characters() {
        WorkAuditWorkRepository workRepository = mock(WorkAuditWorkRepository.class);
        WorkAuditTaskRepository taskRepository = mock(WorkAuditTaskRepository.class);
        WorkAuditClaimTransactionService claimTransactionService = mock(WorkAuditClaimTransactionService.class);
        TencentCiAuditClient auditClient = mock(TencentCiAuditClient.class);
        WorkAuditService service =
                new WorkAuditService(workRepository, taskRepository, claimTransactionService, auditClient, properties());
        String longReason = "失败原因".repeat(200);

        String truncated = ReflectionTestUtils.invokeMethod(service, "truncateAuditRejectReason", longReason);

        assertThat(truncated.codePointCount(0, truncated.length())).isLessThanOrEqualTo(512);
    }

    @ParameterizedTest
    @CsvSource(value = {
            "NULL,1",
            "0,1",
            "-1,1",
            "60000,1",
            "60001,2",
            "270000,5",
            "7200001,120"
    }, nullValues = "NULL")
    void calculateSnapshotCountShouldCoverDurationBoundaries(Integer durationMs, int expectedCount) {
        WorkAuditWorkRepository workRepository = mock(WorkAuditWorkRepository.class);
        WorkAuditTaskRepository taskRepository = mock(WorkAuditTaskRepository.class);
        WorkAuditClaimTransactionService claimTransactionService = mock(WorkAuditClaimTransactionService.class);
        TencentCiAuditClient auditClient = mock(TencentCiAuditClient.class);
        WorkAuditService service =
                new WorkAuditService(workRepository, taskRepository, claimTransactionService, auditClient, properties());

        Integer actualCount = ReflectionTestUtils.invokeMethod(service, "calculateSnapshotCount", durationMs);

        assertThat(actualCount).isEqualTo(expectedCount);
    }

    private WorkAuditProperties properties() {
        WorkAuditProperties properties = new WorkAuditProperties();
        properties.setLockOwnerPrefix("test");
        properties.setVideoQueryMaxAttempts(120);
        properties.setVideoSnapshotIntervalSeconds(60);
        properties.setMaxVideoSnapshotCount(120);
        return properties;
    }

    /**
     * 捕获作品审核服务在指定操作期间输出的格式化日志消息。
     *
     * @param action 待执行操作
     * @return 格式化日志消息列表
     */
    private List<String> captureLogMessages(Runnable action) {
        Logger logger = (Logger) LoggerFactory.getLogger(WorkAuditService.class);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        try {
            action.run();
            return appender.list.stream()
                    .map(ILoggingEvent::getFormattedMessage)
                    .toList();
        } finally {
            logger.detachAppender(appender);
            appender.stop();
        }
    }

    private WorkAuditWorkEntity work(Long id, MediaTypeDict mediaType, String objectKey, Integer durationMs) {
        WorkAuditWorkEntity work = new WorkAuditWorkEntity();
        work.setId(id);
        work.setUserId(99L);
        work.setMediaType(mediaType.getCode());
        work.setMediaObjectKey(objectKey);
        work.setMediaSha256("0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef");
        work.setAuditRound(1);
        work.setDurationMs(durationMs);
        return work;
    }

    private WorkAuditTaskEntity videoTask(Long id, Long workId, Integer queryCount) {
        WorkAuditTaskEntity task = new WorkAuditTaskEntity();
        task.setId(id);
        task.setWorkId(workId);
        task.setMediaObjectKey("video.mp4");
        task.setCiJobId("video-job-id");
        task.setAuditRound(1);
        task.setQueryCount(queryCount);
        return task;
    }

    private TencentCiAuditResult imageResult(AuditResultDict result, Integer ciResult,
                                             String label, Integer score) {
        return new TencentCiAuditResult(
                null, "Success", result, ciResult, label, score,
                true, false, "{}", List.of());
    }
}
