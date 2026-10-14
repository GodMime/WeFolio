package com.jxc.wefolio.job.service;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;
import com.jxc.wefolio.job.config.CosProperties;
import com.jxc.wefolio.job.config.VideoFrameProperties;
import com.jxc.wefolio.job.repo.VideoFrameBackfillRepository;
import com.qcloud.cos.COSClient;
import com.qcloud.cos.exception.CosServiceException;
import com.qcloud.cos.model.ObjectMetadata;
import com.qcloud.cos.model.PutObjectRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;
import org.springframework.transaction.interceptor.TransactionInterceptor;

import java.net.URI;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** 真实清单CAS、严格清理、规范化及特征提取串联验证，COS与付费截图仅使用本机替身。 */
class VideoFrameRebuildIntegrationTest {
    /** 测试对象固定属于同一作品作者，原视频从不下载或删除。 */
    private static final String VIDEO = "OWNER/work/video/a.mp4";
    private static final String BUCKET = "test-bucket";
    private static final String QUEUE = "test-queue";
    private static final String PROVIDER_JOB = "test-snapshot";
    /** 当前作品和摘要引用使用真实H2短事务。 */
    private JdbcTemplate jdbc;
    /** 内存模拟COS对象生命周期，HEAD和DELETE能互相观察。 */
    private COSClient cos;
    private final Map<String, ObjectMetadata> objects = new HashMap<>();
    /** 验证实际远端动作顺序，不只断言调用了某个存储方法。 */
    private final List<String> events = new ArrayList<>();
    private VideoFrameStorageService storage;
    private VideoFrameSnapshotClient snapshots;
    private VideoFrameBackfillLock.ExecutionLock lock;
    private VideoFrameBackfillService service;
    private List<String> previousKeys;
    private String previousManifest;

    /** 准备一件已审核通过、带无特征旧完整帧和旧摘要的视频。 */
    @BeforeEach
    void setup() throws Exception {
        var data = new DriverManagerDataSource("jdbc:h2:mem:frame_rebuild_" + UUID.randomUUID()
                + ";MODE=MySQL;DB_CLOSE_DELAY=-1", "sa", "");
        new ResourceDatabasePopulator(new ClassPathResource("video-frame-backfill-test-schema.sql")).execute(data);
        jdbc = new JdbcTemplate(data);
        jdbc.execute("ALTER TABLE wf_work ADD COLUMN IF NOT EXISTS ai_media_summary_json CLOB");
        jdbc.execute("ALTER TABLE wf_work ADD COLUMN IF NOT EXISTS ai_media_summary_expires_at TIMESTAMP");
        jdbc.update("INSERT INTO wf_user(id,unique_code,status) VALUES(1,'OWNER','ACTIVE')");
        jdbc.update("""
                INSERT INTO wf_work(id,user_id,media_type,status,media_object_key,media_sha256,duration_ms,audit_status)
                VALUES(1,1,'VIDEO','ACTIVE',?,'sha',600000,'PASSED')
                """, VIDEO);
        var repositoryProxy = new ProxyFactory(new VideoFrameBackfillRepository(jdbc));
        repositoryProxy.addAdvice(new TransactionInterceptor(new DataSourceTransactionManager(data),
                new AnnotationTransactionAttributeSource()));
        var repository = (VideoFrameBackfillRepository) repositoryProxy.getProxy();
        cos = mock(COSClient.class);
        var sourceHead = new ObjectMetadata(); sourceHead.setContentLength(1234); sourceHead.setETag("etag");
        objects.put(VIDEO, sourceHead);
        when(cos.getObjectMetadata(eq(BUCKET), anyString())).thenAnswer(call -> {
            ObjectMetadata metadata = objects.get(call.getArgument(1, String.class));
            if (metadata == null) { var missing = new CosServiceException("missing"); missing.setStatusCode(404); throw missing; }
            return metadata;
        });
        when(cos.putObject(any(PutObjectRequest.class))).thenAnswer(call -> {
            PutObjectRequest put = call.getArgument(0); objects.put(put.getKey(), put.getMetadata());
            events.add("PUT"); return null;
        });
        when(cos.getObjectUrl(eq(BUCKET), anyString())).thenAnswer(call ->
                URI.create("https://test-bucket.cos.ap-guangzhou.myqcloud.com/" + call.getArgument(1)).toURL());
        doAnswer(call -> { objects.remove(call.getArgument(1, String.class)); events.add("DELETE"); return null; })
                .when(cos).deleteObject(eq(BUCKET), anyString());
        var properties = new VideoFrameProperties(); var cosProperties = new CosProperties(); cosProperties.setBucketName(BUCKET);
        storage = spy(new VideoFrameStorageService(cos, cosProperties, properties, new VideoFrameImageNormalizer(properties)));
        doReturn(VideoFrameImageNormalizerTest.image(100, 80)).when(storage).download(any(), any(), any());
        VideoFrameSource source = storage.inspectSource(VIDEO, "sha");
        JSONObject legacy = JSON.parseObject(storage.persist(source, 1L, "history-" + UUID.randomUUID(),
                600000, 24, inputs(), Instant.now().plusSeconds(240)));
        previousKeys = legacy.getJSONArray("frames").stream().map(value -> ((JSONObject) value).getString("objectKey")).toList();
        legacy.getJSONArray("frames").forEach(value -> ((JSONObject) value).remove("visualFeatures"));
        previousManifest = legacy.toJSONString();
        jdbc.update("UPDATE wf_work SET video_frames_json=?,ai_media_summary_json='old-summary',ai_media_summary_expires_at=CURRENT_TIMESTAMP WHERE id=1", previousManifest);
        snapshots = mock(VideoFrameSnapshotClient.class);
        when(snapshots.submit(any(), eq(24), anyString(), eq(QUEUE))).thenAnswer(call -> {
            events.add("SUBMIT"); return PROVIDER_JOB;
        });
        when(snapshots.ready(PROVIDER_JOB)).thenReturn(true);
        when(snapshots.outputFrames(anyString(), eq(24))).thenReturn(inputs());
        lock = mock(VideoFrameBackfillLock.ExecutionLock.class);
        service = new VideoFrameBackfillService(repository, storage, snapshots, properties, VideoAudioTestSupport.disabled(), VideoSummaryTestSupport.disabled());
        events.clear(); clearInvocations(cos, storage);
    }

    /** 重建在删除旧对象时数据库已清空，提交截图时旧帧全删，新结果带完整特征和全部审核帧。 */
    @Test
    void clearsDatabaseAndCosBeforeRebuildingAllFramesWithFeatures() {
        doAnswer(call -> {
            assertThat(currentManifest()).isNull();
            assertThat(jdbc.queryForObject("SELECT ai_media_summary_json FROM wf_work WHERE id=1", String.class)).isNull();
            assertThat(previousKeys).contains(call.getArgument(1, String.class));
            objects.remove(call.getArgument(1, String.class)); events.add("DELETE"); return null;
        }).when(cos).deleteObject(eq(BUCKET), anyString());
        when(snapshots.submit(any(), eq(24), anyString(), eq(QUEUE))).thenAnswer(call -> {
            assertThat(objects.keySet()).doesNotContainAnyElementsOf(previousKeys);
            assertThat(currentManifest()).isNull(); events.add("SUBMIT"); return PROVIDER_JOB;
        });
        var summary = service.runRebuild("rebuild", 24, 0, null, lock, QUEUE);
        assertThat(summary.succeeded()).isEqualTo(1);
        assertThat(events.subList(0, 24)).containsOnly("DELETE");
        assertThat(events.get(24)).isEqualTo("SUBMIT");
        assertNewManifest();
        assertThat(jdbc.queryForObject("SELECT version FROM wf_work WHERE id=1", Integer.class)).isEqualTo(2);
        assertThat(jdbc.queryForObject("SELECT audit_status FROM wf_work WHERE id=1", String.class)).isEqualTo("PASSED");
        verify(cos, never()).deleteObject(BUCKET, VIDEO);
    }

    /** 默认历史入口保持跳过有效旧资产；缺帧时也使用相同完整特征生成链路。 */
    @Test
    void ordinaryBackfillSkipsValidLegacyFramesAndGeneratesFeaturesForMissingFrames() {
        assertThat(service.run("backfill-old", 24, lock, QUEUE).skipped()).isEqualTo(1);
        assertThat(currentManifest()).isEqualTo(previousManifest);
        verifyNoInteractions(snapshots);
        jdbc.update("UPDATE wf_work SET video_frames_json=NULL WHERE id=1");
        assertThat(service.run("backfill-missing", 24, lock, QUEUE).succeeded()).isEqualTo(1);
        assertNewManifest();
        verify(cos, never()).deleteObject(anyString(), anyString());
    }

    /** 删除失败会留下空引用且不付费，普通补帧随后可恢复新方案清单。 */
    @Test
    void failedDeletionStopsBeforePaymentAndMissingManifestCanBeBackfilled() {
        doThrow(new IllegalStateException("delete unavailable")).when(cos).deleteObject(BUCKET, previousKeys.getFirst());
        var summary = service.runRebuild("rebuild-failed", 24, 0, null, lock, QUEUE);
        assertThat(summary.failed()).isEqualTo(1);
        assertThat(currentManifest()).isNull();
        assertThat(summary.chargedFrames()).isZero();
        verifyNoInteractions(snapshots);
        assertThat(service.run("backfill-recovery", 24, lock, QUEUE).succeeded()).isEqualTo(1);
        assertNewManifest();
    }

    /** 真正的规范化输出包含全部候选及各帧视觉特征，不只检验mock的固定JSON。 */
    private void assertNewManifest() {
        JSONObject manifest = JSON.parseObject(currentManifest());
        assertThat(manifest.getIntValue("frameCount")).isEqualTo(24);
        assertThat(manifest.getString("objectPrefix")).contains("/history-");
        for (Object value : manifest.getJSONArray("frames")) {
            JSONObject frame = (JSONObject) value;
            assertThat(frame.getJSONObject("visualFeatures").getString("version")).isEqualTo("visual-features-v1");
            assertThat(objects).containsKey(frame.getString("objectKey"));
            assertThat(previousKeys).doesNotContain(frame.getString("objectKey"));
        }
    }

    /** 真实数据库清单字段，清理与发布过程均直接观察。 */
    private String currentManifest() { return jdbc.queryForObject("SELECT video_frames_json FROM wf_work WHERE id=1", String.class); }

    /** 提供互不重复的真实时刻，下载字节由规范化测试图替代。 */
    private List<VideoFrameInput> inputs() {
        return IntStream.range(0, 24).mapToObj(index -> new VideoFrameInput(index * 1000L, null,
                "OWNER/others/video-frames/1/raw/" + index + ".jpg")).toList();
    }
}
