package com.jxc.wefolio.job.service;

import com.alibaba.fastjson2.JSONObject;
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
import org.junit.jupiter.params.provider.ValueSource;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** 新视频审核复用业务边界测试，不访问真实腾讯云或数据库。 */
class WorkAuditPersistentVideoTest {
    private static final String OBJECT_KEY = "OWNER/work/video/demo.mp4";
    private static final VideoFrameSource SOURCE = new VideoFrameSource(OBJECT_KEY, "source-version", "etag", 1024);
    private final WorkAuditWorkRepository works = mock(WorkAuditWorkRepository.class);
    private final WorkAuditTaskRepository tasks = mock(WorkAuditTaskRepository.class);
    private final WorkAuditClaimTransactionService claims = mock(WorkAuditClaimTransactionService.class);
    private final TencentCiAuditClient client = mock(TencentCiAuditClient.class);
    private final VideoFrameStorageService storage = mock(VideoFrameStorageService.class);
    private final WorkAuditProperties properties = new WorkAuditProperties();
    private final WorkAuditService service = new WorkAuditService(works, tasks, claims, client, properties,
            new AnimationFrameSampler(), new AnimationAuditResultAggregator(), mock(AnimationFrameCosService.class), storage);

    /** 缺少帧存储依赖时构造即失败，不留下可能在新调用点产生空指针的服务。 */
    @Test
    void constructorShouldRejectMissingVideoFrameStorage() {
        assertThatThrownBy(() -> new WorkAuditService(works, tasks, claims, client, properties,
                new AnimationFrameSampler(), new AnimationAuditResultAggregator(),
                mock(AnimationFrameCosService.class), null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("持久视频审核需要注入 VideoFrameStorageService");
        verifyNoInteractions(works, tasks, claims, client, storage);
    }

    /** 新提交冻结前端时长、平均模式、数量、源身份与请求参数。 */
    @Test
    void submissionShouldFreezeFrontendDurationAndAverageRequest() {
        properties.setVideoPersistentFramesEnabled(true);
        WorkAuditWorkEntity work = new WorkAuditWorkEntity();
        work.setId(11L); work.setUserId(7L); work.setAuditRound(2); work.setMediaType("VIDEO");
        work.setMediaObjectKey(OBJECT_KEY); work.setDurationMs(180_000); work.setMediaSha256("sha");
        when(works.findPendingVideos(1)).thenReturn(List.of(work));
        when(claims.claimAndCreateSubmittingTask(eq(11L), any())).thenAnswer(invocation -> {
            WorkAuditTaskEntity task = invocation.getArgument(1);
            task.setId(101L);
            assertThat(task.getSnapshotMode()).isEqualTo("AVERAGE");
            assertThat(task.getSnapshotDurationMs()).isEqualTo(180_000);
            assertThat(task.getSnapshotCount()).isEqualTo(18);
            assertThat(task.getSnapshotIntervalSeconds()).isNull();
            return task;
        });
        when(storage.inspectSource(OBJECT_KEY, "sha")).thenReturn(SOURCE);
        when(claims.freezePersistentVideoSource(any(), eq(SOURCE.sourceVersion()), anyString())).thenReturn(true);
        when(client.submitVideo(eq(OBJECT_KEY), any(TencentCiVideoAuditRequest.class)))
                .thenReturn(result(AuditResultDict.UNKNOWN, false));
        service.submitPendingVideoAudits(1);
        verify(client).submitVideo(OBJECT_KEY, TencentCiVideoAuditRequest.average(18));
        verify(client, never()).submitVideo(anyString(), anyInt(), anyInt());
        verify(claims).markPersistentVideoWaiting(any(), any(), eq(true));
        verify(claims, never()).failPersistentVideo(any(), anyString(), anyString());
    }

    /** 中途关闭开关仍按任务模式执行，PASS/REVIEW 先完整存帧再发布审核终态。 */
    @ParameterizedTest
    @ValueSource(strings = {"PASS", "REVIEW"})
    void successfulContentShouldPersistAllFramesBeforePublishing(String code) {
        WorkAuditTaskEntity task = prepareQuery();
        AuditResultDict outcome = AuditResultDict.valueOf(code);
        TencentCiAuditResult result = result(outcome, true);
        when(client.queryVideo("ci-job", true)).thenReturn(result);
        when(storage.persist(eq(SOURCE), eq(11L), eq("audit-101"), eq(23_133), eq(8), eq(result.snapshots()), any()))
                .thenReturn("complete-manifest");
        when(claims.completePersistentVideoWithFrames(eq(task), any(), any(), any(), eq("complete-manifest"))).thenReturn(new PersistentVideoCompletion(true, null));
        service.queryPendingVideoResults(1);
        var order = inOrder(storage, claims);
        order.verify(storage).verifySource(eq(SOURCE), any());
        order.verify(storage).persist(eq(SOURCE), eq(11L), eq("audit-101"), eq(23_133), eq(8), eq(result.snapshots()), any(Instant.class));
        order.verify(claims).completePersistentVideoWithFrames(eq(task), any(), eq(outcome == AuditResultDict.PASS
                ? WorkAuditStatusDict.PASSED : WorkAuditStatusDict.REVIEW_REQUIRED), any(), eq("complete-manifest"));
        verify(storage, never()).cleanupOwnedManifest(anyString(), any(), any());
        verify(claims, never()).markVideoTaskSuccessAndUpdateWork(any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any());
    }

    /** 明确违规沿现有 REJECTED，不为 AI 上传帧。 */
    @Test
    void blockedContentShouldSkipFrameTransfer() {
        WorkAuditTaskEntity task = prepareQuery();
        when(client.queryVideo("ci-job", true)).thenReturn(result(AuditResultDict.BLOCK, true));
        service.queryPendingVideoResults(1);
        verify(storage, never()).persist(any(), any(), any(), any(), anyInt(), any(), any());
        verify(claims).completePersistentVideoWithFrames(eq(task), any(), eq(WorkAuditStatusDict.REJECTED), anyString(), isNull());
    }

    /** 成功新清单提交后，才允许清理不再引用的旧完整清单。 */
    @Test
    void reauditShouldCleanPreviousFramesOnlyAfterNewManifestCommits() {
        WorkAuditTaskEntity task = prepareQuery();
        when(client.queryVideo("ci-job", true)).thenReturn(result(AuditResultDict.PASS, true));
        when(storage.persist(any(), any(), any(), any(), anyInt(), any(), any())).thenReturn("new-manifest");
        when(claims.completePersistentVideoWithFrames(eq(task), any(), any(), any(), eq("new-manifest")))
                .thenReturn(new PersistentVideoCompletion(true, "old-manifest"));
        service.queryPendingVideoResults(1);
        var order = inOrder(claims, works, storage);
        order.verify(claims).completePersistentVideoWithFrames(eq(task), any(), any(), any(), eq("new-manifest"));
        order.verify(works).isVideoManifestPublished(11L, "old-manifest");
        order.verify(storage).cleanupOwnedManifest("old-manifest", SOURCE, 11L);
        verify(storage, never()).cleanupOwnedManifest(eq("new-manifest"), any(), any());
    }

    /** 官方响应属于其他 job 或缺少 jobId 时，不能搬存其他源截图。 */
    @Test
    void mismatchedProviderJobIdShouldFailBeforeFrameTransfer() {
        WorkAuditTaskEntity task = prepareQuery();
        TencentCiAuditResult good = result(AuditResultDict.PASS, true);
        when(client.queryVideo("ci-job", true)).thenReturn(new TencentCiAuditResult("another-job", "Success",
                AuditResultDict.PASS, 0, "Normal", 0, true, false, "{}", List.of(), good.snapshots(), 8));
        service.queryPendingVideoResults(1);
        verify(claims).failPersistentVideo(eq(task), argThat(s -> s.startsWith("AUDIT_QUERY:") && s.contains("任务ID")), anyString());
        verify(storage, never()).persist(any(), any(), any(), any(), anyInt(), any(), any());
    }

    /** 供应商仍在运行只查询原 job，不把正常等待误判为失败。 */
    @Test
    void runningProviderShouldKeepSameJob() {
        WorkAuditTaskEntity task = prepareQuery();
        when(client.queryVideo("ci-job", true)).thenReturn(result(AuditResultDict.UNKNOWN, false));
        service.queryPendingVideoResults(1);
        verify(claims).markPersistentVideoWaiting(eq(task), any(), eq(false));
        verify(claims, never()).failPersistentVideo(any(), any(), any());
        verify(client, never()).submitVideo(anyString(), any(TencentCiVideoAuditRequest.class));
    }

    /** 一次查询异常直接失败，即使查询次数尚未到上限也不进入旧自动重试。 */
    @Test
    void queryExceptionShouldFailImmediatelyWithoutRetry() {
        WorkAuditTaskEntity task = prepareQuery();
        when(client.queryVideo("ci-job", true)).thenThrow(new IllegalStateException("https://private/?q-signature=secret"));
        service.queryPendingVideoResults(1);
        verify(claims).failPersistentVideo(eq(task), argThat(s -> s.startsWith("AUDIT_QUERY:")),
                argThat(s -> !s.contains("q-signature") && !s.contains("https://private")));
        verify(claims, never()).markVideoQueryFailureForNextRunAndKeepWorkAuditing(any(), any(), any(), any(), any(), any());
    }

    /** 缺帧、图片处理、上传和确认每个真实故障都直接失败，没有后备或补缺。 */
    @ParameterizedTest
    @ValueSource(strings = {"FRAME_LIST", "FRAME_DOWNLOAD", "FRAME_NORMALIZE", "FRAME_UPLOAD", "FRAME_VERIFY", "PROCESS_INTERRUPTED"})
    void frameFailuresShouldKeepStageAndEndAudit(String stage) {
        WorkAuditTaskEntity task = prepareQuery();
        when(client.queryVideo("ci-job", true)).thenReturn(result(AuditResultDict.PASS, true));
        when(storage.persist(any(), any(), any(), any(), anyInt(), any(), any()))
                .thenThrow(new VideoFrameProcessingException(stage, "处理失败"));
        service.queryPendingVideoResults(1);
        verify(claims).failPersistentVideo(eq(task), argThat(s -> s.startsWith(stage + ":")), anyString());
        verify(claims, never()).completePersistentVideoWithFrames(any(), any(), any(), any(), any());
        verify(claims, never()).markPersistentVideoWaiting(any(), any(), anyBoolean());
    }

    /** 最终数据库保存失败后独立失败写回，并清理未引用的完整半成品。 */
    @Test
    void finalSaveFailureShouldFailAndCleanOnlyUnpublishedManifest() {
        WorkAuditTaskEntity task = prepareQuery();
        when(client.queryVideo("ci-job", true)).thenReturn(result(AuditResultDict.PASS, true));
        when(storage.persist(any(), any(), any(), any(), anyInt(), any(), any())).thenReturn("manifest");
        when(claims.completePersistentVideoWithFrames(eq(task), any(), any(), any(), eq("manifest")))
                .thenThrow(new IllegalStateException("db failure"));
        service.queryPendingVideoResults(1);
        verify(claims).failPersistentVideo(eq(task), argThat(s -> s.startsWith("MANIFEST_SAVE:")), anyString());
        verify(storage).cleanupOwnedManifest("manifest", SOURCE, 11L);
    }

    /** 数据库提交结果不确定但作品已引用时，禁止删除有效资产。 */
    @Test
    void uncertainCommitMustNotDeleteAlreadyPublishedManifest() {
        WorkAuditTaskEntity task = prepareQuery();
        when(client.queryVideo("ci-job", true)).thenReturn(result(AuditResultDict.PASS, true));
        when(storage.persist(any(), any(), any(), any(), anyInt(), any(), any())).thenReturn("manifest");
        when(claims.completePersistentVideoWithFrames(eq(task), any(), any(), any(), eq("manifest")))
                .thenThrow(new IllegalStateException("commit unknown"));
        when(works.isVideoManifestPublished(11L, "manifest")).thenReturn(true);
        service.queryPendingVideoResults(1);
        verify(storage, never()).cleanupOwnedManifest(anyString(), any(), any());
    }

    /** 租约、源变化或新审核轮次失效只丢弃旧结果，不写当前作品 FAILED。 */
    @Test
    void changedSourceShouldDiscardWithoutFailure() {
        prepareQuery();
        doThrow(new VideoFrameSourceChangedException()).when(storage).verifySource(eq(SOURCE), any());
        service.queryPendingVideoResults(1);
        verify(claims, never()).failPersistentVideo(any(), any(), any());
        verify(tasks).discardChangedPersistentVideo(any());
        verifyNoInteractions(client);
    }

    /** 新策略中断直接失败且不扣新提交容量；旧任务不在本回收列表。 */
    @Test
    void interruptedAverageShouldFailWithoutResubmission() {
        WorkAuditTaskEntity task = persistentTask();
        when(tasks.findInterruptedPersistentVideoTasks(eq(1), any())).thenReturn(List.of(task));
        when(tasks.claimInterruptedPersistentVideo(eq(101L), anyString(), any(), any())).thenReturn(true);
        assertThat(service.recoverExpiredVideoSubmissions(1, LocalDateTime.now())).isEqualTo(1);
        verify(claims).failPersistentVideo(eq(task), argThat(s -> s.startsWith("PROCESS_INTERRUPTED:")), anyString());
        verifyNoInteractions(client);
        verify(storage).verifySource(eq(SOURCE), any());
    }

    /** 官方总数缺失、错误或列表不齐均为真实失败，不能只靠一个 count 判断完整。 */
    @Test
    void providerCountAndListLengthShouldBothMatchFrozenCount() {
        WorkAuditTaskEntity task = prepareQuery();
        TencentCiAuditResult valid = result(AuditResultDict.PASS, true);
        when(client.queryVideo("ci-job", true)).thenReturn(new TencentCiAuditResult(valid.ciJobId(), valid.ciState(),
                valid.auditResult(), valid.ciResult(), valid.ciLabel(), valid.ciScore(), true, false, "{}", List.of(),
                valid.snapshots(), null));
        service.queryPendingVideoResults(1);
        verify(claims).failPersistentVideo(eq(task), argThat(s -> s.startsWith("FRAME_LIST:")), anyString());
        verify(storage, never()).persist(any(), any(), any(), any(), anyInt(), any(), any());
    }

    /** 最小查询夹具冻结真实前端时长，当前开关仍关闭以覆盖部署回退。 */
    private WorkAuditTaskEntity prepareQuery() {
        WorkAuditTaskEntity task = persistentTask();
        when(tasks.findQueryableVideoTasks(eq(1), eq(120), any())).thenReturn(List.of(task));
        when(tasks.claimVideoQuery(eq(101L), anyString(), any(), any(), eq(120))).thenReturn(true);
        when(claims.completePersistentVideoWithFrames(any(), any(), any(), any(), any()))
                .thenReturn(new PersistentVideoCompletion(true, null));
        return task;
    }

    private WorkAuditTaskEntity persistentTask() {
        WorkAuditTaskEntity task = new WorkAuditTaskEntity();
        task.setId(101L); task.setWorkId(11L); task.setUserId(7L); task.setAuditRound(1);
        task.setMediaType("VIDEO"); task.setMediaObjectKey(OBJECT_KEY); task.setCiJobId("ci-job");
        task.setSnapshotMode("AVERAGE"); task.setSnapshotCount(8); task.setSnapshotDurationMs(23_133);
        task.setSnapshotPolicyVersion(VideoFrameStorageService.POLICY_VERSION); task.setSnapshotSourceVersion(SOURCE.sourceVersion());
        task.setQueryCount(0); task.setAttemptCount(1);
        JSONObject payload = new JSONObject();
        payload.put("auditRequest", TencentCiVideoAuditRequest.average(8)); payload.put("source", SOURCE);
        task.setRequestPayload(payload.toJSONString());
        return task;
    }

    private TencentCiAuditResult result(AuditResultDict outcome, boolean terminal) {
        return new TencentCiAuditResult("ci-job", terminal ? "Success" : "Running", outcome,
                outcome == AuditResultDict.BLOCK ? 1 : 0, "Normal", 0, terminal, false, "{}", List.of(),
                java.util.stream.LongStream.range(0, 8)
                        .mapToObj(i -> new VideoFrameInput(964L + i * 1000,
                                "https://bucket.cos.ap-guangzhou.myqcloud.com/frame" + i, null)).toList(), 8);
    }
}
