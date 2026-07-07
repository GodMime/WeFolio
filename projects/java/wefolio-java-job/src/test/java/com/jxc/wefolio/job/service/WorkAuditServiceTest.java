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

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 作品审核服务编排测试。
 */
class WorkAuditServiceTest {

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
        inOrder.verify(taskRepository).countQueryableVideoTasks(120);
        inOrder.verify(taskRepository).findQueryableVideoTasks(1000, 120);
        inOrder.verify(workRepository).findPendingVideos(500);
        inOrder.verify(workRepository).findPendingImages(500);
        inOrder.verify(taskRepository).findQueryableVideoTasks(1000, 120);
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
                        + "单轮图片审核作品上限=500, 视频主动查询最大次数=120");
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
        when(taskRepository.countQueryableVideoTasks(120)).thenReturn(3L);
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
                .contains("作品审核待处理统计: 待审核图片作品数=1, 待审核视频作品数=2, 待审核作品总数=3, "
                        + "待查询视频任务数=3, 视频主动查询最大次数=120");
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
        verify(claimTransactionService).markTaskFailedAndUpdateWorkFailed(
                eq(101L), eq(11L), anyString(), anyString(), reasonCaptor.capture());
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

        verify(claimTransactionService).markVideoSubmittedAndUpdateWorkAuditing(
                107L, 17L, "video-job-id", "{}");
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
                eq(102L), eq(12L), eq(AuditResultDict.REVIEW), any(), eq(1), eq("Porn"), eq(90), eq("{}"),
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
                eq(106L), eq(16L), eq(AuditResultDict.PASS), any(), eq(0), eq("Normal"), eq(0), eq("{}"),
                eq(WorkAuditStatusDict.PASSED), eq(null));
    }

    @Test
    void queryFailureShouldReturnRunningWhenUnderLimit() {
        WorkAuditTaskEntity task = videoTask(103L, 13L, 0);
        WorkAuditWorkRepository workRepository = mock(WorkAuditWorkRepository.class);
        WorkAuditTaskRepository taskRepository = mock(WorkAuditTaskRepository.class);
        WorkAuditClaimTransactionService claimTransactionService = mock(WorkAuditClaimTransactionService.class);
        TencentCiAuditClient auditClient = mock(TencentCiAuditClient.class);
        when(taskRepository.findQueryableVideoTasks(1000, 120)).thenReturn(List.of(task));
        when(taskRepository.claimVideoQuery(eq(103L), anyString(), any(), eq(120))).thenReturn(true);
        when(auditClient.queryVideo("video-job-id")).thenThrow(new IllegalStateException("query failed"));
        WorkAuditService service =
                new WorkAuditService(workRepository, taskRepository, claimTransactionService, auditClient, properties());

        service.queryPendingVideoResults(1000);

        verify(claimTransactionService).markQueryFailureForNextRunAndKeepWorkAuditing(
                eq(103L), eq(13L), anyString(), anyString());
        verify(claimTransactionService, never()).markTaskFailedAndUpdateWorkFailed(
                eq(103L), eq(13L), anyString(), anyString(), any());
    }

    @Test
    void videoPassResultShouldSetWorkPassedAndClearAuditRejectReason() {
        WorkAuditTaskEntity task = videoTask(108L, 18L, 3);
        WorkAuditWorkRepository workRepository = mock(WorkAuditWorkRepository.class);
        WorkAuditTaskRepository taskRepository = mock(WorkAuditTaskRepository.class);
        WorkAuditClaimTransactionService claimTransactionService = mock(WorkAuditClaimTransactionService.class);
        TencentCiAuditClient auditClient = mock(TencentCiAuditClient.class);
        when(taskRepository.findQueryableVideoTasks(1000, 120)).thenReturn(List.of(task));
        when(taskRepository.claimVideoQuery(eq(108L), anyString(), any(), eq(120))).thenReturn(true);
        when(auditClient.queryVideo("video-job-id")).thenReturn(new TencentCiAuditResult(
                "video-job-id", "Success", AuditResultDict.PASS, 0, "Normal", 0, true, false, "{}"));
        WorkAuditService service =
                new WorkAuditService(workRepository, taskRepository, claimTransactionService, auditClient, properties());

        service.queryPendingVideoResults(1000);

        verify(claimTransactionService).markTaskSuccessAndUpdateWork(
                eq(108L), eq(18L), eq(AuditResultDict.PASS), eq("Success"), eq(0), eq("Normal"), eq(0), eq("{}"),
                eq(WorkAuditStatusDict.PASSED), eq(null));
    }

    @Test
    void runningVideoAtQueryLimitShouldFail() {
        WorkAuditTaskEntity task = videoTask(104L, 14L, 119);
        WorkAuditWorkRepository workRepository = mock(WorkAuditWorkRepository.class);
        WorkAuditTaskRepository taskRepository = mock(WorkAuditTaskRepository.class);
        WorkAuditClaimTransactionService claimTransactionService = mock(WorkAuditClaimTransactionService.class);
        TencentCiAuditClient auditClient = mock(TencentCiAuditClient.class);
        when(taskRepository.findQueryableVideoTasks(1000, 120)).thenReturn(List.of(task));
        when(taskRepository.claimVideoQuery(eq(104L), anyString(), any(), eq(120))).thenReturn(true);
        when(auditClient.queryVideo("video-job-id")).thenReturn(new TencentCiAuditResult(
                "video-job-id", "Running", AuditResultDict.UNKNOWN, null, null, null, false, false, "{}"));
        WorkAuditService service =
                new WorkAuditService(workRepository, taskRepository, claimTransactionService, auditClient, properties());

        service.queryPendingVideoResults(1000);

        ArgumentCaptor<String> reasonCaptor = ArgumentCaptor.forClass(String.class);
        verify(claimTransactionService).markTaskFailedAndUpdateWorkFailed(
                eq(104L), eq(14L), anyString(), eq("{}"), reasonCaptor.capture());
        assertThat(reasonCaptor.getValue()).contains("视频审核查询次数超过上限", "queryCount=120", "maxQueryCount=120");
    }

    @Test
    void staleVideoQuerySnapshotShouldUsePostClaimQueryCount() {
        WorkAuditTaskEntity task = videoTask(105L, 15L, 118);
        WorkAuditWorkRepository workRepository = mock(WorkAuditWorkRepository.class);
        WorkAuditTaskRepository taskRepository = mock(WorkAuditTaskRepository.class);
        WorkAuditClaimTransactionService claimTransactionService = mock(WorkAuditClaimTransactionService.class);
        TencentCiAuditClient auditClient = mock(TencentCiAuditClient.class);
        when(taskRepository.findQueryableVideoTasks(1000, 120)).thenReturn(List.of(task));
        when(taskRepository.claimVideoQuery(eq(105L), anyString(), any(), eq(120))).thenReturn(true);
        when(taskRepository.findQueryCountById(105L)).thenReturn(120);
        when(auditClient.queryVideo("video-job-id")).thenReturn(new TencentCiAuditResult(
                "video-job-id", "Running", AuditResultDict.UNKNOWN, null, null, null, false, false, "{}"));
        WorkAuditService service =
                new WorkAuditService(workRepository, taskRepository, claimTransactionService, auditClient, properties());

        service.queryPendingVideoResults(1000);

        ArgumentCaptor<String> reasonCaptor = ArgumentCaptor.forClass(String.class);
        verify(claimTransactionService).markTaskFailedAndUpdateWorkFailed(
                eq(105L), eq(15L), anyString(), eq("{}"), reasonCaptor.capture());
        verify(claimTransactionService, never()).markVideoRunningAndKeepWorkAuditing(
                eq(105L), eq(15L), anyString(), anyString());
        assertThat(reasonCaptor.getValue()).contains("视频审核查询次数超过上限", "queryCount=120", "maxQueryCount=120");
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

    private WorkAuditWorkEntity work(Long id, MediaTypeDict mediaType, String objectKey, Integer durationMs) {
        WorkAuditWorkEntity work = new WorkAuditWorkEntity();
        work.setId(id);
        work.setUserId(99L);
        work.setMediaType(mediaType.getCode());
        work.setMediaObjectKey(objectKey);
        work.setMediaSha256("0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef");
        work.setDurationMs(durationMs);
        return work;
    }

    private WorkAuditTaskEntity videoTask(Long id, Long workId, Integer queryCount) {
        WorkAuditTaskEntity task = new WorkAuditTaskEntity();
        task.setId(id);
        task.setWorkId(workId);
        task.setMediaObjectKey("video.mp4");
        task.setCiJobId("video-job-id");
        task.setQueryCount(queryCount);
        return task;
    }
}
