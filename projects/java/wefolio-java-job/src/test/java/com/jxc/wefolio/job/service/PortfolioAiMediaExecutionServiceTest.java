package com.jxc.wefolio.job.service;

import com.fasterxml.jackson.databind.node.ObjectNode;
import com.jxc.wefolio.job.config.PortfolioAiProperties;
import com.jxc.wefolio.job.entity.AiGenerationTaskEntity;
import com.jxc.wefolio.job.repo.PortfolioAiMediaRepository;
import com.jxc.wefolio.job.service.PortfolioAiModelClient.CacheStatus;
import com.jxc.wefolio.service.portfolioai.StrictAiJson;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
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
    /** 新任务与执行代次。 */
    private AiGenerationTaskEntity task() { var task = new AiGenerationTaskEntity(); task.setId(1L); task.setOwnerType("USER"); task.setOwnerId(2L); task.setRequestedBy(2L); task.setAttemptCount(1); task.setVersion(1); task.setCreatedAt(LocalDateTime.now(ZoneId.of("Asia/Shanghai"))); return task; }
    /** 新冻结计划，源标识不包含标题或文字样式。 */
    private ObjectNode request() {
        return StrictAiJson.parseObject("""
                {"modelConfig":{"model":"glm-5.3-flash","maxTokens":8192},
                 "facts":{"sources":[{"sourceNo":1,"sourceId":"9","kind":"WORK","mediaType":"VIDEO","canBind":true,"title":"作品"}],
                          "mediaPlan":{"version":"portfolio-media-v1","entries":[{"sourceNo":1,"workId":9,"sourceVersion":"source","route":"FRAMES","manifestHash":"frames","frames":[{},{},{},{},{},{},{},{}]}]}}}
                """, 10000, 16);
    }
    /** 合法摘要可缓存，未知用量继续保留预留；最终生成仍使用原协议文本。 */
    @Test void understandingPrecedesGenerationAndUnknownUsageKeepsReservedAmount() {
        properties.setMediaEnabled(true); AiGenerationTaskEntity task = task(); ObjectNode request = request();
        when(access.signedAfterVerification(eq(task), any())).thenReturn(List.of(new PortfolioAiModelClient.MediaInput("image_url", "https://private.example/frame?signature=fake", 1000L)));
        when(repository.reserve(eq(task), anyInt(), any())).thenReturn(1, 2);
        when(client.summarize(eq(task), any(), anyList(), any())).thenReturn(new PortfolioAiModelClient.Completion(SUMMARY, null, null));
        when(client.textRequestBytes(eq(task), any())).thenReturn(1000);
        when(client.generateWithUsage(eq(task), any(), any())).thenReturn(new PortfolioAiModelClient.Completion("final", 1000L, 100L));
        assertThat(service.generate(task, request, checkOwned)).isEqualTo("final");
        var order = inOrder(repository, client);
        order.verify(repository).cached(eq(9L), any(), any()); order.verify(repository).reserve(eq(task), intThat(value -> value <= 5), any());
        order.verify(client).summarize(eq(task), any(), anyList(), any());
        order.verify(repository).settle(eq(task), eq(1), isNull(), isNull(), isNull(), eq(CacheStatus.UNKNOWN), any());
        order.verify(repository).cache(eq(9L), any(), contains("婚礼舞台"), any());
        order.verify(repository).reserve(eq(task), anyInt(), any()); order.verify(client).generateWithUsage(eq(task), any(), any());
        order.verify(repository).settle(eq(task), eq(2), eq(1000L), eq(100L), isNull(), eq(CacheStatus.UNKNOWN), any());
        assertThat(request.toString()).doesNotContain("mediaSummary", "signature=fake");
    }
    /** 两个付费阶段均传递缓存命中量，但准入及发送前仍保留全价金额。 */
    @Test void cachedTokenUsageReachesBothSettlementsWithoutDiscountingReservations() {
        AiGenerationTaskEntity task = task();
        when(access.signedAfterVerification(eq(task), any())).thenReturn(List.of(new PortfolioAiModelClient.MediaInput("image_url", "https://private.example/frame", 1000L)));
        when(repository.reserve(eq(task), anyInt(), any())).thenReturn(1, 2);
        when(client.textRequestBytes(eq(task), any())).thenReturn(1000);
        when(client.summarize(eq(task), any(), anyList(), any())).thenReturn(
                new PortfolioAiModelClient.Completion(SUMMARY, 16000L, 100L, 12000L, CacheStatus.KNOWN_HIT));
        when(client.generateWithUsage(eq(task), any(), any())).thenReturn(
                new PortfolioAiModelClient.Completion("final", 1000L, 100L, 800L, CacheStatus.KNOWN_HIT));

        assertThat(service.generate(task, request(), checkOwned)).isEqualTo("final");

        // 八帧摘要和最大输出布局各按全价预留三分，不能提前假设供应商缓存命中。
        var order = inOrder(repository, client);
        order.verify(repository).checkCapacity(eq(task), eq(6), eq(2), any());
        order.verify(repository).reserve(eq(task), eq(3), any());
        order.verify(client).summarize(eq(task), any(), anyList(), any());
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
        service.generate(task, request(), checkOwned); verify(client, never()).summarize(any(), any(), any(), any());
        verify(repository).checkCapacity(eq(task), eq(3), eq(1), any());
        verify(repository, times(1)).reserve(eq(task), eq(3), any()); verify(access, times(2)).verify(eq(task), any());
        verify(repository).settle(eq(task), eq(7), eq(1000L), eq(100L), eq(800L), eq(CacheStatus.KNOWN_HIT), any());
        verify(repository, never()).cache(anyLong(), any(), anyString(), any());
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
        when(client.summarize(eq(task), any(), anyList(), any())).thenReturn(new PortfolioAiModelClient.Completion(SUMMARY, 100L, 100L));
        when(client.textRequestBytes(eq(task), any())).thenReturn(1000);
        when(client.generateWithUsage(eq(task), any(), any())).thenReturn(new PortfolioAiModelClient.Completion("final", 100L, 100L));
        assertThatThrownBy(() -> service.generate(task, request(), checkOwned)).isInstanceOfSatisfying(PortfolioAiCallException.class,
                exception -> assertThat(exception.isRetryable()).isTrue());
        assertThat(cached.get()).contains("婚礼舞台");
        task.setAttemptCount(2);
        assertThat(service.generate(task, request(), checkOwned)).isEqualTo("final");
        verify(client, times(1)).summarize(eq(task), any(), anyList(), any());
        verify(client, times(2)).generateWithUsage(eq(task), any(), any());
        verify(repository, times(3)).reserve(eq(task), anyInt(), any());
    }
    /** 预算不足或执行租约失效不允许发出任何真实请求。 */
    @Test void failedReservationStopsBeforeThePaidRequest() {
        properties.setMediaEnabled(true); AiGenerationTaskEntity task = task();
        when(access.signedAfterVerification(eq(task), any())).thenReturn(List.of(new PortfolioAiModelClient.MediaInput("image_url", "https://private.example/frame", null)));
        when(repository.reserve(eq(task), anyInt(), any())).thenThrow(new PortfolioAiCallException("SOURCE_UNAVAILABLE", "执行代次失效", false));
        assertThatThrownBy(() -> service.generate(task, request(), checkOwned)).hasMessage("执行代次失效");
        verify(client, never()).summarize(any(), any(), anyList(), any());
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
        verify(client, never()).summarize(any(), any(), anyList(), any());
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
        verify(client, never()).summarize(any(), any(), anyList(), any());
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
        verify(client, never()).summarize(any(), any(), anyList(), any());
        verify(client, never()).generateWithUsage(any(), any(), any());
    }

    /** 请求已经交给模型客户端后抛异常，不能再将可能计费的调用退款。 */
    @Test void modelTransportFailureKeepsUnknownReservation() {
        AiGenerationTaskEntity task = task();
        when(repository.reserve(eq(task), anyInt(), any())).thenReturn(1);
        when(client.summarize(eq(task), any(), any(), any())).thenThrow(new IllegalStateException("模拟传输中断"));
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
        verify(client, never()).summarize(any(), any(), anyList(), any());
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
        verify(client, never()).summarize(any(), any(), anyList(), any());
        verify(client, never()).generateWithUsage(any(), any(), any());
    }

    /** 晚到用量仍先结算，但失权后禁止缓存摘要或继续最终布局生成。 */
    @Test void lostLeaseAfterSummaryStillSettlesUsageButStopsNextPaidStage() {
        properties.setMediaEnabled(true); AiGenerationTaskEntity task = task(); Runnable owner = mock(Runnable.class);
        when(repository.reserve(eq(task), anyInt(), any())).thenReturn(1);
        when(client.summarize(eq(task), any(), anyList(), any())).thenAnswer(invocation -> {
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
}
