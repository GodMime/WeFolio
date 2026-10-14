package com.jxc.wefolio.job.service;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;
import com.jxc.wefolio.job.config.CosProperties;
import com.jxc.wefolio.job.config.VideoFrameProperties;
import com.qcloud.cos.COSClient;
import com.qcloud.cos.exception.CosServiceException;
import com.qcloud.cos.model.ObjectMetadata;
import com.qcloud.cos.model.PutObjectRequest;
import com.qcloud.cos.model.CannedAccessControlList;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.slf4j.LoggerFactory;
import java.awt.image.BufferedImage;
import java.net.URL;
import java.time.Instant;
import java.util.List;
import java.util.function.Consumer;
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
    /** 捕获逐帧日志，验证降级原因能够定位到真实作品与帧。 */
    private final Logger logger = (Logger) LoggerFactory.getLogger(VideoFrameStorageService.class);
    private ListAppender<ILoggingEvent> events;

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
        events = new ListAppender<>();
        events.start();
        logger.addAppender(events);
    }
    /** 避免日志监听器污染其它用例。 */
    @AfterEach
    void detachLogger() {
        if (events != null) { logger.detachAppender(events); events.stop(); }
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
        JSONObject features = manifest.getJSONArray("frames").getJSONObject(0).getJSONObject("visualFeatures");
        assertEquals("visual-features-v1", features.getString("version"));
        assertEquals("0000000000000000", features.getString("dHash"));
        assertEquals(0, features.getIntValue("luminance"));
        assertEquals(0, features.getIntValue("sharpness"));
        List<String> writtenFrames = events.list.stream().map(ILoggingEvent::getFormattedMessage)
                .filter(message -> message.startsWith("视频持久帧写入:")).toList();
        assertEquals(8, writtenFrames.size());
        assertTrue(writtenFrames.stream().allMatch(message -> message.contains("visualFeaturesPresent=true")));
        assertFalse(json.contains("signed"));
        ArgumentCaptor<PutObjectRequest> puts = ArgumentCaptor.forClass(PutObjectRequest.class);
        verify(cos, times(8)).putObject(puts.capture());
        assertTrue(puts.getAllValues().stream().allMatch(request -> request.getCannedAcl() == CannedAccessControlList.Private));
        assertTrue(storage.hasValidManifest(json, source, 7L, deadline));
        storage.cleanupOwnedManifest(json,source,7L);
        verify(cos, times(8)).deleteObject(eq("bucket-1"), startsWith("OWNER/others/video-frames/7/"));
    }
    /** 可选特征缺失不改变旧清单有效性，也不会触发对象写入或删除。 */
    @Test
    void acceptsExistingManifestWithoutVisualFeaturesWithoutRewritingAssets() {
        JSONObject manifest = JSON.parseObject(storage.persist(source, 7L, "audit-1", 23000, 8, inputs(), deadline));
        manifest.getJSONArray("frames").forEach(value -> ((JSONObject) value).remove("visualFeatures"));
        String original = manifest.toJSONString();
        clearInvocations(cos);
        assertTrue(storage.hasValidManifest(original, source, 7L, deadline));
        verify(cos, never()).putObject(any(PutObjectRequest.class));
        verify(cos, never()).deleteObject(anyString(), anyString());
    }

    /** 旧三参数规范化结果不写空特征字段，消费者继续走无特征兼容分支。 */
    @Test
    void persistsFramesWithoutOptionalFeatures() throws Exception {
        CosProperties properties = new CosProperties(); properties.setBucketName("bucket-1");
        VideoFrameProperties frames = new VideoFrameProperties();
        VideoFrameImageNormalizer normalizer = mock(VideoFrameImageNormalizer.class);
        byte[] image = new VideoFrameImageNormalizer(frames).normalize(VideoFrameImageNormalizerTest.image(100, 80)).bytes();
        when(normalizer.normalize(any())).thenReturn(new VideoFrameImageNormalizer.NormalizedImage(image, 100, 80));
        var withoutFeatures = spy(new VideoFrameStorageService(cos, properties, frames, normalizer));
        doReturn(image).when(withoutFeatures).download(any(), any(), any());
        JSONObject manifest = JSON.parseObject(withoutFeatures.persist(source, 7L, "audit-2", 23000, 8, inputs(), deadline));
        assertEquals("balanced-frames-v1", manifest.getString("policyVersion"));
        assertFalse(manifest.getJSONArray("frames").getJSONObject(0).containsKey("visualFeatures"));
        assertTrue(withoutFeatures.hasValidManifest(manifest.toJSONString(), source, 7L, deadline));
    }
    /** 辅助提取失败按每个帧键、时刻和任务记录，异常原文与临时链接不得进入日志或清单。 */
    @Test
    void logsFeatureFailureWithFrameContextWithoutSensitiveExceptionData() throws Exception {
        CosProperties properties = new CosProperties(); properties.setBucketName("bucket-1");
        VideoFrameProperties frames = new VideoFrameProperties();
        var failingNormalizer = new VideoFrameImageNormalizer(frames) {
            /** 保留真实图片规范化，只制造可选分析失败。 */
            @Override
            VideoFrameVisualFeatures analyzeFeatures(BufferedImage image) {
                throw new IllegalStateException("https://private.invalid/frame?signature=secret 原始响应");
            }
        };
        var failingFeatures = spy(new VideoFrameStorageService(cos, properties, frames, failingNormalizer));
        doReturn(VideoFrameImageNormalizerTest.image(100, 80)).when(failingFeatures).download(any(), any(), any());

        String json = failingFeatures.persist(source, 7L, "audit-feature-failure", 23000, 8, inputs(), deadline);
        var manifestFrames = JSON.parseObject(json).getJSONArray("frames");
        List<ILoggingEvent> warnings = events.list.stream().filter(event -> event.getLevel() == Level.WARN).toList();
        assertEquals(8, warnings.size());
        for (int index = 0; index < warnings.size(); index++) {
            JSONObject frame = manifestFrames.getJSONObject(index);
            String message = warnings.get(index).getFormattedMessage();
            assertTrue(message.contains("workId=7, task=audit-feature-failure"));
            assertTrue(message.contains("objectKey=" + frame.getString("objectKey")));
            assertTrue(message.contains("timeMs=" + frame.getLongValue("timeMs")));
            assertTrue(message.contains("exceptionType=IllegalStateException"));
            assertNull(warnings.get(index).getThrowableProxy());
            assertFalse(frame.containsKey("visualFeatures"));
        }
        List<String> writtenFrames = events.list.stream().map(ILoggingEvent::getFormattedMessage)
                .filter(message -> message.startsWith("视频持久帧写入:")).toList();
        assertEquals(8, writtenFrames.size());
        assertTrue(writtenFrames.stream().allMatch(message -> message.contains("visualFeaturesPresent=false")
                && message.contains("featureFailureType=IllegalStateException")));
        assertTrue(events.list.stream().map(ILoggingEvent::getFormattedMessage)
                .noneMatch(message -> message.contains("https://") || message.contains("secret") || message.contains("原始响应")));
        assertFalse(json.contains("IllegalStateException"));
        assertTrue(failingFeatures.hasValidManifest(json, source, 7L, deadline));
        verify(cos, times(8)).putObject(any(PutObjectRequest.class));
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
    /** 空引用无需远端清理，显式空白或无法解释的非空清单不能在重建前被当作无帧。 */
    @Test
    void strictCleanupAllowsNullButRejectsMalformedNonNullManifestWithoutDeleting() {
        clearInvocations(cos);
        assertDoesNotThrow(() -> storage.validateOwnedManifestForCleanup(null, source, 7L));
        assertDoesNotThrow(() -> storage.cleanupOwnedManifestStrict(null, source, 7L, deadline));
        for (String malformed : List.of("", " ", "null", "[]", "{}", "{broken")) {
            VideoFrameProcessingException failure = assertThrows(VideoFrameProcessingException.class,
                    () -> storage.validateOwnedManifestForCleanup(malformed, source, 7L));
            assertEquals("FRAME_CLEANUP", failure.getStage());
            assertThrows(VideoFrameProcessingException.class,
                    () -> storage.cleanupOwnedManifestStrict(malformed, source, 7L, deadline));
        }
        verifyNoInteractions(cos);
    }
    /** 即使非法键位于列表末尾，完整预检也必须发生在第一次删除之前，原视频和异主路径始终受保护。 */
    @Test
    void strictCleanupValidatesEveryFrameAndControlledPrefixBeforeAnyDelete() {
        JSONObject original = JSON.parseObject(storage.persist(source, 7L, "audit-1", 23000, 8, inputs(), deadline));
        List<Consumer<JSONObject>> corruptions = List.of(
                manifest -> manifest.put("objectPrefix", manifest.getString("objectPrefix").replace("OWNER/", "OTHER/")),
                manifest -> manifest.put("objectPrefix", manifest.getString("objectPrefix").replace("/7/", "/8/")),
                manifest -> manifest.put("objectPrefix", "OWNER/others/video-frames/7/"),
                manifest -> manifest.put("objectPrefix", manifest.getString("objectPrefix").replace("audit-1/", "rebuild-1/")),
                manifest -> manifest.put("objectPrefix", manifest.getString("objectPrefix").replace("audit-1/", "audit-9223372036854775808/")),
                manifest -> manifest.put("frameCount", 7),
                manifest -> manifest.put("frameCount", "8"),
                manifest -> manifest.put("sourceVersion", "b".repeat(64)),
                manifest -> manifest.put("policyVersion", "another-policy"),
                manifest -> manifest.put("sourceObjectKey", "OTHER/work/video/secret.mp4"),
                manifest -> manifest.getJSONArray("frames").getJSONObject(7).put("objectKey", source.objectKey()),
                manifest -> manifest.getJSONArray("frames").getJSONObject(7).put("objectKey", manifest.getString("objectPrefix") + "0025.jpg"),
                manifest -> manifest.getJSONArray("frames").getJSONObject(7).put("objectKey", manifest.getString("objectPrefix") + "raw/0008.jpg"),
                manifest -> manifest.getJSONArray("frames").getJSONObject(7).put("objectKey", manifest.getString("objectPrefix") + "../0008.jpg"),
                manifest -> manifest.getJSONArray("frames").getJSONObject(7).put("objectKey", manifest.getString("objectPrefix") + "%2e%2e/0008.jpg"),
                manifest -> manifest.getJSONArray("frames").getJSONObject(7).put("objectKey", manifest.getJSONArray("frames").getJSONObject(0).getString("objectKey")),
                manifest -> manifest.getJSONArray("frames").set(7, "invalid-frame"));
        clearInvocations(cos);
        for (Consumer<JSONObject> corruption : corruptions) {
            JSONObject invalid = JSON.parseObject(original.toJSONString());
            corruption.accept(invalid);
            VideoFrameProcessingException failure = assertThrows(VideoFrameProcessingException.class,
                    () -> storage.cleanupOwnedManifestStrict(invalid.toJSONString(), source, 7L, deadline));
            assertEquals("FRAME_CLEANUP", failure.getStage());
        }
        assertThrows(VideoFrameProcessingException.class,
                () -> storage.cleanupOwnedManifestStrict(original.toJSONString(), source, 8L, deadline));
        verifyNoInteractions(cos);
    }
    /** 预检不触碰远端；重复删除已不存在帧为成功，且只删除完整旧清单明确列出的对象。 */
    @Test
    void strictCleanupDeletesOnlyOwnedFramesAndIsIdempotentForMissingObjects() {
        String manifest = storage.persist(source, 7L, "history-00000000-0000-0000-0000-000000000001", 23000, 8, inputs(), deadline);
        JSONObject parsed = JSON.parseObject(manifest);
        List<String> keys = parsed.getJSONArray("frames").stream()
                .map(frame -> ((JSONObject) frame).getString("objectKey")).toList();
        clearInvocations(cos);
        assertDoesNotThrow(() -> storage.validateOwnedManifestForCleanup(manifest, source, 7L));
        verifyNoInteractions(cos);
        assertDoesNotThrow(() -> storage.cleanupOwnedManifestStrict(manifest, source, 7L, deadline));
        var missing = new CosServiceException("https://private.invalid?signature=secret"); missing.setStatusCode(404);
        doThrow(missing).when(cos).deleteObject(eq("bucket-1"), anyString());
        assertDoesNotThrow(() -> storage.cleanupOwnedManifestStrict(manifest, source, 7L, deadline));
        for (String key : keys) { verify(cos, times(2)).deleteObject("bucket-1", key); }
        verify(cos, never()).deleteObject("bucket-1", source.objectKey());
        verifyNoMoreInteractions(cos);
    }
    /** 部分删除失败必须中断重建并保留安全失败信息，不能把后续帧当作已清完。 */
    @Test
    void strictCleanupStopsAtPartialDeleteFailureWithoutLeakingRemoteResponse() {
        String manifest = storage.persist(source, 7L, "audit-1", 23000, 8, inputs(), deadline);
        var frames = JSON.parseObject(manifest).getJSONArray("frames");
        String first = frames.getJSONObject(0).getString("objectKey");
        String failed = frames.getJSONObject(1).getString("objectKey");
        var unavailable = new CosServiceException("https://private.invalid?signature=secret 原始响应");
        unavailable.setStatusCode(503);
        doThrow(unavailable).when(cos).deleteObject("bucket-1", failed);
        clearInvocations(cos);
        events.list.clear();

        VideoFrameProcessingException failure = assertThrows(VideoFrameProcessingException.class,
                () -> storage.cleanupOwnedManifestStrict(manifest, source, 7L, deadline));

        assertEquals("FRAME_CLEANUP", failure.getStage());
        assertFalse(failure.getMessage().contains("secret"));
        assertNull(failure.getCause());
        verify(cos).deleteObject("bucket-1", first);
        verify(cos).deleteObject("bucket-1", failed);
        verifyNoMoreInteractions(cos);
        assertTrue(events.list.stream().map(ILoggingEvent::getFormattedMessage)
                .anyMatch(message -> message.contains("workId=7") && message.contains(failed)
                        && message.contains("exceptionType=CosServiceException")));
        assertTrue(events.list.stream().map(ILoggingEvent::getFormattedMessage)
                .noneMatch(message -> message.contains("https://") || message.contains("secret") || message.contains("原始响应")));
    }
    /** 过期或不足一次远端调用的期限在第一次删除前中断，不能继续扩大已清空引用后的破坏范围。 */
    @Test
    void strictCleanupRejectsExpiredOrInsufficientDeadlineBeforeDeleting() {
        String manifest = storage.persist(source, 7L, "audit-1", 23000, 8, inputs(), deadline);
        clearInvocations(cos);
        for (Instant tooLate : List.of(Instant.now().minusSeconds(1), Instant.now().plusSeconds(1))) {
            VideoFrameProcessingException failure = assertThrows(VideoFrameProcessingException.class,
                    () -> storage.cleanupOwnedManifestStrict(manifest, source, 7L, tooLate));
            assertEquals("PROCESS_INTERRUPTED", failure.getStage());
        }
        VideoFrameProcessingException missing = assertThrows(VideoFrameProcessingException.class,
                () -> storage.cleanupOwnedManifestStrict(manifest, source, 7L, null));
        assertEquals("PROCESS_INTERRUPTED", missing.getStage());
        verifyNoInteractions(cos);
    }
    /** 首帧删除后收到停机中断，下一帧删除前立即停止且保留中断状态供调用方结束扫描。 */
    @Test
    void strictCleanupChecksInterruptionBeforeEachDelete() {
        String manifest = storage.persist(source, 7L, "audit-1", 23000, 8, inputs(), deadline);
        String first = JSON.parseObject(manifest).getJSONArray("frames").getJSONObject(0).getString("objectKey");
        doAnswer(call -> { Thread.currentThread().interrupt(); return null; })
                .when(cos).deleteObject("bucket-1", first);
        clearInvocations(cos);
        try {
            VideoFrameProcessingException failure = assertThrows(VideoFrameProcessingException.class,
                    () -> storage.cleanupOwnedManifestStrict(manifest, source, 7L, deadline));
            assertEquals("PROCESS_INTERRUPTED", failure.getStage());
            assertTrue(Thread.currentThread().isInterrupted());
            verify(cos).deleteObject("bucket-1", first);
            verifyNoMoreInteractions(cos);
        } finally { Thread.interrupted(); }
    }
    /** 旧接口继续容忍无效输入和远端删除失败，不改变既有回滚清理行为。 */
    @Test
    void existingBestEffortCleanupStillSwallowsDeleteFailures() {
        String manifest = storage.persist(source, 7L, "audit-1", 23000, 8, inputs(), deadline);
        doThrow(new IllegalStateException("delete failed")).when(cos).deleteObject(anyString(), anyString());
        clearInvocations(cos);
        assertDoesNotThrow(() -> storage.cleanupOwnedManifest(" ", source, 7L));
        assertDoesNotThrow(() -> storage.cleanupOwnedManifest(manifest, source, 7L));
        verify(cos, times(8)).deleteObject(eq("bucket-1"), startsWith("OWNER/others/video-frames/7/"));
    }
    /** 原视频摘要 HEAD 必须预留真实 SDK 超时，时间不足时不发送，也没有隐藏重试。 */
    @Test void directSummaryHeadRequiresFullCallBudgetAndDoesNotRetry() {
        clearInvocations(cos);
        assertEquals(5, storage.headTimeoutSeconds());
        assertThrows(VideoFrameProcessingException.class,
                () -> storage.head(source.objectKey(), Instant.now().plusSeconds(4)));
        verifyNoInteractions(cos);
        when(cos.getObjectMetadata("bucket-1", source.objectKey())).thenThrow(new IllegalStateException("临时远端错误"));
        assertThrows(IllegalStateException.class, () -> storage.head(source.objectKey(), deadline));
        verify(cos, times(1)).getObjectMetadata("bucket-1", source.objectKey());
    }

    /** 远端完成后收到中断，最终源复验不能将该结果当作仍有效。 */
    @Test void directSummaryHeadChecksInterruptionAfterResponse() {
        when(cos.getObjectMetadata("bucket-1", source.objectKey())).thenAnswer(call -> {
            Thread.currentThread().interrupt(); return new ObjectMetadata();
        });
        try {
            assertThrows(VideoFrameProcessingException.class, () -> storage.head(source.objectKey(), deadline));
            assertTrue(Thread.currentThread().isInterrupted());
        } finally { Thread.interrupted(); }
    }

    /** 故意倒序输入，验证清单按真实时间排序。 */
    private List<VideoFrameInput> inputs() { return IntStream.range(0, 8).mapToObj(i ->
            new VideoFrameInput((7L - i) * 1000, "https://bucket.cos.ap-guangzhou.myqcloud.com/frame-" + i, null)).toList(); }
}
