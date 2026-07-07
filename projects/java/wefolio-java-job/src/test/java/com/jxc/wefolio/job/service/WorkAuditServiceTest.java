package com.jxc.wefolio.job.service;

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
import org.mockito.InOrder;
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

        verify(taskRepository).markFailed(eq(101L), anyString(), anyString());
        verify(workRepository).updateAuditStatus(11L, WorkAuditStatusDict.FAILED);
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

        verify(taskRepository).markSuccess(eq(102L), eq(AuditResultDict.REVIEW), any(), eq(1), eq("Porn"), eq(90), eq("{}"));
        verify(workRepository).updateAuditStatus(12L, WorkAuditStatusDict.REVIEW_REQUIRED);
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

        verify(taskRepository).markQueryFailureForNextRun(eq(103L), anyString(), anyString());
        verify(taskRepository, never()).markFailed(eq(103L), anyString(), anyString());
        verify(workRepository, never()).updateAuditStatus(eq(13L), eq(WorkAuditStatusDict.FAILED));
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

        verify(taskRepository).markFailed(eq(104L), anyString(), eq("{}"));
        verify(workRepository).updateAuditStatus(14L, WorkAuditStatusDict.FAILED);
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

        verify(taskRepository).markFailed(eq(105L), anyString(), eq("{}"));
        verify(taskRepository, never()).markVideoRunning(eq(105L), anyString(), anyString());
        verify(workRepository).updateAuditStatus(15L, WorkAuditStatusDict.FAILED);
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
