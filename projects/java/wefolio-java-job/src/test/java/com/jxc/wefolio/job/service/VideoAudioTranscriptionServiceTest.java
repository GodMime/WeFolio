package com.jxc.wefolio.job.service;

import com.jxc.wefolio.job.config.VideoAudioProperties;
import com.jxc.wefolio.job.repo.VideoAudioTextRepository;
import com.jxc.wefolio.job.repo.VideoAudioTextRepository.Snapshot;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Optional;

import static com.jxc.wefolio.job.service.VideoAudioTranscriptionService.Outcome.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** 验证真实收费步骤的准入、源一致性、重建资源复用和失败隔离。 */
class VideoAudioTranscriptionServiceTest {
    /** 启用的离线测试配置，不读取真实密钥。 */
    private final VideoAudioProperties properties = new VideoAudioProperties();
    /** 控制作品状态和 CAS 结果。 */
    private final VideoAudioTextRepository repository = mock(VideoAudioTextRepository.class);
    /** 模拟跨实例作品锁入口。 */
    private final VideoAudioWorkLock locks = mock(VideoAudioWorkLock.class);
    /** 模拟当前执行持有的数据库锁。 */
    private final VideoAudioWorkLock.ExecutionLock lock = mock(VideoAudioWorkLock.ExecutionLock.class);
    /** 不访问 COS 或执行进程的提取替身。 */
    private final VideoAudioExtractor extractor = mock(VideoAudioExtractor.class);
    /** 不发送收费请求的客户端替身。 */
    private final TokenHubAudioTranscriptionClient client = mock(TokenHubAudioTranscriptionClient.class);
    /** 所有步骤共享的受控源身份。 */
    private final VideoAudioExtractor.Source source = new VideoAudioExtractor.Source("OWNER/work/video/a.mp4", "etag", 100);
    /** 被测完整音频用例。 */
    private VideoAudioTranscriptionService service;
    /** 单次执行的可观测内存预算。 */
    private VideoAudioTranscriptionService.RunBudget run;
    /** 真实临时目录与模拟探测结果，用于验证资源清理。 */
    private VideoAudioExtractor.Prepared media;
    /** 尚未生成文本的合法作品快照。 */
    private Snapshot snapshot;
    /** 每个测试隔离的临时目录。 */
    @TempDir Path temporary;

    /** 所有付费与 COS 调用都由离线替身提供。 */
    @BeforeEach void setup() throws Exception {
        properties.setEnabled(true);
        service = new VideoAudioTranscriptionService(properties, repository, locks, extractor, client);
        run = service.newRun();
        snapshot = snapshot(2, 1, null);
        Path directory = Files.createDirectory(temporary.resolve("item"));
        Path video = Files.write(directory.resolve("video"), new byte[]{1});
        media = new VideoAudioExtractor.Prepared(directory, video, source, new VideoAudioExtractor.Probe(true, 84.61));
        when(locks.tryAcquire(1)).thenReturn(Optional.of(lock));
        when(repository.find(1)).thenReturn(snapshot);
        when(repository.save(any(), anyString())).thenReturn(true);
        when(extractor.prepare(eq(source.key()), any())).thenReturn(media);
        when(extractor.inspect(eq(source.key()), any())).thenReturn(source);
        when(extractor.extract(eq(media), any())).thenReturn(video);
        when(client.transcribe(any(), any())).thenReturn(new TokenHubAudioTranscriptionClient.Result("全文", 1861L, "id", null));
    }

    /** 数据库只记录 ASR 全文，同时记录真实 token；中间资源及时销毁。 */
    @Test void savesTranscriptAndCleansTemporarySource() {
        assertEquals(SAVED, service.transcribe(1, run, future()));
        verify(repository).save(snapshot, "全文"); verify(lock).close();
        assertFalse(Files.exists(media.directory())); assertEquals(1, run.requests()); assertEquals(86, run.seconds());
        assertEquals(1, run.sentRequests()); assertEquals(1861, run.knownTokens()); assertFalse(run.usageUnknown());
    }

    /** 无音轨是成功空文本，不预留 ASR 预算、不发请求。 */
    @Test void absentAudioTrackWritesEmptyWithoutCost() throws Exception {
        media = new VideoAudioExtractor.Prepared(media.directory(), media.video(), source, new VideoAudioExtractor.Probe(false, 5));
        when(extractor.prepare(anyString(), any())).thenReturn(media);
        assertEquals(EMPTY, service.transcribe(1, run, future()));
        verify(repository).save(snapshot, ""); verifyNoInteractions(client); assertEquals(0, run.requests());
    }

    /** 已保存空字符串同样是完成状态，不反复识别同一无文本视频。 */
    @Test void existingEmptyAndNonemptyTextBothSkip() {
        for (String text : new String[]{"", "已有全文"}) {
            when(repository.find(1)).thenReturn(snapshot(2, 1, text));
            assertEquals(ALREADY_COMPLETE, service.transcribe(1, run, future()));
        }
        verifyNoInteractions(extractor, client); verify(lock, times(2)).close();
    }

    /** 重建预检后复用文件，发布新帧的版本必须重新读取再用于音频 CAS。 */
    @Test void rebuildReusesPreflightAndReloadsFrameVersion() throws Exception {
        when(repository.find(1)).thenReturn(snapshot(2, 1, "旧全文"));
        try (var prepared = service.prepare(1, true, run, future())) {
            assertTrue(prepared.ready()); verifyNoInteractions(client); assertEquals(1, run.requests());
            Snapshot afterFrames = snapshot(4, 1, null);
            when(repository.find(1)).thenReturn(afterFrames);
            assertEquals(SAVED, service.complete(prepared, future()));
            verify(repository).save(afterFrames, "全文");
            assertEquals(STALE, service.complete(prepared, future()));
        }
        verify(extractor, times(1)).prepare(anyString(), any()); verify(client, times(1)).transcribe(any(), any());
    }

    /** 秒数和请求预算都在清空前预留，拒绝路径释放文件及锁。 */
    @Test void budgetRefusalDoesNotCallAsrAndClosesPreparedResources() {
        for (var budget : new VideoAudioTranscriptionService.RunBudget[]{
                new VideoAudioTranscriptionService.RunBudget(0, 3600), new VideoAudioTranscriptionService.RunBudget(100, 84)}) {
            try (var prepared = service.prepare(1, true, budget, future())) { assertEquals(BUDGET_EXHAUSTED, prepared.outcome()); }
        }
        verifyNoInteractions(client); verify(repository, never()).save(any(), any()); assertFalse(Files.exists(media.directory()));
    }

    /** 401/402 只熔断当前运行；失败或未知计量不能被统计成零费用成功。 */
    @Test void quotaFailureStopsRemainingRunAndRetainsUnknownUsage() throws Exception {
        when(client.transcribe(any(), any())).thenThrow(new TokenHubAudioTranscriptionClient.RunDisabledException(402));
        assertEquals(FAILED, service.transcribe(1, run, future())); assertTrue(run.stopped()); assertTrue(run.usageUnknown());
        assertEquals(FAILED, service.transcribe(1, run, future())); verify(client, times(1)).transcribe(any(), any());
        verify(repository, never()).save(any(), any());
    }

    /** 账户熔断后仍跳过既有完整文本，未完成作品和强制重建保留失败语义且不再下载。 */
    @Test void stoppedRunStillRecognizesPreviouslyCompletedText() {
        run.stop();
        for (String text : new String[]{"", "已有全文"}) {
            when(repository.find(1)).thenReturn(snapshot(2, 1, text));
            assertEquals(ALREADY_COMPLETE, service.transcribe(1, run, future()));
        }
        try (var prepared = service.prepare(1, true, run, future())) {
            assertEquals(FAILED, prepared.outcome());
        }
        when(repository.find(1)).thenReturn(snapshot);
        assertEquals(FAILED, service.transcribe(1, run, future()));
        verifyNoInteractions(extractor, client);
        verify(repository, never()).save(any(), any());
        verify(lock, times(4)).close();
    }

    /** 竞争导致失锁时禁止写入，即使供应商已经返回成功也只能等待后续补齐。 */
    @Test void lockLostAfterPaidCallCannotWrite() throws Exception {
        when(client.transcribe(any(), any())).thenAnswer(invocation -> {
            doThrow(new IllegalStateException("锁失效")).when(lock).checkHeld();
            return new TokenHubAudioTranscriptionClient.Result("全文", null, null, null);
        });
        assertEquals(FAILED, service.transcribe(1, run, future())); verify(repository, never()).save(any(), any());
    }

    /** 源对象 HEAD 变化不能发送 ASR；审核轮次变化不能利用新作品权限继续。 */
    @Test void changedSourceAndStaleAuditCallbackAreDiscardedBeforePayment() throws Exception {
        assertEquals(STALE, service.transcribe(1, 0, source.key(), "sha", run, future())); verifyNoInteractions(extractor);
        when(extractor.inspect(anyString(), any())).thenReturn(new VideoAudioExtractor.Source(source.key(), "new-etag", 100));
        assertEquals(STALE, service.transcribe(1, run, future())); verifyNoInteractions(client);
    }

    /** 旧帧仍指向不同 COS 内容时，即使数据库源键未变，也不把新源音频写到旧帧上下文。 */
    @Test void existingManifestMustMatchFrozenCosSource() {
        when(repository.find(1)).thenReturn(new Snapshot(1, 1, source.key(), "sha", 1, 2,
                "{\"sourceObjectKey\":\"OWNER/work/video/a.mp4\",\"sourceEtag\":\"old-etag\",\"sourceBytes\":100,\"sourceVersion\":\"old\"}", null, "OWNER"));
        assertEquals(STALE, service.transcribe(1, run, future()));
        verifyNoInteractions(client); verify(repository, never()).save(any(), any());
    }

    /** 来源计量溢出不能绕过已知量与未知量区分。 */
    @Test void usageOverflowRemainsUnknownInsteadOfWrapping() {
        run.sent(); run.recordUsage(Long.MAX_VALUE);
        run.sent(); run.recordUsage(1L);
        assertEquals(Long.MAX_VALUE, run.knownTokens()); assertEquals(1, run.unknownUsageCount());
    }

    /** 提取时出现普通编辑也安全丢弃，不放松 CAS 或继续收费。 */
    @Test void concurrentEditBeforeAsrPreventsPaidCall() throws Exception {
        when(extractor.extract(any(), any())).thenAnswer(invocation -> {
            when(repository.find(1)).thenReturn(snapshot(3, 1, null)); return media.video();
        });
        assertEquals(STALE, service.transcribe(1, run, future())); verifyNoInteractions(client);
    }

    /** 单实例并发限制覆盖预检，未获准作品既不借数据库连接也不下载。 */
    @Test void localCapacityAndDatabaseContentionSkipWithoutCost() throws Exception {
        try (var prepared = service.prepare(1, false, run, future())) {
            assertTrue(prepared.ready()); assertEquals(BUSY, service.transcribe(2, run, future()));
            verify(locks, never()).tryAcquire(2);
        }
        when(locks.tryAcquire(1)).thenReturn(Optional.empty());
        assertEquals(BUSY, service.transcribe(1, run, future())); verifyNoInteractions(client);
    }

    /** 历史执行锁的检查异常不能被绕过，且不会消费收费预算。 */
    @Test void callerCancellationAndDisabledFeatureHaveNoSideEffects() {
        assertEquals(FAILED, service.transcribe(1, run, future(), () -> { throw new IllegalStateException("批次失锁"); }));
        properties.setEnabled(false); assertEquals(DISABLED, service.transcribe(1, run, future()));
        verifyNoInteractions(locks, extractor, client, repository);
    }

    /** 同源帧版本只保留必要夹具，不包含真实用户文字。 */
    private Snapshot snapshot(int version, int round, String text) {
        return new Snapshot(1, 1, source.key(), "sha", round, version, null, text, "OWNER");
    }
    /** 独立于审核租约的测试总期限。 */
    private Instant future() { return Instant.now().plusSeconds(360); }
}
