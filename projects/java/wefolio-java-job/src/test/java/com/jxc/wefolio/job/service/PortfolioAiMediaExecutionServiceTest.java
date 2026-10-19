package com.jxc.wefolio.job.service;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.databind.JsonNode;
import com.jxc.wefolio.service.portfolioai.PortfolioAiVideoAudioContext;
import com.jxc.wefolio.service.portfolioai.PortfolioAiMediaContract;
import com.jxc.wefolio.job.config.PortfolioAiProperties;
import com.jxc.wefolio.job.entity.AiGenerationTaskEntity;
import com.jxc.wefolio.job.repo.PortfolioAiMediaRepository;
import com.jxc.wefolio.job.service.PortfolioAiModelClient.CacheStatus;
import com.jxc.wefolio.service.portfolioai.StrictAiJson;
import com.jxc.wefolio.service.portfolioai.PortfolioAiStoredVideoSummary;
import com.jxc.wefolio.service.portfolioai.PortfolioAiFrameSelection;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.AfterEach;
import org.slf4j.LoggerFactory;

import java.time.LocalDateTime;
import java.time.Instant;
import java.time.ZoneId;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** 媒体缓存不授予权限、实际发送前预留和未知usage不退款。 */
class PortfolioAiMediaExecutionServiceTest {
    /** 摘要和预算持久边界。 */ private final PortfolioAiMediaRepository repository = mock(PortfolioAiMediaRepository.class);
    /** 当前授权替身。 */ private final PortfolioAiMediaAccessService access = mock(PortfolioAiMediaAccessService.class);
    /** 单次模型传输替身。 */ private final PortfolioAiModelClient client = mock(PortfolioAiModelClient.class);
    /** 默认关闭媒体，测试主动启用。 */ private final PortfolioAiProperties properties = new PortfolioAiProperties();
    /** 被测完整串行媒体理解流程。 */ private final PortfolioAiMediaExecutionService service = new PortfolioAiMediaExecutionService(repository, access, client, properties);
    /** 默认始终持权替身，失权边界由专项测试注入。 */ private final Runnable checkOwned = () -> {};
    /** 合法摘要仅为可见事实。 */ private static final String SUMMARY = "{\"summary\":\"婚礼舞台\",\"observations\":[\"暖色灯光\"],\"limitations\":[\"采样帧有盲区\"]}";
    /** 日志只允许输出受信任的结构诊断，不回显模拟敏感正文或未知字段。 */
    private static final String FAILURE_LOG_PREFIX = "高级 AI 媒体摘要校验失败";
    /** 模拟摘要正文中的私有标记、临时资源地址和手机号。 */
    private static final String PRIVATE_TEXT = "private-summary-marker https://private.example/raw?signature=temporary 13800138000";
    /** 模拟模型添加的不可信字段名。 */
    private static final String PRIVATE_FIELD = "private-unknown-field-marker";
    /** 捕获真实执行入口产生的服务日志。 */
    private final Logger logger = (Logger) LoggerFactory.getLogger(PortfolioAiMediaExecutionService.class);
    /** 当前测试捕获的服务告警事件。 */
    private ListAppender<ILoggingEvent> events;
    /** 测试结束后恢复原日志级别。 */
    private Level originalLevel;

    /** 每例独立捕获告警，防止日志配置影响失败诊断断言。 */
    @BeforeEach void captureSummaryDiagnostics() {
        originalLevel = logger.getLevel();
        logger.setLevel(Level.WARN);
        events = new ListAppender<>();
        events.start();
        logger.addAppender(events);
    }

    /** 还原共享日志器配置，避免影响其他服务测试。 */
    @AfterEach void releaseSummaryDiagnostics() {
        logger.detachAppender(events);
        events.stop();
        logger.setLevel(originalLevel);
    }
    /** 新任务与执行代次。 */
    private AiGenerationTaskEntity task() { var task = new AiGenerationTaskEntity(); task.setId(1L); task.setOwnerType("USER"); task.setOwnerId(2L); task.setRequestedBy(2L); task.setRequestedByUniqueCode("WFREQUEST1"); task.setAttemptCount(1); task.setVersion(1); task.setCreatedAt(LocalDateTime.now(ZoneId.of("Asia/Shanghai"))); return task; }
    /** 新冻结计划，源标识不包含标题或文字样式。 */
    private ObjectNode request() {
        return StrictAiJson.parseObject("""
                {"modelConfig":{"model":"glm-5.3-flash","maxTokens":8192},
                 "facts":{"sources":[{"sourceNo":1,"sourceId":"9","kind":"WORK","mediaType":"VIDEO","canBind":true,"title":"作品"}],
                          "mediaPlan":{"version":"portfolio-media-v1","entries":[{"sourceNo":1,"workId":9,"sourceVersion":"source","route":"FRAMES","manifestHash":"frames","frames":[{},{},{},{},{},{},{},{}]}]}}}
                """, 10000, 16);
    }

    /** 准入按真实精选分辨率预算，完整二十四帧不会被重复按旧全部输入预留。 */
    @Test void selectedHighResolutionFramesReserveThreeCentsInsteadOfFive() {
        AiGenerationTaskEntity task = task(); ObjectNode request = request();
        ObjectNode entry = (ObjectNode) request.path("facts").path("mediaPlan").path("entries").get(0);
        var frames = entry.putArray("frames");
        for (int i = 0; i < 24; i++) {
            frames.addObject().put("objectKey", "OWNER/others/video-frames/9/" + i + ".jpg")
                    .put("timeMs", i * 1000).put("bytes", 1000).put("width", 960).put("height", 540);
        }
        entry.set(PortfolioAiFrameSelection.FIELD, PortfolioAiFrameSelection.select(frames));
        when(repository.reserve(eq(task), anyInt(), any())).thenReturn(1, 2);
        when(client.summarize(eq(task), any(), any(), any(), any())).thenReturn(new PortfolioAiModelClient.Completion(SUMMARY, 6000L, 200L));
        when(client.textRequestBytes(eq(task), any())).thenReturn(1000);
        when(client.generateWithUsage(eq(task), any(), any())).thenReturn(new PortfolioAiModelClient.Completion("final", 1000L, 100L));

        assertThat(service.generate(task, request, checkOwned)).isEqualTo("final");

        verify(repository).checkCapacity(eq(task), eq(6), eq(2), any());
        verify(repository, times(2)).reserve(eq(task), eq(3), any());
        verify(repository).settle(eq(task), eq(1), eq(6000L), eq(200L), isNull(), eq(CacheStatus.UNKNOWN), any());
        assertThat(entry.path("frames")).hasSize(24);
    }

    /** 同提示词下实际发送相同八帧时新旧计划复用缓存，策略和预算元数据不改变身份。 */
    @Test void equivalentLegacyAndSelectedInputsShareCacheIdentity() {
        ObjectNode request = request(); ObjectNode entry = (ObjectNode) request.path("facts").path("mediaPlan").path("entries").get(0);
        var candidates = entry.putArray("frames");
        for (int i = 7; i >= 0; i--) {
            candidates.addObject().put("objectKey", "OWNER/others/video-frames/9/" + i + ".jpg")
                    .put("timeMs", i * 1000).put("bytes", 1000).put("width", 960).put("height", 540);
        }
        String legacy = PortfolioAiMediaExecutionService.cacheKey(entry, request);
        ObjectNode selection = PortfolioAiFrameSelection.select(candidates);
        entry.set(PortfolioAiFrameSelection.FIELD, selection);
        assertThat(PortfolioAiMediaExecutionService.cacheKey(entry, request)).isEqualTo(legacy);
        assertThat(PortfolioAiMediaExecutionService.cacheKey(
                StrictAiJson.parseObject(entry.toString(), 128 * 1024, 12), request)).isEqualTo(legacy);
        // 本断言只验证缓存投影；不合法计划的准入与错误语义由真实 Access 测试覆盖。
        selection.put("policyVersion", "future-policy").put("estimatedInputTokens", 1);
        assertThat(PortfolioAiMediaExecutionService.cacheKey(entry, request)).isEqualTo(legacy);
    }

    /** 仅实际模型输入子集变化时隔离缓存，完整清单保护仍由缓存身份中的 manifestHash 负责。 */
    @Test void selectedInputSubsetChangesCacheIdentity() {
        ObjectNode request = request(); ObjectNode entry = (ObjectNode) request.path("facts").path("mediaPlan").path("entries").get(0);
        var candidates = entry.putArray("frames");
        for (int i = 0; i < 24; i++) {
            candidates.addObject().put("objectKey", "OWNER/others/video-frames/9/" + i + ".jpg")
                    .put("timeMs", i * 1000).put("bytes", 1000).put("width", 960).put("height", 540);
        }
        String complete = PortfolioAiMediaExecutionService.cacheKey(entry, request);
        entry.set(PortfolioAiFrameSelection.FIELD, PortfolioAiFrameSelection.select(candidates));
        String highResolution = PortfolioAiMediaExecutionService.cacheKey(entry, request);
        assertThat(highResolution).isNotEqualTo(complete);
        for (var candidate : candidates) { ((ObjectNode) candidate).put("width", 512).put("height", 288); }
        entry.set(PortfolioAiFrameSelection.FIELD, PortfolioAiFrameSelection.select(candidates));
        assertThat(PortfolioAiMediaExecutionService.cacheKey(entry, request)).isEqualTo(complete);
    }

    /** 帧身份由对象键、字节和真实时刻确定，不因 JSON 属性顺序或尺寸元数据重复付费。 */
    @Test void actualFrameObjectBytesAndTimeDetermineCacheIdentity() {
        ObjectNode request = request(); ObjectNode entry = (ObjectNode) request.path("facts").path("mediaPlan").path("entries").get(0);
        var frame = entry.putArray("frames").addObject().put("objectKey", "OWNER/frame.jpg").put("bytes", 1000).put("timeMs", 1000);
        String original = PortfolioAiMediaExecutionService.cacheKey(entry, request);
        frame.put("width", 960).put("height", 540);
        assertThat(PortfolioAiMediaExecutionService.cacheKey(entry, request)).isEqualTo(original);
        for (String field : List.of("bytes", "timeMs")) {
            frame.put(field, 2000);
            assertThat(PortfolioAiMediaExecutionService.cacheKey(entry, request)).isNotEqualTo(original);
            frame.put(field, 1000);
        }
        frame.put("objectKey", "OWNER/replaced.jpg");
        assertThat(PortfolioAiMediaExecutionService.cacheKey(entry, request)).isNotEqualTo(original);
    }
    /** 合法摘要可缓存，未知用量继续保留预留；最终生成仍使用原协议文本。 */
    @Test void understandingPrecedesGenerationAndUnknownUsageKeepsReservedAmount() {
        properties.setMediaEnabled(true); AiGenerationTaskEntity task = task(); ObjectNode request = request();
        when(access.signedAfterVerification(eq(task), any())).thenReturn(List.of(new PortfolioAiModelClient.MediaInput("image_url", "https://private.example/frame?signature=fake", 1000L)));
        when(repository.reserve(eq(task), anyInt(), any())).thenReturn(1, 2);
        when(client.summarize(eq(task), any(), anyList(), any(), any())).thenReturn(new PortfolioAiModelClient.Completion(SUMMARY, null, null));
        when(client.textRequestBytes(eq(task), any())).thenReturn(1000);
        when(client.generateWithUsage(eq(task), any(), any())).thenReturn(new PortfolioAiModelClient.Completion("final", 1000L, 100L));
        assertThat(service.generate(task, request, checkOwned)).isEqualTo("final");
        var order = inOrder(repository, client);
        order.verify(repository).cached(eq(9L), any(), any()); order.verify(repository).reserve(eq(task), intThat(value -> value <= 5), any());
        order.verify(client).summarize(eq(task), any(), anyList(), any(), any());
        order.verify(repository).settle(eq(task), eq(1), isNull(), isNull(), isNull(), eq(CacheStatus.UNKNOWN), any());
        order.verify(repository).cache(eq(9L), any(), contains("婚礼舞台"), any());
        order.verify(repository).reserve(eq(task), anyInt(), any()); order.verify(client).generateWithUsage(eq(task), any(), any());
        order.verify(repository).settle(eq(task), eq(2), eq(1000L), eq(100L), isNull(), eq(CacheStatus.UNKNOWN), any());
        verify(repository).cache(eq(9L), argThat(identity -> identity.promptVersion().equals(PortfolioAiMediaSummaryPrompt.version())
                && identity.manifestHash().equals("frames")), contains("婚礼舞台"), any());
        assertThat(request.toString()).doesNotContain("mediaSummary", "signature=fake");
    }
    /** 两个付费阶段均传递缓存命中量，但准入及发送前仍保留全价金额。 */
    @Test void cachedTokenUsageReachesBothSettlementsWithoutDiscountingReservations() {
        AiGenerationTaskEntity task = task();
        when(access.signedAfterVerification(eq(task), any())).thenReturn(List.of(new PortfolioAiModelClient.MediaInput("image_url", "https://private.example/frame", 1000L)));
        when(repository.reserve(eq(task), anyInt(), any())).thenReturn(1, 2);
        when(client.textRequestBytes(eq(task), any())).thenReturn(1000);
        when(client.summarize(eq(task), any(), anyList(), any(), any())).thenReturn(
                new PortfolioAiModelClient.Completion(SUMMARY, 16000L, 100L, 12000L, CacheStatus.KNOWN_HIT));
        when(client.generateWithUsage(eq(task), any(), any())).thenReturn(
                new PortfolioAiModelClient.Completion("final", 1000L, 100L, 800L, CacheStatus.KNOWN_HIT));

        assertThat(service.generate(task, request(), checkOwned)).isEqualTo("final");

        // 八帧摘要和最大输出布局各按全价预留三分，不能提前假设供应商缓存命中。
        var order = inOrder(repository, client);
        order.verify(repository).checkCapacity(eq(task), eq(6), eq(2), any());
        order.verify(repository).reserve(eq(task), eq(3), any());
        order.verify(client).summarize(eq(task), any(), anyList(), any(), any());
        order.verify(repository).settle(eq(task), eq(1), eq(16000L), eq(100L), eq(12000L), eq(CacheStatus.KNOWN_HIT), any());
        order.verify(repository).reserve(eq(task), eq(3), any());
        order.verify(client).generateWithUsage(eq(task), any(), any());
        order.verify(repository).settle(eq(task), eq(2), eq(1000L), eq(100L), eq(800L), eq(CacheStatus.KNOWN_HIT), any());
        verify(repository, never()).releaseUnsent(any(), anyInt());
    }
    /** 命中仍先验证当前权限，拒绝后不读缓存也不发模型。 */
    @Test void revokedPermissionCannotUseCachedSummary() {
        properties.setMediaEnabled(true); AiGenerationTaskEntity task = task();
        doThrow(new PortfolioAiCallException("SOURCE_UNAVAILABLE", "已撤权", false)).when(access).verify(eq(task), any());
        assertThatThrownBy(() -> service.generate(task, request(), checkOwned)).hasMessage("已撤权");
        verifyNoInteractions(repository, client);
    }

    /** 缓存损坏直接结束，不发模型尝试修补或偷偷使用文字。 */
    @Test void corruptCacheFailsWithoutAnotherModelCall() {
        properties.setMediaEnabled(true);
        when(repository.cached(eq(9L), any(), any())).thenReturn("{broken-json");
        assertThatThrownBy(() -> service.generate(task(), request(), checkOwned)).hasMessageContaining("摘要JSON不合法");
        verifyNoInteractions(client); verify(repository, never()).reserve(any(), anyInt(), any());
    }
    /** 七天摘要缓存命中只跳过媒体调用，最终请求仍全价预留并按供应商缓存用量结算。 */
    @Test void cachedMediaAvoidsRepeatedUnderstandingAndStillChecksPermission() {
        properties.setMediaEnabled(true); AiGenerationTaskEntity task = task();
        when(repository.cached(eq(9L), any(), any())).thenReturn(SUMMARY); when(client.textRequestBytes(eq(task), any())).thenReturn(1000);
        when(repository.reserve(eq(task), anyInt(), any())).thenReturn(7);
        when(client.generateWithUsage(eq(task), any(), any())).thenReturn(
                new PortfolioAiModelClient.Completion("final", 1000L, 100L, 800L, CacheStatus.KNOWN_HIT));
        service.generate(task, request(), checkOwned); verify(client, never()).summarize(any(), any(), any(), any(), any());
        verify(repository).checkCapacity(eq(task), eq(3), eq(1), any());
        verify(repository, times(1)).reserve(eq(task), eq(3), any()); verify(access, times(2)).verify(eq(task), any());
        verify(repository).settle(eq(task), eq(7), eq(1000L), eq(100L), eq(800L), eq(CacheStatus.KNOWN_HIT), any());
        verify(repository, never()).cache(anyLong(), any(), anyString(), any());
    }

    /** 冻结配置未显式给出输出上限时，准入和发送前均按新默认 32768 token 全价预留。 */
    @Test void missingFrozenMaxTokensReservesThirtyTwoKOutputBudget() {
        AiGenerationTaskEntity task = task(); ObjectNode request = request();
        request.withObject("modelConfig").remove("maxTokens");
        when(repository.cached(eq(9L), any(), any())).thenReturn(SUMMARY);
        when(client.textRequestBytes(eq(task), any())).thenReturn(1000);
        when(client.generateWithUsage(eq(task), any(), any())).thenReturn(
                new PortfolioAiModelClient.Completion("final", 1000L, 100L));

        assertThat(service.generate(task, request, checkOwned)).isEqualTo("final");

        // 输入 1000 token、输出 32768 token 按广州全价共 9.25504 分，向上预留 10 分。
        verify(repository).checkCapacity(eq(task), eq(10), eq(1), any());
        verify(repository).reserve(eq(task), eq(10), any());
        verify(client, never()).summarize(any(), any(), anyList(), any(), any());
    }

    /** 新冻结 32768 输出上限扩大最终布局预留，媒体摘要仍保持原 4096 token 上限对应金额。 */
    @Test void frozenThirtyTwoKMaxTokensReservesMoreForGenerationWithoutChangingSummaryBudget() {
        AiGenerationTaskEntity task = task(); ObjectNode request = request();
        request.withObject("modelConfig").put("maxTokens", 32768);
        when(access.signedAfterVerification(eq(task), any())).thenReturn(List.of(
                new PortfolioAiModelClient.MediaInput("image_url", "https://private.example/frame", 1000L)));
        when(repository.reserve(eq(task), anyInt(), any())).thenReturn(1, 2);
        when(client.summarize(eq(task), any(), anyList(), any(), any())).thenReturn(
                new PortfolioAiModelClient.Completion(SUMMARY, 16000L, 100L));
        when(client.textRequestBytes(eq(task), any())).thenReturn(1000);
        when(client.generateWithUsage(eq(task), any(), any())).thenReturn(
                new PortfolioAiModelClient.Completion("final", 1000L, 100L));

        assertThat(service.generate(task, request, checkOwned)).isEqualTo("final");

        // 原八帧摘要预留 3 分，新最终布局预留 10 分，首次付费前须准入全部 13 分。
        var order = inOrder(repository, client);
        order.verify(repository).checkCapacity(eq(task), eq(13), eq(2), any());
        order.verify(repository).reserve(eq(task), eq(3), any());
        order.verify(client).summarize(eq(task), any(), anyList(), any(), any());
        order.verify(repository).reserve(eq(task), eq(10), any());
        order.verify(client).generateWithUsage(eq(task), any(), any());
    }

    /** 已接单的旧冻结 16384 输出上限继续按原价预留，不因新默认值扩大该任务预算。 */
    @Test void legacyFrozenSixteenKMaxTokensKeepsOriginalOutputReservation() {
        AiGenerationTaskEntity task = task(); ObjectNode request = request();
        request.withObject("modelConfig").put("maxTokens", 16384);
        when(repository.cached(eq(9L), any(), any())).thenReturn(SUMMARY);
        when(client.textRequestBytes(eq(task), any())).thenReturn(1000);
        when(client.generateWithUsage(eq(task), any(), any())).thenReturn(
                new PortfolioAiModelClient.Completion("final", 1000L, 100L));

        assertThat(service.generate(task, request, checkOwned)).isEqualTo("final");

        // 输入 1000 token、旧冻结输出 16384 token 按全价共 4.66752 分，仍预留 5 分。
        verify(repository).checkCapacity(eq(task), eq(5), eq(1), any());
        verify(repository).reserve(eq(task), eq(5), any());
        verify(client, never()).summarize(any(), any(), anyList(), any(), any());
    }

    /** 已接单的旧冻结 8192 输出上限继续按原价预留，不能因新默认值扩大该任务预算。 */
    @Test void legacyFrozenEightKMaxTokensKeepsOriginalOutputReservation() {
        AiGenerationTaskEntity task = task();
        when(repository.cached(eq(9L), any(), any())).thenReturn(SUMMARY);
        when(client.textRequestBytes(eq(task), any())).thenReturn(1000);
        when(client.generateWithUsage(eq(task), any(), any())).thenReturn(
                new PortfolioAiModelClient.Completion("final", 1000L, 100L));

        assertThat(service.generate(task, request(), checkOwned)).isEqualTo("final");

        // 输入 1000 token、旧冻结输出 8192 token 按全价共 2.37376 分，仍预留 3 分。
        verify(repository).checkCapacity(eq(task), eq(3), eq(1), any());
        verify(repository).reserve(eq(task), eq(3), any());
        verify(client, never()).summarize(any(), any(), anyList(), any(), any());
    }

    /** 返回前瞬时 HEAD 失败不丢失已付费摘要，下一代执行复用缓存而不重复理解。 */
    @Test void retryAfterTransientFinalVerificationReusesPaidSummary() {
        properties.setMediaEnabled(true); AiGenerationTaskEntity task = task();
        var cached = new AtomicReference<String>();
        when(repository.cached(eq(9L), any(), any())).thenAnswer(invocation -> cached.get());
        doAnswer(invocation -> { cached.set(invocation.getArgument(2)); return null; })
                .when(repository).cache(eq(9L), any(), anyString(), any());
        var checks = new AtomicInteger();
        doAnswer(invocation -> {
            if (checks.incrementAndGet() == 2) { throw new PortfolioAiCallException("MEDIA_ACCESS_RETRY", "暂时不可用", true); }
            return null;
        }).when(access).verify(eq(task), any());
        when(access.signedAfterVerification(eq(task), any())).thenReturn(List.of(new PortfolioAiModelClient.MediaInput("image_url", "https://private.example/frame", 0L)));
        when(client.summarize(eq(task), any(), anyList(), any(), any())).thenReturn(new PortfolioAiModelClient.Completion(SUMMARY, 100L, 100L));
        when(client.textRequestBytes(eq(task), any())).thenReturn(1000);
        when(client.generateWithUsage(eq(task), any(), any())).thenReturn(new PortfolioAiModelClient.Completion("final", 100L, 100L));
        assertThatThrownBy(() -> service.generate(task, request(), checkOwned)).isInstanceOfSatisfying(PortfolioAiCallException.class,
                exception -> assertThat(exception.isRetryable()).isTrue());
        assertThat(cached.get()).contains("婚礼舞台");
        task.setAttemptCount(2);
        assertThat(service.generate(task, request(), checkOwned)).isEqualTo("final");
        verify(client, times(1)).summarize(eq(task), any(), anyList(), any(), any());
        verify(client, times(2)).generateWithUsage(eq(task), any(), any());
        verify(repository, times(3)).reserve(eq(task), anyInt(), any());
    }
    /** 预算不足或执行租约失效不允许发出任何真实请求。 */
    @Test void failedReservationStopsBeforeThePaidRequest() {
        properties.setMediaEnabled(true); AiGenerationTaskEntity task = task();
        when(access.signedAfterVerification(eq(task), any())).thenReturn(List.of(new PortfolioAiModelClient.MediaInput("image_url", "https://private.example/frame", null)));
        when(repository.reserve(eq(task), anyInt(), any())).thenThrow(new PortfolioAiCallException("SOURCE_UNAVAILABLE", "执行代次失效", false));
        assertThatThrownBy(() -> service.generate(task, request(), checkOwned)).hasMessage("执行代次失效");
        verify(client, never()).summarize(any(), any(), anyList(), any(), any());
        verify(client, never()).generateWithUsage(any(), any(), any());
    }

    /** 预留后失权但尚未调用模型，退款并停止，不能占用当日未知费用额度。 */
    @Test void ownershipLossAfterReservationStopsBeforeTheModelCall() {
        properties.setMediaEnabled(true); AiGenerationTaskEntity task = task();
        Runnable owner = mock(Runnable.class);
        when(repository.reserve(eq(task), anyInt(), any())).thenAnswer(invocation -> {
            doThrow(new PortfolioAiCallException("EXECUTION_INTERRUPTED", "执行代次已失效", false)).when(owner).run();
            return 1;
        });
        assertThatThrownBy(() -> service.generate(task, request(), owner)).hasMessage("执行代次已失效");
        verify(repository).reserve(eq(task), anyInt(), any());
        verify(repository).releaseUnsent(task, 1);
        verify(client, never()).summarize(any(), any(), anyList(), any(), any());
        verify(client, never()).generateWithUsage(any(), any(), any());
    }

    /** 摘要命中后最终生成的预留也需在发送前失权时退还。 */
    @Test void ownershipLossAfterFinalReservationRefundsBeforeTheModelCall() {
        AiGenerationTaskEntity task = task(); Runnable owner = mock(Runnable.class);
        when(repository.cached(eq(9L), any(), any())).thenReturn(SUMMARY);
        when(client.textRequestBytes(eq(task), any())).thenReturn(1000);
        when(repository.reserve(eq(task), anyInt(), any())).thenAnswer(invocation -> {
            doThrow(new PortfolioAiCallException("EXECUTION_INTERRUPTED", "执行代次已失效", false)).when(owner).run();
            return 2;
        });
        assertThatThrownBy(() -> service.generate(task, request(), owner)).hasMessage("执行代次已失效");
        verify(repository).releaseUnsent(task, 2);
        verify(client, never()).summarize(any(), any(), anyList(), any(), any());
        verify(client, never()).generateWithUsage(any(), any(), any());
    }

    /** 预留期间越过绝对期限，尚未发送的摘要请求不能留下未知预留。 */
    @Test void timeoutAfterReservationRefundsBeforeTheModelCall() {
        AiGenerationTaskEntity task = task();
        when(repository.reserve(eq(task), anyInt(), any())).thenAnswer(invocation -> {
            task.setCreatedAt(task.getCreatedAt().minusSeconds(properties.getTotalTimeoutSeconds() + 1));
            return 1;
        });
        assertThatThrownBy(() -> service.generate(task, request(), checkOwned)).hasMessage("媒体处理超过任务期限");
        verify(repository).releaseUnsent(task, 1);
        verify(client, never()).summarize(any(), any(), anyList(), any(), any());
        verify(client, never()).generateWithUsage(any(), any(), any());
    }

    /** 请求已经交给模型客户端后抛异常，不能再将可能计费的调用退款。 */
    @Test void modelTransportFailureKeepsUnknownReservation() {
        AiGenerationTaskEntity task = task();
        when(repository.reserve(eq(task), anyInt(), any())).thenReturn(1);
        when(client.summarize(eq(task), any(), any(), any(), any())).thenThrow(new IllegalStateException("模拟传输中断"));
        assertThatThrownBy(() -> service.generate(task, request(), checkOwned)).hasMessage("模拟传输中断");
        verify(repository, never()).settle(any(), anyInt(), any(), any(), any(), any(), any());
        verify(repository, never()).releaseUnsent(any(), anyInt());
        verify(client, never()).generateWithUsage(any(), any(), any());
    }

    /** 最终客户端抛出异常同样保留未知预留，摘要缓存不能让该次调用免费。 */
    @Test void finalModelTransportFailureKeepsUnknownReservationAfterSummaryCacheHit() {
        AiGenerationTaskEntity task = task();
        when(repository.cached(eq(9L), any(), any())).thenReturn(SUMMARY);
        when(client.textRequestBytes(eq(task), any())).thenReturn(1000);
        when(repository.reserve(eq(task), anyInt(), any())).thenReturn(2);
        when(client.generateWithUsage(eq(task), any(), any())).thenThrow(new IllegalStateException("模拟最终传输中断"));

        assertThatThrownBy(() -> service.generate(task, request(), checkOwned)).hasMessage("模拟最终传输中断");

        verify(repository).reserve(eq(task), eq(3), any());
        verify(repository, never()).settle(any(), anyInt(), any(), any(), any(), any(), any());
        verify(repository, never()).releaseUnsent(any(), anyInt());
        verify(client, never()).summarize(any(), any(), anyList(), any(), any());
    }

    /** 总步骤上界预算不足时，摘要、最终生成和逐次预留都还没有发生。 */
    @Test void totalCapacityFailureStopsBeforeAnyPaidStep() {
        AiGenerationTaskEntity task = task();
        doThrow(new PortfolioAiCallException("MEDIA_BUDGET_EXHAUSTED", "预算不足", false))
                .when(repository).checkCapacity(eq(task), anyInt(), eq(2), any());
        assertThatThrownBy(() -> service.generate(task, request(), checkOwned)).hasMessage("预算不足");
        verify(repository, never()).reserve(any(), anyInt(), any());
        verify(repository, never()).cache(anyLong(), any(), anyString(), any());
        verify(access, never()).signedAfterVerification(any(), any());
        verify(client, never()).summarize(any(), any(), anyList(), any(), any());
        verify(client, never()).generateWithUsage(any(), any(), any());
    }

    /** 晚到用量仍先结算，但失权后禁止缓存摘要或继续最终布局生成。 */
    @Test void lostLeaseAfterSummaryStillSettlesUsageButStopsNextPaidStage() {
        properties.setMediaEnabled(true); AiGenerationTaskEntity task = task(); Runnable owner = mock(Runnable.class);
        when(repository.reserve(eq(task), anyInt(), any())).thenReturn(1);
        when(client.summarize(eq(task), any(), anyList(), any(), any())).thenAnswer(invocation -> {
            doThrow(new PortfolioAiCallException("EXECUTION_INTERRUPTED", "执行代次已失效", false)).when(owner).run();
            return new PortfolioAiModelClient.Completion(SUMMARY, 100L, 10L, 80L, CacheStatus.KNOWN_HIT);
        });
        assertThatThrownBy(() -> service.generate(task, request(), owner)).hasMessage("执行代次已失效");
        verify(repository).settle(eq(task), eq(1), eq(100L), eq(10L), eq(80L), eq(CacheStatus.KNOWN_HIT), any());
        verify(repository, never()).cache(anyLong(), any(), anyString(), any());
        verify(client, never()).generateWithUsage(any(), any(), any());
    }

    /** 损坏来源、不可绑定、错配ID、重复编号及丢失entry均不能访问任何外部边界。 */
    @Test void damagedFrozenContextFailsBeforeCacheSigningModelOrReservation() {
        properties.setMediaEnabled(true);
        ObjectNode missing = request(); missing.withObject("facts").withArray("sources").removeAll();
        ObjectNode unavailable = request(); ((ObjectNode) unavailable.path("facts").path("sources").get(0)).put("canBind", false);
        ObjectNode wrongWork = request(); ((ObjectNode) wrongWork.path("facts").path("sources").get(0)).put("sourceId", "10");
        ObjectNode duplicateEntry = request(); var entries = duplicateEntry.withObject("facts").withObject("mediaPlan").withArray("entries"); entries.add(entries.get(0).deepCopy());
        ObjectNode duplicateSource = request(); var sources = duplicateSource.withObject("facts").withArray("sources"); sources.add(sources.get(0).deepCopy());
        ObjectNode lostEntry = request(); lostEntry.withObject("facts").withObject("mediaPlan").withArray("entries").removeAll();
        ObjectNode wrongKind = request(); ((ObjectNode) wrongKind.path("facts").path("sources").get(0)).put("kind", "MEMBER_PORTFOLIO");
        for (ObjectNode broken : List.of(missing, unavailable, wrongWork, duplicateEntry, duplicateSource, lostEntry, wrongKind)) {
            assertThatThrownBy(() -> service.generate(task(), broken, checkOwned)).isInstanceOf(PortfolioAiCallException.class);
        }
        verifyNoInteractions(repository, access, client);
    }
    /** 已接单冻结版本决定媒体执行，Job 局部开关不能把合法任务撤销或降为文本。 */
    @Test void frozenMediaExecutesEvenWhenLocalMediaSwitchIsDisabled() {
        AiGenerationTaskEntity task = task();
        when(repository.cached(eq(9L), any(), any())).thenReturn(SUMMARY);
        when(client.textRequestBytes(eq(task), any())).thenReturn(1000);
        when(client.generateWithUsage(eq(task), any(), any())).thenReturn(new PortfolioAiModelClient.Completion("final", 1L, 1L));
        assertThat(service.generate(task, request(), checkOwned)).isEqualTo("final");
        verify(repository).cached(eq(9L), any(), any());
        verify(access, times(2)).verify(eq(task), any());
    }
    /** 未知媒体版本不能偷偷走纯文本。 */
    @Test void unsupportedFrozenMediaVersionFailsInsteadOfDowngrading() {
        ObjectNode request = request(); request.withObject("facts").withObject("mediaPlan").put("version", "unsupported");
        assertThatThrownBy(() -> service.generate(task(), request, checkOwned)).hasMessageContaining("暂未").isInstanceOf(PortfolioAiCallException.class);
        verifyNoInteractions(client, access, repository);
    }
    /** 摘要的未知字段、超长与超量内容不能影响绑定能力。 */
    @Test void summaryIsClosedBoundedAndRemovesMediaLinks() {
        assertThatThrownBy(() -> PortfolioAiMediaExecutionService.validateSummary(SUMMARY.replace("\"limitations\"", "\"url\""))).hasMessageContaining("字段");
        assertThat(PortfolioAiMediaExecutionService.validateSummary(SUMMARY.replace("婚礼舞台", "https://secret.example/path 13800138000")).toString()).doesNotContain("secret.example", "13800138000");
    }

    /** 新模型观察条目超限须记录字段与实际数量，计费用量仍在校验之前结算。 */
    @Test void modelObservationOverflowLogsSafeDiagnosticAfterSettlement() {
        assertModelSummaryFailure(summaryWithItems("observations", 9), "媒体摘要条目不合法",
                "path=$.observations", "rule=ARRAY_COUNT", "actualType=ARRAY", "actualCount=9", "maxCount=8");
    }

    /** 限制条目独立使用四条上限，不能把失败误归到观察字段。 */
    @Test void modelLimitationOverflowLogsItsOwnLimit() {
        assertModelSummaryFailure(summaryWithItems("limitations", 5), "媒体摘要条目不合法",
                "path=$.limitations", "rule=ARRAY_COUNT", "actualType=ARRAY", "actualCount=5", "maxCount=4");
    }

    /** 非数组内容只记录固定类型，不把非法字段原值当作诊断信息。 */
    @Test void modelNonArraySummaryLogsTypeWithoutRawValue() {
        ObjectNode content = privateSummary();
        content.put("observations", PRIVATE_TEXT);
        assertModelSummaryFailure(content, "媒体摘要条目不合法",
                "path=$.observations", "rule=ARRAY_TYPE", "actualType=STRING", "maxCount=8");
    }

    /** 未知字段只允许记录个数，模型生成的字段名不得进入日志。 */
    @Test void modelUnknownSummaryFieldIsCountedWithoutLeakingItsName() {
        ObjectNode content = privateSummary();
        content.put(PRIVATE_FIELD, PRIVATE_TEXT);
        assertModelSummaryFailure(content, "媒体摘要结构不合法",
                "path=$", "rule=FIELDS", "actualFields=4", "expectedFields=3", "unknownFields=1");
    }

    /** 缓存结构失败须标记缓存阶段，不能预留费用、重发模型或回写损坏摘要。 */
    @Test void invalidCachedSummaryLogsCacheStageWithoutPaidCalls() {
        AiGenerationTaskEntity task = task();
        when(repository.cached(eq(9L), any(), any())).thenReturn(summaryWithItems("observations", 9).toString());

        assertSummaryFailure(task, request(), "媒体摘要条目不合法");

        assertSafeDiagnostic("CACHE", null, "path=$.observations", "rule=ARRAY_COUNT", "actualCount=9", "maxCount=8");
        verifyNoInteractions(client);
        verify(repository, never()).reserve(any(), anyInt(), any());
        verify(repository, never()).settle(any(), anyInt(), any(), any(), any(), any(), any());
        verify(repository, never()).cache(anyLong(), any(), anyString(), any());
    }

    /** 冻结摘要损坏在任何外部访问前失败，保留原用户提示并记录内容字段位置。 */
    @Test void invalidFrozenSummaryLogsFrozenStageWithoutExternalCalls() {
        ObjectNode request = storedRequest();
        ObjectNode document = (ObjectNode) request.path("facts").path("mediaPlan").path("entries")
                .get(0).path(PortfolioAiStoredVideoSummary.FIELD);
        document.set(PortfolioAiStoredVideoSummary.KEY_CONTENT, summaryWithItems("limitations", 5));

        assertSummaryFailure(task(), request, "冻结视频摘要不合法");

        assertSafeDiagnostic("FROZEN", null, "path=$.content.limitations", "rule=ARRAY_COUNT", "actualCount=5", "maxCount=4");
        verifyNoInteractions(repository, client, access);
    }

    /** 模拟包含需保护正文的摘要，正常结构仍由真实合同负责校验。 */
    private ObjectNode privateSummary() {
        return StrictAiJson.parseObject(SUMMARY, 4096, 8).put("summary", PRIVATE_TEXT);
    }

    /** 生成指定字段的真实超量数组，使测试依赖实际服务校验而非异常替身。 */
    private ObjectNode summaryWithItems(String field, int count) {
        ObjectNode content = privateSummary();
        var items = content.putArray(field);
        for (int index = 0; index < count; index++) { items.add("private-summary-marker" + index); }
        return content;
    }

    /** 实际模型失败仍先结算，并保持既有错误合同和终止边界。 */
    private void assertModelSummaryFailure(ObjectNode content, String message, String... diagnostic) {
        AiGenerationTaskEntity task = task();
        when(repository.reserve(eq(task), anyInt(), any())).thenReturn(7);
        when(client.summarize(eq(task), any(), any(), any(), any())).thenReturn(
                new PortfolioAiModelClient.Completion(content.toString(), 100L, 20L, 80L, CacheStatus.KNOWN_HIT));
        doAnswer(invocation -> {
            assertThat(events.list).as("费用结算应发生在摘要失败诊断之前").isEmpty();
            return null;
        }).when(repository).settle(eq(task), eq(7), eq(100L), eq(20L), eq(80L), eq(CacheStatus.KNOWN_HIT), any());

        assertSummaryFailure(task, request(), message);

        assertSafeDiagnostic("MODEL", 7, diagnostic);
        var order = inOrder(client, repository);
        order.verify(client).summarize(eq(task), any(), any(), any(), any());
        order.verify(repository).settle(eq(task), eq(7), eq(100L), eq(20L), eq(80L), eq(CacheStatus.KNOWN_HIT), any());
        verify(repository, times(1)).reserve(eq(task), anyInt(), any());
        verify(repository, never()).releaseUnsent(any(), anyInt());
        verify(repository, never()).cache(anyLong(), any(), anyString(), any());
        verify(client, never()).generateWithUsage(any(), any(), any());
    }

    /** 日志增强不得改变对任务执行器公开的失败码、文案和可重试性。 */
    private void assertSummaryFailure(AiGenerationTaskEntity task, ObjectNode request, String message) {
        assertThatThrownBy(() -> service.generate(task, request, checkOwned))
                .isInstanceOfSatisfying(PortfolioAiCallException.class, exception -> {
                    assertThat(exception.getCode()).isEqualTo(PortfolioAiMediaContract.ERROR_INVALID_SUMMARY);
                    assertThat(exception.getMessage()).isEqualTo(message);
                    assertThat(exception.isRetryable()).isFalse();
                });
    }

    /** 全链路定位字段使用可信身份，日志不含模型正文、资源链接、手机号或异常堆栈。 */
    private void assertSafeDiagnostic(String stage, Integer callNo, String... diagnostic) {
        assertThat(events.list).hasSize(1);
        ILoggingEvent event = events.list.getFirst();
        assertThat(event.getLevel()).isEqualTo(Level.WARN);
        assertThat(event.getThrowableProxy()).isNull();
        assertThat(event.getFormattedMessage()).contains(FAILURE_LOG_PREFIX, "taskId=1", "requestedByUniqueCode=WFREQUEST1", "attempt=1", "workId=9", "sourceNo=1",
                        "stage=" + stage, "callNo=" + callNo, "errorCode=" + PortfolioAiMediaContract.ERROR_INVALID_SUMMARY, "diagnostic=")
                .contains(diagnostic)
                .doesNotContain("private-summary-marker", PRIVATE_FIELD, "private.example", "signature=temporary", "13800138000", "婚礼舞台");
    }

    /** v2 的两件口播分别进入各自摘要；最终任务携带全部冻结文字，后续更新不混入。 */
    @Test void v2AudioIsScopedPerSummaryAndFrozenForFinalGeneration() {
        var task = task(); ObjectNode request = request();
        request.withObject("facts").withObject("mediaPlan").put("version", PortfolioAiMediaContract.PLAN_VERSION);
        var sources = request.withObject("facts").withArray("sources");
        sources.add(sources.get(0).deepCopy());
        ((ObjectNode) sources.get(1)).put("sourceNo", 2).put("sourceId", "10");
        var entries = request.withObject("facts").withObject("mediaPlan").withArray("entries");
        ObjectNode first = (ObjectNode) entries.get(0); ObjectNode second = first.deepCopy().put("sourceNo", 2).put("workId", 10);
        entries.add(second);
        PortfolioAiVideoAudioContext.freeze(List.of(new PortfolioAiVideoAudioContext.Source(first, "第一件口播"),
                new PortfolioAiVideoAudioContext.Source(second, "第二件口播")));
        when(access.signedAfterVerification(eq(task), any())).thenReturn(List.of(new PortfolioAiModelClient.MediaInput("image_url", "https://example.test/frame", 1L)));
        when(client.summarize(eq(task), any(), anyList(), any(), any())).thenAnswer(invocation -> {
            JsonNode current = invocation.getArgument(3);
            String expected = current.path("workId").asLong() == 9 ? "第一件口播" : "第二件口播";
            assertThat(current.path(PortfolioAiVideoAudioContext.TEXT).asText()).isEqualTo(expected);
            // 模拟原冻结对象在执行后发生更新，执行私有副本不能跟随变化。
            first.put(PortfolioAiVideoAudioContext.TEXT, "后来补齐的内容");
            return new PortfolioAiModelClient.Completion(SUMMARY, 1L, 1L);
        });
        when(client.generateWithUsage(eq(task), any(), any())).thenAnswer(invocation -> {
            ObjectNode finalRequest = invocation.getArgument(1);
            assertThat(finalRequest.path("facts").path("sources").findValuesAsText(PortfolioAiVideoAudioContext.TEXT))
                    .containsExactly("第一件口播", "第二件口播");
            return new PortfolioAiModelClient.Completion("final", 1L, 1L);
        });
        assertThat(service.generate(task, request, checkOwned)).isEqualTo("final");
        verify(client, times(2)).summarize(eq(task), any(), anyList(), any(), any());
    }

    /** v1 明确保留无转写语义，附带未知音频字段也不会意外升级成 v2。 */
    @Test void legacyTaskNeverUsesAudioEvenIfOptionalFieldsWerePresent() {
        var task = task(); ObjectNode request = request();
        ObjectNode entry = (ObjectNode) request.path("facts").path("mediaPlan").path("entries").get(0);
        PortfolioAiVideoAudioContext.freeze(List.of(new PortfolioAiVideoAudioContext.Source(entry, "不应送入")));
        when(repository.cached(eq(9L), any(), any())).thenReturn(SUMMARY);
        when(client.generateWithUsage(eq(task), any(), any())).thenAnswer(invocation -> {
            ObjectNode sent = invocation.getArgument(1);
            assertThat(sent.toString()).doesNotContain("不应送入", PortfolioAiVideoAudioContext.TEXT);
            return new PortfolioAiModelClient.Completion("final", 1L, 1L);
        });
        assertThat(service.generate(task, request, checkOwned)).isEqualTo("final");
    }

    /** 只按实际输入、节选提示与策略隔离摘要，数据库未选取的尾部只影响写缓存守卫。 */
    @Test void audioChangesCacheIdentityButUnsentTailDoesNot() {
        ObjectNode request = request(); ObjectNode entry = (ObjectNode) request.path("facts").path("mediaPlan").path("entries").get(0);
        PortfolioAiVideoAudioContext.freeze(List.of(new PortfolioAiVideoAudioContext.Source(entry, "字".repeat(7000))));
        String original = PortfolioAiMediaExecutionService.cacheKey(entry, request);
        PortfolioAiVideoAudioContext.freeze(List.of(new PortfolioAiVideoAudioContext.Source(entry, "字".repeat(6000) + "尾".repeat(1000))));
        assertThat(PortfolioAiMediaExecutionService.cacheKey(entry, request)).isEqualTo(original);
        entry.put(PortfolioAiVideoAudioContext.TEXT, "不同文字");
        assertThat(PortfolioAiMediaExecutionService.cacheKey(entry, request)).isNotEqualTo(original);
        PortfolioAiVideoAudioContext.freeze(List.of(new PortfolioAiVideoAudioContext.Source(entry, "字".repeat(7000))));
        entry.put(PortfolioAiVideoAudioContext.EXCERPT, false);
        assertThat(PortfolioAiMediaExecutionService.cacheKey(entry, request)).isNotEqualTo(original);
    }

    /** 篡改音频合同在准入、权限、签名与任何模型动作之前失败。 */
    @Test void corruptAudioContextIsRejectedBeforeAnyExternalBoundary() {
        ObjectNode request = request(); request.withObject("facts").withObject("mediaPlan").put("version", PortfolioAiMediaContract.PLAN_VERSION);
        ObjectNode entry = (ObjectNode) request.path("facts").path("mediaPlan").path("entries").get(0);
        PortfolioAiVideoAudioContext.freeze(List.of(new PortfolioAiVideoAudioContext.Source(entry, "冻结文字")));
        entry.put(PortfolioAiVideoAudioContext.TEXT, "篡改文字");
        assertThatThrownBy(() -> service.generate(task(), request, checkOwned)).hasMessageContaining("音频文字上下文不合法");
        verifyNoInteractions(repository, access, client);
    }
    /** 命中持久摘要只产生最终生成费用，完整冻结摘要与转写直接进入最终上下文。 */
    @Test void v3StoredSummarySkipsCacheSigningAndSummaryBilling() {
        AiGenerationTaskEntity task = task(); ObjectNode request = storedRequest();
        when(client.textRequestBytes(eq(task), any())).thenReturn(1000);
        when(repository.reserve(eq(task), anyInt(), any())).thenReturn(1);
        when(client.generateWithUsage(eq(task), any(), any())).thenReturn(new PortfolioAiModelClient.Completion("final", 1000L, 100L));
        assertThat(service.generate(task, request, checkOwned)).isEqualTo("final");
        verify(repository).checkCapacity(eq(task), eq(3), eq(1), any());
        verify(repository, times(1)).reserve(eq(task), eq(3), any());
        verify(repository, never()).cached(anyLong(), any(), any());
        verify(repository, never()).cache(anyLong(), any(), anyString(), any());
        verify(access, never()).signedAfterVerification(any(), any());
        verify(client, never()).summarize(any(), any(), any(), any(), any());
        verify(client).generateWithUsage(eq(task), argThat(value ->
                "婚礼舞台".equals(value.path("facts").path("sources").get(0).path("mediaSummary").path("summary").asText())
                && "完整口播文字".equals(value.path("facts").path("sources").get(0).path(PortfolioAiVideoAudioContext.TEXT).asText())), any());
        verify(access, times(2)).verify(eq(task), any());
        assertThat(request.path("facts").path("sources").get(0).has("mediaSummary")).isFalse();
    }

    /** v3 缺少摘要的帧兜底沿用缓存与原调用次数预算，冻结文字进入摘要和最终上下文。 */
    @Test void v3FrameFallbackGeneratesWithAudioAndOnlyWritesExistingCache() {
        AiGenerationTaskEntity task = task(); ObjectNode request = request();
        request.withObject("facts").withObject("mediaPlan").put("version", PortfolioAiMediaContract.STORED_SUMMARY_PLAN_VERSION);
        ObjectNode entry = (ObjectNode) request.path("facts").path("mediaPlan").path("entries").get(0);
        entry.put("auditRound", 1);
        PortfolioAiVideoAudioContext.freeze(List.of(new PortfolioAiVideoAudioContext.Source(entry, "冻结口播")));
        when(access.signedAfterVerification(eq(task), any())).thenReturn(List.of(new PortfolioAiModelClient.MediaInput("image_url", "https://example.test/frame", 1000L)));
        when(client.textRequestBytes(eq(task), any())).thenReturn(1000);
        when(repository.reserve(eq(task), anyInt(), any())).thenReturn(1, 2);
        when(client.summarize(eq(task), any(), any(), any(), any())).thenReturn(new PortfolioAiModelClient.Completion(SUMMARY, 1000L, 100L));
        when(client.generateWithUsage(eq(task), any(), any())).thenReturn(new PortfolioAiModelClient.Completion("final", 1000L, 100L));
        assertThat(service.generate(task, request, checkOwned)).isEqualTo("final");
        verify(repository).checkCapacity(eq(task), anyInt(), eq(2), any());
        verify(client).summarize(eq(task), any(), any(), argThat(value -> "冻结口播".equals(value.path(PortfolioAiVideoAudioContext.TEXT).asText())), any());
        verify(repository).cache(eq(9L), argThat(identity -> "FRAMES".equals(identity.route())), contains("婚礼舞台"), any());
        verify(client).generateWithUsage(eq(task), argThat(value -> "冻结口播".equals(value.path("facts").path("sources").get(0).path(PortfolioAiVideoAudioContext.TEXT).asText())), any());
    }

    /** v3 帧缓存命中仍只产生一次最终调用，不读取后补的新持久摘要来切换路线。 */
    @Test void v3FrameFallbackCacheHitAvoidsAdditionalSummaryCall() {
        ObjectNode request = request();
        request.withObject("facts").withObject("mediaPlan").put("version", PortfolioAiMediaContract.STORED_SUMMARY_PLAN_VERSION);
        ((ObjectNode) request.path("facts").path("mediaPlan").path("entries").get(0)).put("auditRound", 1);
        when(repository.cached(eq(9L), any(), any())).thenReturn(SUMMARY);
        when(client.textRequestBytes(any(), any())).thenReturn(1000);
        when(client.generateWithUsage(any(), any(), any())).thenReturn(new PortfolioAiModelClient.Completion("final", 1000L, 100L));
        assertThat(service.generate(task(), request, checkOwned)).isEqualTo("final");
        verify(repository).checkCapacity(any(), eq(3), eq(1), any());
        verify(client, never()).summarize(any(), any(), any(), any(), any());
        verify(access, never()).signedAfterVerification(any(), any());
    }

    /** 新路线不能伪装成旧合同，新合同也不能绕回原短视频路线。 */
    @Test void v3RoutesAndAuditRoundAreValidatedBeforeAnyModelOrCacheAccess() {
        for (String version : List.of(PortfolioAiMediaContract.LEGACY_PLAN_VERSION, PortfolioAiMediaContract.PLAN_VERSION)) {
            ObjectNode value = storedRequest(); value.withObject("facts").withObject("mediaPlan").put("version", version);
            assertThatThrownBy(() -> service.generate(task(), value, checkOwned)).isInstanceOf(PortfolioAiCallException.class);
        }
        for (String route : List.of("VIDEO", "FRAMES")) {
            ObjectNode value = storedRequest(); ObjectNode entry = (ObjectNode) value.path("facts").path("mediaPlan").path("entries").get(0);
            entry.put("route", route);
            assertThatThrownBy(() -> service.generate(task(), value, checkOwned)).isInstanceOf(PortfolioAiCallException.class);
        }
        ObjectNode value = storedRequest(); ((ObjectNode) value.path("facts").path("mediaPlan").path("entries").get(0)).remove("auditRound");
        assertThatThrownBy(() -> service.generate(task(), value, checkOwned)).isInstanceOf(PortfolioAiCallException.class);
        verifyNoInteractions(repository, client, access);
    }

    /** 包装损坏不触发付费模型自救，当前授权失效也不能消费曾经合法的摘要。 */
    @Test void corruptOrUnauthorizedStoredSummaryFailsBeforeBilling() {
        ObjectNode value = storedRequest();
        ((ObjectNode) value.path("facts").path("mediaPlan").path("entries").get(0).path(PortfolioAiStoredVideoSummary.FIELD)).put("inputMode", "FRAMES");
        assertThatThrownBy(() -> service.generate(task(), value, checkOwned)).isInstanceOf(PortfolioAiCallException.class);
        doThrow(new PortfolioAiCallException("SOURCE_UNAVAILABLE", "已撤权", false)).when(access).verify(any(), any());
        assertThatThrownBy(() -> service.generate(task(), storedRequest(), checkOwned)).hasMessage("已撤权");
        verifyNoInteractions(repository, client);
    }

    /** 原视频摘要来源独立于帧清单和音频全文哈希，音频只冻结给最终生成。 */
    private ObjectNode storedRequest() {
        ObjectNode request = request();
        request.withObject("facts").withObject("mediaPlan").put("version", PortfolioAiMediaContract.STORED_SUMMARY_PLAN_VERSION);
        ObjectNode entry = (ObjectNode) request.path("facts").path("mediaPlan").path("entries").get(0);
        entry.remove(List.of("frames", "manifestHash"));
        entry.put("route", PortfolioAiMediaContract.ROUTE_STORED_VIDEO_SUMMARY).put("sourceVersion", "a".repeat(64)).put("auditRound", 1);
        entry.set(PortfolioAiStoredVideoSummary.FIELD, PortfolioAiStoredVideoSummary.create("a".repeat(64), "b".repeat(64), "glm-5.3-flash", "direct-v1",
                Instant.parse("2020-01-01T00:00:00Z"), StrictAiJson.parseObject(SUMMARY, 4096, 8)));
        PortfolioAiVideoAudioContext.freeze(List.of(new PortfolioAiVideoAudioContext.Source(entry, "完整口播文字")));
        return request;
    }

}
