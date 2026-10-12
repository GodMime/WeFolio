package com.jxc.wefolio.job.service;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;
import com.jxc.wefolio.job.config.CosProperties;
import com.jxc.wefolio.job.config.VideoFrameProperties;
import com.qcloud.cos.COSClient;
import com.qcloud.cos.model.ObjectMetadata;
import com.qcloud.cos.model.PutObjectRequest;
import com.qcloud.cos.model.CannedAccessControlList;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import java.net.URL;
import java.time.Instant;
import java.util.List;
import java.util.stream.IntStream;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** 新审核与历史共用帧清单、源身份、失败清理和读取边界。 */
class VideoFrameStorageServiceTest {
    private COSClient cos;
    private VideoFrameStorageService storage;
    private VideoFrameSource source;
    private final Instant deadline = Instant.now().plusSeconds(240);

    @BeforeEach
    void setup() throws Exception {
        cos = mock(COSClient.class);
        CosProperties properties = new CosProperties(); properties.setBucketName("bucket-1");
        VideoFrameProperties frames = new VideoFrameProperties();
        storage = spy(new VideoFrameStorageService(cos, properties, frames, new VideoFrameImageNormalizer(frames)));
        ObjectMetadata sourceMetadata = new ObjectMetadata(); sourceMetadata.setETag("\"AbC\""); sourceMetadata.setContentLength(1234);
        when(cos.getObjectMetadata("bucket-1", "OWNER/work/video/a.mp4")).thenReturn(sourceMetadata);
        source = storage.inspectSource("OWNER/work/video/a.mp4", "sha");
        when(cos.getObjectUrl(eq("bucket-1"), anyString())).thenAnswer(call -> new URL("https://bucket-1.cos.ap-guangzhou.myqcloud.com/" + call.getArgument(1)));
        when(cos.putObject(any(PutObjectRequest.class))).thenAnswer(call -> {
            PutObjectRequest request = call.getArgument(0);
            ObjectMetadata metadata = new ObjectMetadata(); metadata.setContentType("image/jpeg");
            metadata.setContentLength(request.getMetadata().getContentLength());
            when(cos.getObjectMetadata("bucket-1", request.getKey())).thenReturn(metadata);
            return null;
        });
        doReturn(VideoFrameImageNormalizerTest.image(100, 80)).when(storage).download(any(), any(), any());
    }
    @Test
    void savesSortedPrivateFramesAndDurationMismatchWithoutDropping() {
        String json = storage.persist(source, 7L, "audit-1", 10, 8, inputs(), deadline);
        JSONObject manifest = JSON.parseObject(json);
        assertEquals(8, manifest.getIntValue("frameCount"));
        assertEquals(0L, manifest.getJSONArray("frames").getJSONObject(0).getLongValue("timeMs"));
        assertEquals(7000L, manifest.getLongValue("lastFrameMs"));
        assertTrue(manifest.getBooleanValue("durationMismatch"));
        assertNull(manifest.get("tailBlindMs"));
        assertEquals("FRONTEND", manifest.getString("durationSource"));
        assertFalse(json.contains("signed"));
        ArgumentCaptor<PutObjectRequest> puts = ArgumentCaptor.forClass(PutObjectRequest.class);
        verify(cos, times(8)).putObject(puts.capture());
        assertTrue(puts.getAllValues().stream().allMatch(request -> request.getCannedAcl() == CannedAccessControlList.Private));
        assertTrue(storage.hasValidManifest(json, source, 7L, deadline));
        storage.cleanupOwnedManifest(json,source,7L);
        verify(cos, times(8)).deleteObject(eq("bucket-1"), startsWith("OWNER/others/video-frames/7/"));
    }
    @Test
    void unknownDurationHasNullTailAndNoEnlargement() {
        var manifest = JSON.parseObject(storage.persist(source, 7L, "audit-1", null, 8, inputs(), deadline));
        assertTrue(manifest.getBooleanValue("durationMissing")); assertNull(manifest.get("tailBlindMs"));
        assertEquals(100, manifest.getJSONArray("frames").getJSONObject(0).getIntValue("width"));
    }
    @Test
    void failedUploadCleansAttemptObjectsAndPreservesFailureStage() {
        when(cos.putObject(any(PutObjectRequest.class))).thenThrow(new IllegalStateException("secret remote response"));
        var failure = assertThrows(VideoFrameProcessingException.class,
                () -> storage.persist(source, 7L, "audit-1", 23000, 8, inputs(), deadline));
        assertEquals("FRAME_UPLOAD", failure.getStage()); assertFalse(failure.getMessage().contains("secret"));
        verify(cos).deleteObject(eq("bucket-1"), endsWith("/audit-1/0001.jpg"));
    }
    @Test
    void rejectsDuplicateTimesAndIncompleteListsBeforeUploading() {
        var duplicate = inputs().stream().map(frame -> new VideoFrameInput(0L, frame.url(), null)).toList();
        assertThrows(VideoFrameProcessingException.class, () -> storage.persist(source, 7L, "audit-1", 23000, 8, duplicate, deadline));
        assertThrows(VideoFrameProcessingException.class, () -> storage.persist(source, 7L, "audit-1", 23000, 8, inputs().subList(0, 7), deadline));
        verify(cos, never()).putObject(any(PutObjectRequest.class));
    }
    @Test
    void sourceReplacementSignalsDiscardAndDeadlineDoesNotWrite() {
        ObjectMetadata changed = new ObjectMetadata(); changed.setContentLength(1234); changed.setETag("replacement");
        when(cos.getObjectMetadata("bucket-1", source.objectKey())).thenReturn(changed);
        assertThrows(VideoFrameSourceChangedException.class, () -> storage.verifySource(source, deadline));
        assertThrows(VideoFrameProcessingException.class, () -> storage.persist(source, 7L, "audit-1", 23000, 8, inputs(), Instant.now()));
        verify(cos, never()).putObject(any(PutObjectRequest.class));
    }
    @Test
    void rejectsExternalAndRedirectableUrlsWithoutNetwork() throws Exception {
        CosProperties properties = new CosProperties(); properties.setBucketName("bucket-1");
        VideoFrameProperties frames = new VideoFrameProperties();
        var original = new VideoFrameStorageService(cos, properties, frames, new VideoFrameImageNormalizer(frames));
        assertThrows(VideoFrameProcessingException.class,
                () -> original.download(new VideoFrameInput(0L, "http://localhost/a.jpg", null), source, deadline));
        assertThrows(VideoFrameProcessingException.class,
                () -> original.download(new VideoFrameInput(0L, "https://external.invalid/a.jpg", null), source, deadline));
        assertThrows(VideoFrameProcessingException.class,
                () -> original.download(new VideoFrameInput(0L, null, "OTHER/others/video-frames/a.jpg"), source, deadline));
    }
    @Test
    void temporaryCosFailureDoesNotTreatExistingAssetAsMissing() {
        String manifest = storage.persist(source,7L,"audit-1",23000,8,inputs(),deadline);
        String key = JSON.parseObject(manifest).getJSONArray("frames").getJSONObject(0).getString("objectKey");
        when(cos.getObjectMetadata("bucket-1",key)).thenThrow(new IllegalStateException("timeout"));
        assertThrows(VideoFrameProcessingException.class, () -> storage.hasValidManifest(manifest,source,7L,deadline));
        com.qcloud.cos.exception.CosServiceException missing = new com.qcloud.cos.exception.CosServiceException("not found"); missing.setStatusCode(404);
        doThrow(missing).when(cos).getObjectMetadata("bucket-1",key);
        assertFalse(storage.hasValidManifest(manifest,source,7L,deadline));
    }
    /** 瞬时 HEAD 故障只重读现有对象，不能把有效作品判失败或重复取帧。 */
    @Test
    void transientHeadFailureRecoversExistingManifestWithoutReupload() {
        String manifest = storage.persist(source, 7L, "audit-1", 23000, 8, inputs(), deadline);
        String key = JSON.parseObject(manifest).getJSONArray("frames").getJSONObject(0).getString("objectKey");
        ObjectMetadata metadata = cos.getObjectMetadata("bucket-1", key);
        clearInvocations(cos);
        com.qcloud.cos.exception.CosServiceException temporary = new com.qcloud.cos.exception.CosServiceException("temporary");
        temporary.setStatusCode(503);
        when(cos.getObjectMetadata("bucket-1", key)).thenThrow(temporary).thenReturn(metadata);
        assertTrue(storage.hasValidManifest(manifest, source, 7L, deadline));
        verify(cos, times(2)).getObjectMetadata("bucket-1", key);
        verify(cos, never()).putObject(any(PutObjectRequest.class));
        verify(cos, never()).deleteObject(anyString(), anyString());
    }

    /** 源复核也允许有界重读，成功后保持原冻结源身份。 */
    @Test
    void transientSourceHeadRecoversBeforePersistingFrames() {
        ObjectMetadata metadata = cos.getObjectMetadata("bucket-1", source.objectKey());
        clearInvocations(cos);
        when(cos.getObjectMetadata("bucket-1", source.objectKey()))
                .thenThrow(new com.qcloud.cos.exception.CosClientException("timeout")).thenReturn(metadata);
        assertDoesNotThrow(() -> storage.verifySource(source, deadline));
        verify(cos, times(2)).getObjectMetadata("bucket-1", source.objectKey());
    }
    /** 服务持续异常最多尝试三次，缺失或鉴权失败不等待重读。 */
    @Test
    void sourceHeadRetriesAreBoundedAndPermanentFailuresAreImmediate() {
        clearInvocations(cos);
        com.qcloud.cos.exception.CosServiceException outage = new com.qcloud.cos.exception.CosServiceException("unavailable");
        outage.setStatusCode(503);
        when(cos.getObjectMetadata("bucket-1", source.objectKey())).thenThrow(outage);
        assertThrows(VideoFrameProcessingException.class, () -> storage.verifySource(source, deadline));
        verify(cos, times(3)).getObjectMetadata("bucket-1", source.objectKey());
        clearInvocations(cos);
        com.qcloud.cos.exception.CosServiceException forbidden = new com.qcloud.cos.exception.CosServiceException("forbidden");
        forbidden.setStatusCode(403);
        doThrow(forbidden).when(cos).getObjectMetadata("bucket-1", source.objectKey());
        assertThrows(VideoFrameProcessingException.class, () -> storage.verifySource(source, deadline));
        verify(cos).getObjectMetadata("bucket-1", source.objectKey());
    }

    /** 重读预算耗尽应报告处理超时，不把源或既有资产误判为无效。 */
    @Test
    void expiredRetryDeadlineReportsInterruptionWithoutExtraHead() {
        clearInvocations(cos);
        Instant shortDeadline = Instant.now().plusSeconds(6);
        when(cos.getObjectMetadata("bucket-1", source.objectKey())).thenAnswer(call -> {
            try { Thread.sleep(1100); }
            catch (InterruptedException exception) { Thread.currentThread().interrupt(); }
            throw new com.qcloud.cos.exception.CosClientException("timeout");
        });
        VideoFrameProcessingException failure = assertThrows(VideoFrameProcessingException.class,
                () -> storage.verifySource(source, shortDeadline));
        assertEquals("PROCESS_INTERRUPTED", failure.getStage());
        verify(cos).getObjectMetadata("bucket-1", source.objectKey());
    }
    @Test
    void oldInvalidManifestNeverDeletesAnotherOwnerOrWork() {
        String manifest=storage.persist(source,7L,"audit-1",23000,8,inputs(),deadline);
        JSONObject crossOwner=JSON.parseObject(manifest);
        String originalPrefix=crossOwner.getString("objectPrefix");
        crossOwner.put("objectPrefix",originalPrefix.replace("OWNER/", "OTHER/"));
        storage.cleanupOwnedManifest(crossOwner.toJSONString(),source,7L);
        storage.cleanupOwnedManifest(manifest,source,8L);
        verify(cos,never()).deleteObject(anyString(),anyString());
        storage.cleanupOwnedManifest(manifest,source,7L);
        verify(cos,times(8)).deleteObject(eq("bucket-1"),startsWith(originalPrefix));
    }
    /** 故意倒序输入，验证清单按真实时间排序。 */
    private List<VideoFrameInput> inputs() { return IntStream.range(0, 8).mapToObj(i ->
            new VideoFrameInput((7L - i) * 1000, "https://bucket.cos.ap-guangzhou.myqcloud.com/frame-" + i, null)).toList(); }
}
