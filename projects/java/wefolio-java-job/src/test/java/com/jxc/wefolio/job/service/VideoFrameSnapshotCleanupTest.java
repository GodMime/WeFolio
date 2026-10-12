package com.jxc.wefolio.job.service;

import com.jxc.wefolio.job.config.CosProperties;
import com.jxc.wefolio.job.config.VideoFrameProperties;
import com.jxc.wefolio.job.model.VideoFrameBackfillModels.Work;
import com.jxc.wefolio.job.repo.VideoFrameBackfillRepository;
import com.qcloud.cos.COSClient;
import com.qcloud.cos.model.COSObjectSummary;
import com.qcloud.cos.model.ListObjectsRequest;
import com.qcloud.cos.model.ObjectMetadata;
import com.qcloud.cos.model.ObjectListing;
import com.qcloud.cos.model.ciModel.job.MediaJobObject;
import com.qcloud.cos.model.ciModel.job.MediaListJobResponse;
import org.junit.jupiter.api.Test;
import org.springframework.core.task.TaskExecutor;

import java.util.List;
import java.util.Optional;
import java.util.Date;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** 真实历史提交响应丢失后，跨次定时补偿仅删除已终态的供应商原始输出。 */
class VideoFrameSnapshotCleanupTest {
    @Test
    void lostSubmitResponseIsCleanedAfterProviderFinishesWithoutAnotherPaidSubmission() throws Exception {
        COSClient cos = mock(COSClient.class);
        CosProperties properties = new CosProperties();
        properties.setBucketName("bucket-1"); properties.setRegion("ap-guangzhou");
        VideoFrameProperties frames = new VideoFrameProperties();
        VideoFrameSnapshotClient snapshots = new VideoFrameSnapshotClient(cos, properties, frames);
        VideoFrameBackfillRepository repository = mock(VideoFrameBackfillRepository.class);
        VideoFrameStorageService storage = mock(VideoFrameStorageService.class);
        VideoFrameSource source = new VideoFrameSource("OWNER/work/video/a.mp4", "a".repeat(64), "etag", 1000);
        Work work = new Work(1, source.objectKey(), "sha", 23000, null, 0, "OWNER");
        when(repository.highWatermark()).thenReturn(1L);
        when(repository.scan(0, 1, 20)).thenReturn(List.of(work));
        when(repository.scan(1, 1, 20)).thenReturn(List.of());
        when(repository.ownedSource(work)).thenReturn(true);
        when(storage.inspectSource(source.objectKey(), "sha")).thenReturn(source);
        when(storage.targetPrefix(eq(source), eq(1L), anyString())).thenAnswer(call ->
                "OWNER/others/video-frames/1/" + source.sourceVersion() + "/v1/" + call.getArgument(2) + "/");
        AtomicReference<MediaJobObject> providerJob = new AtomicReference<>();
        AtomicReference<String> rawKey = new AtomicReference<>();
        when(cos.createMediaJobs(any())).thenAnswer(call -> {
            var request = (com.qcloud.cos.model.ciModel.job.MediaJobsRequest) call.getArgument(0);
            MediaJobObject job = new MediaJobObject();
            job.setJobId("job-response-lost"); job.setState("Running"); job.setTag("Snapshot");
            job.setInput(request.getInput()); job.setOperation(request.getOperation());
            providerJob.set(job);
            rawKey.set(request.getOperation().getOutput().getObject().replace("${number}", "1").replace("${time}", "1000"));
            throw new IllegalStateException("submit response lost");
        });
        MediaListJobResponse jobPage = new MediaListJobResponse();
        when(cos.describeMediaJobs(any())).thenAnswer(call -> {
            jobPage.setJobsDetailList(List.of(providerJob.get())); return jobPage;
        });
        when(cos.listObjects(any(ListObjectsRequest.class))).thenAnswer(call -> {
            ObjectListing listing = new ObjectListing();
            COSObjectSummary output = new COSObjectSummary(); output.setKey(rawKey.get());
            listing.getObjectSummaries().add(output); return listing;
        });
        VideoFrameBackfillLock.ExecutionLock executionLock = mock(VideoFrameBackfillLock.ExecutionLock.class);
        VideoFrameBackfillService backfill = new VideoFrameBackfillService(repository, storage, snapshots, frames);
        var failed = backfill.run("execution-lost", 8, executionLock, "queue-1");
        assertEquals(1, failed.failed()); assertEquals(8, failed.chargedFrames());
        verify(cos, never()).deleteObject(anyString(), anyString());

        // 模拟新的服务执行；只依赖供应商任务清单，不依赖丢失的本机 JobId。
        VideoFrameBackfillLock locks = mock(VideoFrameBackfillLock.class);
        when(locks.tryAcquire()).thenReturn(Optional.of(executionLock));
        TaskExecutor executor = Runnable::run;
        var maintenance = new VideoFrameBackfillExecutionService(mock(AdminPointSecretValidator.class), backfill,
                snapshots, frames, new JobExecutionLifecycle(), locks, executor);
        maintenance.cleanupFinishedSnapshots();
        verify(cos, never()).deleteObject(anyString(), anyString());
        providerJob.get().setState("Success");
        maintenance.cleanupFinishedSnapshots();
        verify(cos).deleteObject("bucket-1", rawKey.get());
        verify(cos, times(1)).createMediaJobs(any());
        verify(repository, never()).saveManifest(any(), anyString());
    }

    /** 供应商历史已过期也不遗留原始图片；新输出与持久帧始终保留。 */
    @Test
    void expiredProviderHistoryStillCleansOldRawButKeepsNewRawAndPersistentFrames() {
        COSClient cos = mock(COSClient.class);
        CosProperties properties = new CosProperties();
        properties.setBucketName("bucket-1"); properties.setRegion("ap-guangzhou");
        VideoFrameProperties frames = new VideoFrameProperties();
        VideoFrameSnapshotClient snapshots = new VideoFrameSnapshotClient(cos, properties, frames);
        when(cos.describeMediaJobs(any())).thenReturn(new MediaListJobResponse());
        String prefix = "OWNER/others/video-frames/1/" + "a".repeat(64) + "/v1/history-00000000-0000-0000-0000-000000000001/";
        String expired = prefix + "raw/raw-1-1000.jpg";
        String recent = prefix + "raw/raw-2-2000.jpg";
        String persistent = prefix + "0001.jpg";
        String auditOutput = prefix.replace("history-00000000-0000-0000-0000-000000000001", "audit-task") + "raw/raw-1-1000.jpg";
        ObjectMetadata expiredMetadata = new ObjectMetadata();
        expiredMetadata.setLastModified(Date.from(Instant.now().minus(Duration.ofHours(25))));
        when(cos.getObjectMetadata("bucket-1", expired)).thenReturn(expiredMetadata);
        ObjectListing listing = new ObjectListing();
        listing.getObjectSummaries().add(output(expired, Instant.now().minus(Duration.ofHours(25))));
        listing.getObjectSummaries().add(output(recent, Instant.now().minus(Duration.ofHours(1))));
        listing.getObjectSummaries().add(output(persistent, Instant.now().minus(Duration.ofDays(60))));
        listing.getObjectSummaries().add(output(auditOutput, Instant.now().minus(Duration.ofDays(60))));
        when(cos.listObjects(any(ListObjectsRequest.class))).thenReturn(listing);
        VideoFrameBackfillLock.ExecutionLock executionLock = mock(VideoFrameBackfillLock.ExecutionLock.class);
        VideoFrameBackfillLock locks = mock(VideoFrameBackfillLock.class);
        when(locks.tryAcquire()).thenReturn(Optional.of(executionLock));
        var maintenance = new VideoFrameBackfillExecutionService(mock(AdminPointSecretValidator.class), mock(VideoFrameBackfillService.class),
                snapshots, frames, new JobExecutionLifecycle(), locks, Runnable::run);
        maintenance.cleanupFinishedSnapshots();
        verify(cos).deleteObject("bucket-1", expired);
        verify(cos, never()).deleteObject("bucket-1", recent);
        verify(cos, never()).deleteObject("bucket-1", persistent);
        verify(cos, never()).deleteObject("bucket-1", auditOutput);
        verify(cos, never()).createMediaJobs(any());
    }

    /** 构造云端最后写入时间；年龄判断不依赖随机任务创建时间。 */
    private COSObjectSummary output(String key, Instant modified) {
        COSObjectSummary object = new COSObjectSummary();
        object.setKey(key); object.setLastModified(Date.from(modified));
        return object;
    }
}
